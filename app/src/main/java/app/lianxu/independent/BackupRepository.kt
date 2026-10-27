package app.lianxu.independent

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.gson.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** One database transaction publishes both data and the prepared encrypted vault generation. */
internal class BackupRepository(private val store:Store,private val vault:ModelVault){
    data class Export(val root:JsonObject,val files:Map<String,ByteArray>){override fun toString()="BackupExport(redacted)"}
    data class Preview(val pack:BackupArchive.Package,val mode:String,val target:String?,val fingerprint:String,val contentDigest:String,val impact:String){override fun toString()="BackupPreview(redacted)"}
    data class RoleDeletion(val friendId:String,val name:String,val characterStamp:String)
    private val base=RoleArchive.tables.keys.toList()
    private val extra=listOf("processing_policies","memory_batches","memory_operations","memory_batch_reviews","candidate_injections","relationship_jobs")
    private val order=base+extra
    private val globalKeys=setOf("nickname","compact",MemoryRules.KEY)
    private val legacy=RoleArchiveRepository(store)
    private val repo=ProcessingRepository(store)
    private class Budget {
        private var json=0L;private var assets=0L
        fun json(value:JsonElement){json+=value.toString().toByteArray(Charsets.UTF_8).size;require(json<=BackupArchive.MAX_JSON){"备份文字资料超过8 MiB，请先减少资料后重试"};require(json+assets<=BackupArchive.MAX_EXPANDED){"备份解压内容超过48 MiB"}}
        fun asset(size:Int){assets+=size;require(assets+json<=BackupArchive.MAX_EXPANDED){"备份解压内容超过48 MiB"}}
    }
    private fun rows(db:SQLiteDatabase,table:String,id:String,budget:Budget):JsonArray {
        val predicate=when(table){
            "message_state","message_history"->"message_id IN (SELECT id FROM messages WHERE friend_id=?)"
            "memory_history"->"memory_id IN (SELECT id FROM memory_current WHERE friend_id=?)"
            "memory_operations","memory_batch_reviews"->"batch_id IN (SELECT id FROM memory_batches WHERE friend_id=?)"
            else->"friend_id=?"
        }
        return db.rawQuery("SELECT * FROM $table WHERE $predicate ORDER BY rowid",arrayOf(id)).use{c->JsonArray().apply{while(c.moveToNext())add(row(c).also{budget.json(it)})}}
    }
    private fun row(c:Cursor)=JsonObject().apply{c.columnNames.forEachIndexed{i,k->when(c.getType(i)){Cursor.FIELD_TYPE_NULL->add(k,JsonNull.INSTANCE);Cursor.FIELD_TYPE_INTEGER->addProperty(k,c.getLong(i));Cursor.FIELD_TYPE_STRING->addProperty(k,c.getString(i));else->error("资料含不支持的列类型")}}}
    private fun prefs(db:SQLiteDatabase)=db.rawQuery("SELECT key,value FROM preferences ORDER BY key",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}
    private fun roleKey(key:String,id:String,turns:Set<String>,batches:Set<String>):Boolean =
        key in setOf(RolePromptStore.key(id),"role-examples:$id",MemoryAssociations.key(id))||key.startsWith("memory-reference-v1:$id:")||turns.any{key=="memory-recall-usage:$it"}||batches.any{key=="memory-dropped:$it"}
    private fun ignored(key:String)=key.startsWith("memory-tools:")||key.startsWith("memory-vectors-v1:")||key.startsWith("model-vault-generation:")
    private fun snapshot(db:SQLiteDatabase,id:String,files:MutableMap<String,ByteArray>,budget:Budget=Budget()):JsonObject {
        val friend=db.rawQuery("SELECT * FROM friends WHERE id=?",arrayOf(id)).use{check(it.moveToFirst()){ "角色已不存在" };row(it).also(budget::json)}
        val tables=JsonObject();order.forEach{table->tables.add(table,rows(db,table,id,budget))}
        tables["chat_turns"].asJsonArray.forEach{v->v.asJsonObject.apply{addProperty("owner_session","");if(get("state").asString=="PENDING"){addProperty("state","INTERRUPTED");addProperty("error_code","INTERRUPTED");add("assistant_message_id",JsonNull.INSTANCE)}}}
        for(table in listOf("memory_batches","relationship_jobs"))tables[table].asJsonArray.forEach{v->v.asJsonObject.apply{
            if(has("owner_session"))addProperty("owner_session","")
            if(has("next_retry"))addProperty("next_retry",0)
            if(get("state").asString !in setOf("COMMITTED","COMPLETED")){addProperty("state","ARCHIVED");addProperty("error","MANUAL_RETRY")}
        }}
        val turns=tables["chat_turns"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet();val batches=tables["memory_batches"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
        val preferences=JsonObject();prefs(db).filterKeys{roleKey(it,id,turns,batches)}.forEach{(k,v)->
            val value=if(k=="role-examples:$id")runCatching{JsonParser.parseString(v).asJsonObject.apply{if(get("state")?.asString=="SELECTING"){addProperty("state","FAILED");addProperty("attempt","");addProperty("reason","导入前的挑选任务没有恢复，请手动重试。")}}.toString()}.getOrDefault(v)else v
            budget.json(JsonPrimitive(value));preferences.addProperty(k,value)
        }
        val assets=JsonArray();db.rawQuery("SELECT path,kind,mime,length(data) FROM role_assets WHERE friend_id=? ORDER BY path",arrayOf(id)).use{c->while(c.moveToNext()){
            val size=c.getInt(3);require(size in 0..BackupArchive.MAX_FILE){"单个资源超过16 MiB"};budget.asset(size);val data=RoleAssets.read(db,id,c.getString(0),size);val name="assets/${files.size}.bin";files[name]=data
            assets.add(JsonObject().apply{addProperty("path",c.getString(0));addProperty("kind",c.getString(1));addProperty("mime",c.getString(2));addProperty("entry",name)})
        }}
        return JsonObject().apply{add("character",friend);add("tables",tables);add("preferences",preferences);add("assets",assets)}
    }
    suspend fun export(id:String?=null,includeKeys:Boolean=false):Export=read{db->
        require(id==null||!includeKeys)
        val ids=if(id!=null)listOf(id)else db.rawQuery("SELECT id FROM friends ORDER BY rowid",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        require(ids.size<=100){"一次最多备份100个角色"}
        val budget=Budget();val files=linkedMapOf<String,ByteArray>();val roles=JsonArray();ids.forEach{roles.add(snapshot(db,it,files,budget))}
        val settings=JsonObject();if(id==null){
            val represented=roles.flatMap{it.asJsonObject["preferences"].asJsonObject.keySet()}.toSet()
            prefs(db).forEach{(k,v)->when{ k in globalKeys->settings.addProperty(k,v);k in represented||ignored(k)->Unit;else->error("存在尚未分类的持久设置，已停止以避免生成不完整备份") }}
        }
        val models=JsonArray();val selections=JsonObject();if(id==null){val saved=vault.backup(includeKeys);saved.first.forEach{models.add(modelJson(it,includeKeys))};saved.second.forEach{(p,v)->selections.addProperty(p.key,v)}}
        Export(JsonObject().apply{addProperty("scope",if(id==null)"all" else "role");add("roles",roles);add("preferences",settings);add("models",models);add("selections",selections);addProperty("includesKeys",includeKeys)},files).also{validate(db,it.root,it.files)}
    }
    private fun modelJson(row:BackupModel,includeKeys:Boolean)=JsonObject().apply{add("config",Gson().toJsonTree(row.config));if(includeKeys)addProperty("key",row.secret.orEmpty())}
    private fun models(root:JsonObject)=root["models"].asJsonArray.map{v->val j=v.asJsonObject;require(j.keySet()==if(root["includesKeys"].asBoolean)setOf("config","key")else setOf("config"));val fields=j["config"].asJsonObject.keySet();val old=setOf("id","name","providerId","baseUrl","modelId","contextTokens","outputTokens");require(fields==old||fields==old+"memoryOutputTokens");val config=Gson().fromJson(j["config"],ModelConfig::class.java)
        require(config.id.matches(Regex("[a-zA-Z0-9-]{1,80}"))&&config.name.length<=200&&config.modelId.length<=200&&config.baseUrl.length<=2000&&ConfigRules.validate(config,"validation-placeholder")==null){"模型配置无效"}
        val key=if(j.has("key"))j["key"].asString else null;require(key==null||key.length<=4096&&key.none{it.isWhitespace()||it.code<32}){"密钥字段格式无效"};BackupModel(config,key)
    }
    private fun validate(db:SQLiteDatabase,root:JsonObject,files:Map<String,ByteArray>){
        require(root["preferences"].asJsonObject.keySet().all{it in globalKeys})
        root["preferences"].asJsonObject.entrySet().forEach{(k,v)->require(v.isJsonPrimitive&&v.asJsonPrimitive.isString);when(k){"compact"->require(v.asString in setOf("true","false"));"nickname"->require(v.asString.length in 1..80);MemoryRules.KEY->require(MemoryRules.from(JSONObject(v.asString)).valid())}}
        val configs=models(root);require(configs.map{it.config.id}.distinct().size==configs.size)
        root["selections"].asJsonObject.entrySet().forEach{(k,v)->require(ModelPurpose.entries.any{it.key==k}&&(v.asString.isEmpty()||configs.any{it.config.id==v.asString}))}
        val ids=hashSetOf<String>();val used=hashSetOf<String>();var total=0
        root["roles"].asJsonArray.forEach{v->val role=v.asJsonObject;require(role.keySet()==setOf("character","tables","preferences","assets"));val id=role["character"].asJsonObject["id"].asString;require(ids.add(id))
            val tables=role["tables"].asJsonObject;require(tables.keySet()==order.toSet())
            total+=base.sumOf{tables[it].asJsonArray.size()};require(total<=200000){"备份记录超过200000行"}
            val old=JsonObject().apply{addProperty("scope","records");add("character",role["character"]);add("prompts",JsonParser.parseString(role["preferences"].asJsonObject[RolePromptStore.key(id)]?.asString?:"{}"));add("records",JsonObject().apply{base.forEach{add(it,tables[it].deepCopy())}})}
            old["records"].asJsonObject["chat_turns"].asJsonArray.forEach{it.asJsonObject.addProperty("config_id","")}
            legacy.validateForBackup(db,RoleArchive.validate(old.toString().toByteArray()))
            val turns=tables["chat_turns"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet();val batches=tables["memory_batches"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
            val messageIds=tables["messages"].asJsonArray.map{it.asJsonObject["id"].asLong}.toSet()
            val memoryIds=tables["memory_current"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
            val candidateIds=tables["memory_candidates"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
            val proposedIds=tables["memory_candidates"].asJsonArray.map{JsonParser.parseString(it.asJsonObject["json"].asString).asJsonObject["proposed"].asJsonObject["id"].asString}.toSet()
            role["preferences"].asJsonObject.entrySet().forEach{(k,value)->require(roleKey(k,id,turns,batches)&&value.isJsonPrimitive&&value.asJsonPrimitive.isString);RoleArchive.parse(("{\"value\":"+value.asString+"}").toByteArray())}
            for(table in extra){val schema=db.rawQuery("PRAGMA table_info($table)",null).use{c->buildMap{while(c.moveToNext())put(c.getString(1),c.getString(2))}}
                val rows=tables[table].asJsonArray;total+=rows.size();require(total<=200000)
                rows.forEach{r->val j=r.asJsonObject;require(j.keySet()==schema.keys);j.entrySet().forEach{(k,x)->if(!x.isJsonNull){if(schema[k]=="INTEGER")RoleArchive.integer(x)else require(x.isJsonPrimitive&&x.asJsonPrimitive.isString)}};if(j.has("friend_id"))require(j["friend_id"].asString==id)
                    if(table in setOf("memory_operations","memory_batch_reviews"))require(j["batch_id"].asString in batches){"批次引用不属于包内角色"}
                    if(table=="memory_operations"&&!j["result_id"].isJsonNull)require(j["result_id"].asString in memoryIds+proposedIds){"操作结果引用不属于包内角色"}
                    if(table=="memory_batch_reviews")require(j["memory_id"].asString in memoryIds){"记忆审阅引用不属于包内角色"}
                    if(table=="candidate_injections")require(j["candidate_id"].asString in candidateIds&&j["user_message_id"].asLong in messageIds){"候选引用不属于包内角色"}
                    if(table=="memory_batches")require(j["state"].asString in setOf("COMMITTED","ARCHIVED")&&j["next_retry"].asLong==0L)
                    if(table=="relationship_jobs"){require(j["state"].asString in setOf("COMPLETED","ARCHIVED"));val work=RelationshipWork.from(JSONObject(j["json"].asString));require(work.friendId==id&&work.id==j["id"].asString&&work.targets.all{it.source.characterId==id})}
                }
            }
            val paths=hashSetOf<String>();role["assets"].asJsonArray.forEach{a->val j=a.asJsonObject;require(j.keySet()==setOf("path","kind","mime","entry"));val path=RoleArchive.entryName(j["path"].asString);require(path.length<=240&&paths.add(path)&&j["kind"].asString in setOf("avatar","attachment")&&j["mime"].asString.length in 1..100);val entry=j["entry"].asString;require(used.add(entry)&&files.containsKey(entry)&&files.getValue(entry).size<=BackupArchive.MAX_FILE)}
        }
        require(used==files.keys){"资源清单不完整"}
    }
    private fun fingerprint(db:SQLiteDatabase):String {
        val hash=java.security.MessageDigest.getInstance("SHA-256")
        fun add(value:String){val bytes=value.toByteArray(Charsets.UTF_8);hash.update((bytes.size.toString()+":").toByteArray());hash.update(bytes)}
        for(table in listOf("friends")+order+listOf("preferences")){
            add(table);db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use{c->while(c.moveToNext())add(row(c).toString())}
        }
        db.rawQuery("SELECT friend_id,path,kind,mime,length(data) FROM role_assets ORDER BY friend_id,path",null).use{c->while(c.moveToNext()){
            add(c.getString(0));add(c.getString(1));add(c.getString(2));add(c.getString(3));add(RoleArchive.hash(RoleAssets.read(db,c.getString(0),c.getString(1),c.getInt(4))))
        }}
        add(vault.stamp());return hash.digest().joinToString(""){"%02x".format(it)}
    }
    suspend fun preview(pack:BackupArchive.Package,mode:String,target:String?=null):Preview=read{db->
        require(mode in setOf("ADD","REPLACE"));require(mode!="REPLACE"||pack.all||target!=null){"请选择要覆盖的角色"}
        require(pack.legacy==null){"请通过旧格式兼容入口导入"}
        validate(db,pack.root,pack.files)
        if(mode=="REPLACE"&&target!=null)require(db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(target)).use{it.moveToFirst()})
        val impact=if(mode=="ADD")"新增 ${pack.roles.size()} 个独立角色及全部包内角色资料；保留当前角色、设置、模型服务和密钥，不启用包内凭据。"else if(pack.all)"替换软件全部角色、聊天、记忆、关系、资源、设置和模型配置。${if(pack.includesKeys)"备份含可直接使用的 API 密钥，请勿分享。"else "备份没有密钥：仅按原服务身份和原端点匹配保留，不能匹配的原安全配置会另行保留，不自动选择。"}不会恢复在途任务或自动请求模型。"else "替换明确选中的角色全部资料和资源，其他角色及全局模型配置保留。"
        Preview(pack,mode,target,fingerprint(db),contentDigest(pack),impact)
    }
    private fun contentDigest(pack:BackupArchive.Package)=RoleArchive.hash((pack.root.toString()+pack.files.toSortedMap().entries.joinToString{it.key+RoleArchive.hash(it.value)}).toByteArray())
    private fun cv(row:JsonObject)=ContentValues().apply{row.entrySet().forEach{(k,v)->when{v.isJsonNull->putNull(k);v.asJsonPrimitive.isNumber->put(k,RoleArchive.integer(v));else->put(k,v.asString)}}}
    suspend fun commit(plan:Preview,confirmed:Boolean=false,lease:ChatAttemptLease=ChatAttemptLease(),checkpoint:()->Unit={}):List<String> =withContext(Dispatchers.IO){val action={
        require(plan.mode!="REPLACE"||confirmed){"覆盖需要明确确认"};if(plan.mode=="REPLACE"){if(plan.pack.all)store.memorySummary.requireAllEditable()else store.memorySummary.requireEditable(requireNotNull(plan.target))};val db=store.writableDatabase;var staged:ModelVault.Prepared?=null
        db.beginTransaction();var committed=false
        try {
            check(lease.isActive()){ "导入已取消" };require(contentDigest(plan.pack)==plan.contentDigest){"备份内容已变化，请重新预览"};require(fingerprint(db)==plan.fingerprint){"资料或配置已变化，请重新预览"};validate(db,plan.pack.root,plan.pack.files)
            if(plan.mode=="REPLACE"&&plan.pack.all){staged=vault.prepareRestore(models(plan.pack.root),plan.pack.root["selections"].asJsonObject.entrySet().associate{(k,v)->ModelPurpose.entries.first{it.key==k} to v.asString},plan.pack.includesKeys);clearAll(db);checkpoint()}
            val imported=plan.pack.roles.map{v->val old=v.asJsonObject;val target=if(plan.mode=="REPLACE"&&!plan.pack.all)plan.target!! else UUID.randomUUID().toString();if(plan.mode=="REPLACE"&&!plan.pack.all)clearRole(db,target);restore(db,old,target,plan.pack.files,checkpoint);target}
            if(plan.mode=="REPLACE"&&plan.pack.all){plan.pack.root["preferences"].asJsonObject.entrySet().forEach{(k,v)->pref(db,k,v.asString)};pref(db,vault.generationKey,staged!!.generation)}
            checkpoint();check(lease.commit{db.setTransactionSuccessful();true}){ "导入已取消" }
            db.endTransaction();committed=true
            staged?.let{vault.finishRestore(it)}
            imported
        }finally{
            try{if(db.inTransaction())db.endTransaction()}finally{
                // A failed/uncertain SQLite commit must never delete the generation it may have published.
                if(!committed)staged?.let{if(vault.isActive(it)==false)vault.abort(it)}
            }
        }
    };if(plan.mode=="REPLACE"&&plan.pack.all)AppMutationGate.write(action)else AppMutationGate.read(action)}
    suspend fun previewRoleDeletion(id:String):RoleDeletion=read{db->
        val name=db.rawQuery("SELECT name FROM friends WHERE id=?",arrayOf(id)).use{require(it.moveToFirst()){"角色已不存在，请返回列表。"};it.getString(0)}
        RoleDeletion(id,name,repo.characterStamp(db,id))
    }
    suspend fun deleteRole(plan:RoleDeletion,confirmed:Boolean,lease:ChatAttemptLease=ChatAttemptLease(),checkpoint:()->Unit={})=withContext(Dispatchers.IO){
        require(confirmed){"删除角色需要再次确认"}
        // Keep other characters' in-flight config epochs valid; serialize only the local transaction.
        AppMutationGate.read{
            val db=store.writableDatabase;db.beginTransaction()
            try {
                store.memorySummary.requireEditable(plan.friendId)
                check(lease.isActive()){"删除已取消"}
                require(repo.characterStamp(db,plan.friendId)==plan.characterStamp){"角色资料已变化，请重新确认。"}
                clearRole(db,plan.friendId);checkpoint()
                check(lease.commit{db.setTransactionSuccessful();true}){"删除已取消"}
            }finally{db.endTransaction()}
        }
    }
    private fun clearRole(db:SQLiteDatabase,id:String){
        fun ids(table:String)=db.rawQuery("SELECT id FROM $table WHERE friend_id=?",arrayOf(id)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        val turnIds=ids("chat_turns");val batches=ids("memory_batches")
        prefs(db).keys.filter{key->roleKey(key,id,turnIds,batches)||key=="memory-vectors-v1:$id"||turnIds.any{key.startsWith("memory-tools:$it:")}}.forEach{db.delete("preferences","key=?",arrayOf(it))}
        db.delete("role_assets","friend_id=?",arrayOf(id))
        for(table in (extra+base).reversed().distinct()){
            val predicate=when(table){"message_state","message_history"->"message_id IN (SELECT id FROM messages WHERE friend_id=?)";"memory_history"->"memory_id IN (SELECT id FROM memory_current WHERE friend_id=?)";"memory_operations","memory_batch_reviews"->"batch_id IN (SELECT id FROM memory_batches WHERE friend_id=?)";else->"friend_id=?"}
            db.delete(table,predicate,arrayOf(id))
        }
        db.delete("friends","id=?",arrayOf(id))
    }
    private fun clearAll(db:SQLiteDatabase){
        // Foreign key order also removes in-flight rows; none are imported or restarted.
        listOf("memory_operations","memory_batch_reviews","candidate_injections","message_state","message_history","memory_history","memory_current","memory_candidates","memory_retired","memory_progress","relationship_state","relationship_history","relationship_jobs","memory_batches","processing_policies","role_assets","chat_turns","messages","friends","preferences").forEach{db.delete(it,null,null)}
    }
    private fun restore(db:SQLiteDatabase,role:JsonObject,id:String,files:Map<String,ByteArray>,checkpoint:()->Unit){
        val oldId=role["character"].asJsonObject["id"].asString;val tables=role["tables"].asJsonObject
        val strings=linkedMapOf<String,String>();listOf("memory_current","memory_candidates","chat_turns","memory_batches","relationship_jobs").forEach{table->tables[table].asJsonArray.forEach{strings.putIfAbsent(it.asJsonObject["id"].asString,UUID.randomUUID().toString())}}
        tables["memory_candidates"].asJsonArray.forEach{val proposed=JsonParser.parseString(it.asJsonObject["json"].asString).asJsonObject["proposed"].asJsonObject["id"].asString;strings.putIfAbsent(proposed,UUID.randomUUID().toString())}
        tables["memory_retired"].asJsonArray.forEach{strings.putIfAbsent(it.asJsonObject["batch_id"].asString,UUID.randomUUID().toString())}
        var next=db.rawQuery("SELECT COALESCE(MAX(seq),0) FROM sqlite_sequence WHERE name='messages'",null).use{it.moveToFirst();it.getLong(0)}
        val messages=tables["messages"].asJsonArray.associate{it.asJsonObject["id"].asLong to ++next}
        val map=BackupRemapper(oldId,id,strings,messages)
        db.insertOrThrow("friends",null,cv(map.json(role["character"]).asJsonObject.apply{addProperty("id",id)}))
        for(table in order){for(value in tables[table].asJsonArray){checkpoint();val row=map.json(value).asJsonObject
            if(table=="processing_policies"){val p=ProcessingPolicy.from(JSONObject(row["json"].asString));row.addProperty("json",p.copy(relationshipAutomatic=false).json().toString())}
            db.insertOrThrow(table,null,cv(row))
        }}
        role["preferences"].asJsonObject.entrySet().forEach{(key,value)->pref(db,map.preferenceKey(key),map.preferenceValue(value.asString))}
        // Snapshot identity is rebound only after character data and prompt preferences exist.
        db.rawQuery("SELECT json FROM relationship_jobs WHERE friend_id=?",arrayOf(id)).use{c->if(c.moveToFirst()){val work=JSONObject(c.getString(0)).put("characterStamp",repo.characterStamp(db,id));db.update("relationship_jobs",ContentValues().apply{put("json",work.toString());put("owner_session","")},"friend_id=?",arrayOf(id))}}
        role["assets"].asJsonArray.forEach{v->val a=v.asJsonObject;db.insertOrThrow("role_assets",null,ContentValues().apply{put("friend_id",id);put("path",a["path"].asString);put("kind",a["kind"].asString);put("mime",a["mime"].asString);put("data",files.getValue(a["entry"].asString))})}
    }
    private fun pref(db:SQLiteDatabase,key:String,value:String){db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    private suspend fun <T> read(block:(SQLiteDatabase)->T):T=withContext(Dispatchers.IO){AppMutationGate.read{val db=store.readableDatabase;db.beginTransaction();try{block(db)}finally{db.endTransaction()}}}
}

internal class BackupRemapper(private val old:String,private val id:String,private val strings:Map<String,String>,private val messages:Map<Long,Long>){
    private val numeric=setOf("message_id","source_user_id","user_message_id","assistant_message_id","user_id","assistant_id","turn","turnId","cursor","boundary")
    private val ids=setOf("id","request","task","task_id","batch_id","batch","candidate_id","target_id","memory_id","result_id","source","target","sourceId","targetId","memoryIds","retainedIds")
    fun json(v:JsonElement,key:String=""):JsonElement=when{
        v.isJsonObject->JsonObject().apply{v.asJsonObject.entrySet().forEach{(k,x)->add(if(key in setOf("associationVersions","existingVersions","resultVersions"))strings[k]?:error("记忆版本引用缺失") else k,json(x,k))}}
        v.isJsonArray->JsonArray().apply{v.asJsonArray.forEach{add(json(it,key))}}
        v.isJsonNull->JsonNull.INSTANCE
        v.asJsonPrimitive.isNumber&&(key in numeric||key=="id")->JsonPrimitive(if(v.asLong==0L)0L else messages[v.asLong]?:error("原话编号无法映射"))
        v.asJsonPrimitive.isString->{val s=v.asString;when{key=="json"->JsonPrimitive(json(JsonParser.parseString(s)).toString());key in setOf("friend_id","friendId","character","character_id","characterId","conversation")&&s==old->JsonPrimitive(id);key in ids->JsonPrimitive(if(key=="id"&&s==old)id else strings[s]?:s);key=="owner_session"->JsonPrimitive("");else->v.deepCopy()}}
        else->v.deepCopy()
    }
    fun preferenceKey(key:String):String {
        listOf("role-prompts:","role-examples:","memory-associations-v1:").forEach{if(key==it+old)return it+id}
        if(key.startsWith("memory-reference-v1:$old:")){val n=key.substringAfterLast(':').toLong();return "memory-reference-v1:$id:${messages[n]?:error("原话引用缺失")}"}
        if(key.startsWith("memory-recall-usage:"))return "memory-recall-usage:"+(strings[key.removePrefix("memory-recall-usage:")]?:error("轮次引用缺失"))
        if(key.startsWith("memory-dropped:"))return "memory-dropped:"+(strings[key.removePrefix("memory-dropped:")]?:error("批次引用缺失"))
        error("不支持的角色设置")
    }
    fun preferenceValue(raw:String)=runCatching{json(JsonParser.parseString(raw)).toString()}.getOrElse{error("角色设置格式无效")}
}
