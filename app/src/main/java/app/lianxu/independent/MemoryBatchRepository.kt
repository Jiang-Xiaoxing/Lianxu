package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MemoryWork(val id:String,val friendId:String,val targets:List<SourceDialogue>,val policyVersion:Int,val characterStamp:String,val configStamp:String,val existingVersions:Map<String,Int> = emptyMap(),val runId:String=UUID.randomUUID().toString(),val rulesVersion:Int=0,val associationVersions:Map<String,Int> = emptyMap()) {
    fun json()=JSONObject().put("id",id).put("character",friendId).put("targets",JSONArray().apply{targets.forEach{put(it.json())}}).put("policyVersion",policyVersion).put("characterStamp",characterStamp).put("configStamp",configStamp).put("existingVersions",JSONObject().apply{existingVersions.toSortedMap().forEach{(id,version)->put(id,version)}}).put("runId",runId).put("rulesVersion",rulesVersion).put("associationVersions",JSONObject().apply{associationVersions.toSortedMap().forEach{(id,version)->put(id,version)}})
    companion object {fun from(j:JSONObject):MemoryWork {val a=j.getJSONArray("targets");return MemoryWork(j.getString("id"),j.getString("character"),(0 until a.length()).map{SourceDialogue.from(a.getJSONObject(it))},j.getInt("policyVersion"),j.getString("characterStamp"),j.getString("configStamp"),j.optJSONObject("existingVersions")?.let{v->v.keys().asSequence().associateWith{v.getInt(it)}}?:emptyMap(),j.optString("runId","legacy"),j.optInt("rulesVersion",0),j.optJSONObject("associationVersions")?.let{v->v.keys().asSequence().associateWith{v.getInt(it)}}?:emptyMap())}}
}
class ProcessingRejected(val code:String):Exception(code)
internal fun rejectUnless(value:Boolean,code:String="FORMAT"){if(!value)throw ProcessingRejected(code)}
internal fun canonicalJson(value:Any?):String=when(value){
    null,JSONObject.NULL->"null"
    is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonicalJson(value.get(it))}
    is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonicalJson(value.get(it))}
    is String->JSONObject.quote(value)
    else->value.toString()
}

class MemoryBatchRepository(private val repo:ProcessingRepository) {
    data class Review(val batchId:String,val current:StoredMemory,val savedVersion:Int,val acknowledged:Boolean)
    private fun reviewDigest(db:SQLiteDatabase,work:MemoryWork,m:StoredMemory):String {
        val ops=db.rawQuery("SELECT op_key,digest,result_version FROM memory_operations WHERE batch_id=? AND result_id=? ORDER BY op_key",arrayOf(work.id,m.id)).use{c->buildString{while(c.moveToNext())append("${c.getString(0)}:${c.getString(1)}:${c.getInt(2)};")}}
        return stableDigest(m.json().toString()+work.targets.joinToString{it.json().toString()}+ops)
    }
    private fun acknowledged(db:SQLiteDatabase,work:MemoryWork,m:StoredMemory)=db.rawQuery("SELECT 1 FROM memory_batch_reviews WHERE batch_id=? AND memory_id=? AND version=? AND digest=? AND decision='KEEP_CURRENT'",arrayOf(work.id,m.id,m.version.toString(),reviewDigest(db,work,m))).use{it.moveToFirst()}
    suspend fun reviews(friendId:String):List<Review> = repo.read{db->
        val work=pendingWork(db,friendId)?:return@read emptyList()
        db.rawQuery("SELECT result_id,MAX(result_version) FROM memory_operations WHERE batch_id=? GROUP BY result_id",arrayOf(work.id)).use{c->buildList{while(c.moveToNext()){val m=repo.memory(db,friendId,c.getString(0))?:continue;if(m.version!=c.getInt(1))add(Review(work.id,m,c.getInt(1),acknowledged(db,work,m)))}}}
    }
    /** Explicit local decision only. A valid model receipt is still required to finish this batch. */
    suspend fun keepCurrent(friendId:String,batchId:String,memoryId:String,expectedVersion:Int)=repo.tx{db->
        val work=pendingWork(db,friendId)?:throw ProcessingRejected("STALE_TASK")
        rejectUnless(work.id==batchId&&repo.sourcesMatch(db,work.targets),"SOURCE_CHANGED")
        val m=repo.memory(db,friendId,memoryId)?:throw ProcessingRejected("SOURCE_CHANGED")
        rejectUnless(m.version==expectedVersion&&m.valid&&m.evidence.all{repo.sourceValid(db,it.source)},"SOURCE_CHANGED")
        rejectUnless(db.rawQuery("SELECT 1 FROM memory_operations WHERE batch_id=? AND result_id=?",arrayOf(batchId,memoryId)).use{it.moveToFirst()},"STALE_TASK")
        db.insertWithOnConflict("memory_batch_reviews",null,ContentValues().apply{put("batch_id",batchId);put("memory_id",memoryId);put("version",m.version);put("digest",reviewDigest(db,work,m));put("decision","KEEP_CURRENT");put("created",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun pendingWork(db:SQLiteDatabase,friendId:String):MemoryWork?=db.rawQuery("SELECT json FROM memory_batches WHERE friend_id=? AND state NOT IN('COMMITTED','ARCHIVED') ORDER BY rowid",arrayOf(friendId)).use{c->var found:MemoryWork?=null;while(found==null&&c.moveToNext()){val w=MemoryWork.from(JSONObject(c.getString(0)));if(repo.sourcesMatch(db,w.targets))found=w};found}
    suspend fun prepare(friendId:String,targets:List<SourceDialogue>,configStamp:String,existingIds:Set<String>?=null,expectedRulesVersion:Int?=null,associationIds:Set<String> = emptySet()):MemoryWork=repo.tx{db->
        rejectUnless(targets.isNotEmpty()&&targets.all{it.source.characterId==friendId})
        rejectUnless(repo.sourcesMatch(db,targets),"SOURCE_CHANGED")
        val id="batch-"+stableDigest(friendId+targets.joinToString{ "${it.source.turnId}@${it.source.version}" }).take(40)
        val policy=repo.policy(db,friendId)
        val rulesVersion=repo.memoryRules(db).second;rejectUnless(expectedRulesVersion==null||rulesVersion==expectedRulesVersion,"SOURCE_CHANGED")
        val work=MemoryWork(id,friendId,targets,policy.second,repo.characterStamp(db,friendId),configStamp,repo.memories(db,friendId).filter{it.valid&&(existingIds==null||it.id in existingIds)}.associate{it.id to it.version},rulesVersion=rulesVersion,associationVersions=repo.usableMemories(db,friendId).filter{it.id in associationIds}.take(MemoryRecall.RELATION_CANDIDATE_LIMIT).associate{it.id to it.version})
        val old=db.rawQuery("SELECT state FROM memory_batches WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0) else null}
        rejectUnless(old!="RUNNING","BUSY")
        rejectUnless(old!="COMMITTED","ALREADY_COMMITTED")
        db.insertWithOnConflict("memory_batches",null,ContentValues().apply{put("id",id);put("friend_id",friendId);put("json",work.json().toString());put("state","PENDING");put("attempt",0);put("config_stamp",configStamp)},SQLiteDatabase.CONFLICT_IGNORE)
        db.update("memory_batches",ContentValues().apply{put("json",work.json().toString());put("config_stamp",configStamp);put("state","PENDING");putNull("error")},"id=?",arrayOf(id))
        work
    }
    suspend fun beginAttempt(work:MemoryWork,session:String)=repo.tx{db->
        rejectUnless(repo.memoryRules(db).second==work.rulesVersion,"SOURCE_CHANGED")
        val count=db.rawQuery("SELECT attempt FROM memory_batches WHERE id=? AND json=? AND state NOT IN('COMMITTED','ARCHIVED')",arrayOf(work.id,work.json().toString())).use{rejectUnless(it.moveToFirst(),"STALE_TASK");it.getInt(0)+1}
        rejectUnless(count<=3,"MANUAL_RETRY")
        db.update("memory_batches",ContentValues().apply{put("state","RUNNING");put("attempt",count);put("owner_session",session);putNull("error")},"id=?",arrayOf(work.id));count
    }
    private fun validate(db:SQLiteDatabase,work:MemoryWork,configCurrent:()->Boolean){
        rejectUnless(repo.memoryRules(db).second==work.rulesVersion,"SOURCE_CHANGED")
        rejectUnless(repo.policy(db,work.friendId).second==work.policyVersion&&repo.characterStamp(db,work.friendId)==work.characterStamp,"SOURCE_CHANGED")
        rejectUnless(repo.sourcesMatch(db,work.targets)&&configCurrent(),"SOURCE_CHANGED")
        val header=db.rawQuery("SELECT json,state FROM memory_batches WHERE id=? AND friend_id=?",arrayOf(work.id,work.friendId)).use{rejectUnless(it.moveToFirst(),"STALE_TASK");it.getString(0) to it.getString(1)}
        rejectUnless(header.first==work.json().toString()&&header.second=="RUNNING","STALE_TASK")
    }
    suspend fun applied(work:MemoryWork):List<String> = repo.read{db->db.rawQuery("SELECT op_key FROM memory_operations WHERE batch_id=? ORDER BY op_key",arrayOf(work.id)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}}
    suspend fun appliedOperations(work:MemoryWork):List<JSONObject> = repo.read{db->db.rawQuery("SELECT json FROM memory_operations WHERE batch_id=? ORDER BY op_key",arrayOf(work.id)).use{c->buildList{while(c.moveToNext())add(JSONObject(c.getString(0)))}}}
    suspend fun pending(friendId:String):MemoryWork?=repo.read{pendingWork(it,friendId)}
    suspend fun recoverAbandoned(session:String)=repo.tx{db->db.update("memory_batches",ContentValues().apply{put("state","FAILED");put("error","INTERRUPTED")},"state='RUNNING' AND (owner_session IS NULL OR owner_session<>?)",arrayOf(session))}
    private fun evidence(work:MemoryWork,op:JSONObject):List<MemoryEvidence> {
        val a=op.optJSONArray("evidence")?:throw ProcessingRejected("EVIDENCE")
        rejectUnless(a.length() in 1..20,"EVIDENCE")
        return (0 until a.length()).map{i->val j=a.getJSONObject(i)
            val turn=j.getLong("turn");val version=j.getLong("version");val side=j.getString("side");val quote=j.getString("quote")
            val source=work.targets.find{it.source.turnId==turn&&it.source.version==version}?:throw ProcessingRejected("EVIDENCE")
            rejectUnless(side in setOf("user","assistant")&&quote.isNotBlank(),"EVIDENCE")
            val text=if(side=="user")source.user.text else source.assistant.text;rejectUnless(quote in text,"EVIDENCE")
            MemoryEvidence(source.source,side,quote,text,if(side=="user")source.user.created else source.assistant.created,source.zone)
        }.distinct()
    }
    suspend fun apply(work:MemoryWork,op:JSONObject,configCurrent:()->Boolean={true}):String=repo.tx{db->
        Diagnostics.event("memory.operation.received",mapOf("character" to work.friendId,"batch" to work.id,"operation" to op.toString()))
        validate(db,work,configCurrent)
        val key=op.getString("id");rejectUnless(key.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val digest=stableDigest(canonicalJson(op))
        val previous=db.rawQuery("SELECT digest,result_id FROM memory_operations WHERE batch_id=? AND op_key=?",arrayOf(work.id,key)).use{if(it.moveToFirst())it.getString(0) to it.getString(1) else null}
        if(previous!=null){rejectUnless(previous.first==digest,"OPERATION_CHANGED");return@tx previous.second}
        rejectUnless(!op.has("locked")&&!op.has("manual"),"PROTECTED")
        val kind=op.getString("op");rejectUnless(kind in setOf("ADD","UPDATE","CANCEL","COMPLETE","RETRACT"))
        val local=op.optString("target")
        val id=if(kind=="ADD"){
            rejectUnless(local.matches(Regex("new:[A-Za-z0-9_-]{1,64}")))
            "memory-"+stableDigest(work.friendId+work.id+local).take(40)
        }else if(local.startsWith("new:"))"memory-"+stableDigest(work.friendId+work.id+local).take(40) else local
        var old=repo.memory(db,work.friendId,id)
        if(kind!="ADD")rejectUnless(old!=null&&(local.startsWith("new:")||id in work.existingVersions),"UNKNOWN_TARGET")
        if(kind=="ADD")rejectUnless(old==null,"OPERATION_CHANGED")
        val category=op.optString("category").takeIf{it.isNotBlank()}?.let{MemoryCategory.valueOf(it)}?:old?.category?:throw ProcessingRejected("FORMAT")
        rejectUnless(category.modern&&(old==null||old.category.modern),"LEGACY_READ_ONLY")
        val about=if(op.has("about"))MemoryAbout.valueOf(op.getString("about")) else old?.about?:throw ProcessingRejected("FORMAT")
        val tags=if(op.has("tags")){val a=op.getJSONArray("tags");rejectUnless(a.length()<=12);MemoryFacets.tags((0 until a.length()).map{a.getString(it)})}else old?.tags?:emptyList()
        val text=op.optString("text").ifBlank{old?.text.orEmpty()};rejectUnless(text.isNotBlank()&&text.length<=4000)
        val proof=evidence(work,op)
        if(about==MemoryAbout.USER)rejectUnless(proof.any{it.side=="user"},"USER_EVIDENCE")
        val dateType=if(op.has("date_type"))op.getString("date_type").also{rejectUnless(it in setOf("day","habit","birthday"))} else old?.dateType
        val expression=if(op.optString("date_type")=="habit")null else if(!op.has("date_expression"))old?.dateExpression else if(op.isNull("date_expression"))null else op.optString("date_expression").takeIf{it.isNotBlank()}
        if(expression!=null&&op.has("date_expression"))rejectUnless(proof.any{expression in it.quote&&(about!=MemoryAbout.USER||it.side=="user")},"DATE_EVIDENCE")
        val dates=proof.filter{expression!=null&&expression in it.quote}.map{MemoryDates.resolve(expression,it.sourceAt,it.zone)}.toSet()
        val birthday=if(dateType=="birthday")MemoryDates.birthday(expression) else null
        val annual=if(birthday!=null)birthday.second else if(!op.has("date_type")&&!op.has("date_expression"))old?.annualMonthDay else null
        val date=if(birthday!=null)birthday.first else if(op.optString("date_type")=="habit")null else if(!op.has("date_expression"))old?.eventDate else if(dates.size==1)dates.first() else null
        rejectUnless(!op.has("due_date"),"DATE_EVIDENCE")
        val dueExpression=if(!op.has("due_expression"))old?.dueExpression else if(op.isNull("due_expression"))null else op.getString("due_expression").takeIf{it.isNotBlank()}
        if(dueExpression!=null&&op.has("due_expression"))rejectUnless(proof.any{dueExpression in it.quote&&(about!=MemoryAbout.USER||it.side=="user")},"DATE_EVIDENCE")
        val dueDates=proof.filter{dueExpression!=null&&dueExpression in it.quote}.map{MemoryDates.resolve(dueExpression,it.sourceAt,it.zone)}.toSet()
        val dueDate=if(!op.has("due_expression"))old?.dueDate else if(dueDates.size==1)dueDates.first() else null
        val life=when(kind){"CANCEL"->MemoryLife.CANCELLED;"COMPLETE"->MemoryLife.COMPLETED;"RETRACT"->MemoryLife.RETRACTED;else->old?.life?:MemoryLife.ACTIVE}
        if(kind=="ADD")old=repo.memories(db,work.friendId).firstOrNull{it.valid&&it.category==category&&it.about==about&&it.tags==tags&&it.text==text&&it.eventDate==date&&it.dateType==dateType&&it.annualMonthDay==annual&&it.dueDate==dueDate&&it.life==life}
        val actualId=old?.id?:id
        val proposed=StoredMemory(actualId,work.friendId,category,text,life,if(op.has("core"))op.getBoolean("core") else old?.core?:false,false,false,(old?.version?:0)+1,true,expression,date,((old?.evidence?:emptyList())+proof).distinct(),about,tags,dueExpression,dueDate,old?.expiresAt,dateType,annual)
        val lastOwnVersion=db.rawQuery("SELECT MAX(result_version) FROM memory_operations WHERE batch_id=? AND result_id=?",arrayOf(work.id,actualId)).use{if(it.moveToFirst()&&!it.isNull(0))it.getInt(0) else null}
        val expectedVersion=lastOwnVersion?:work.existingVersions[actualId]
        val protected=old!=null&&(old.locked||old.manual||old.version!=expectedVersion)
        val unchanged=old!=null&&old.text==proposed.text&&old.category==proposed.category&&old.about==about&&old.tags==tags&&old.dueDate==dueDate&&old.life==proposed.life&&old.eventDate==proposed.eventDate&&old.dateType==proposed.dateType&&old.annualMonthDay==proposed.annualMonthDay&&old.core==proposed.core
        var savedVersion=old?.version?:1
        val doubt=op.optString("doubt").takeIf{it.isNotBlank()&&it!="null"}
        if(doubt!=null){
            repo.candidate(db,work.friendId,old?.id,proposed,"拿不准：${doubt.take(240)}","candidate-"+stableDigest(work.id+key).take(40));savedVersion=old?.version?:0
        }else if(protected&&!unchanged){
            repo.candidate(db,work.friendId,actualId,proposed,"人工、锁定或并发修改的内容受保护", "candidate-"+stableDigest(work.id+key).take(40))
        }else if(!protected){repo.saveMemory(db,proposed);savedVersion=proposed.version}
        db.insertOrThrow("memory_operations",null,ContentValues().apply{put("batch_id",work.id);put("op_key",key);put("json",canonicalJson(op));put("digest",digest);put("result_id",actualId);put("result_version",savedVersion)})
        actualId
    }
    suspend fun complete(work:MemoryWork,operationKeys:Set<String>,declaredCount:Int,marker:String,configCurrent:()->Boolean={true},lease:ChatAttemptLease?=null)=repo.tx{db->
        val finish={
        validate(db,work,configCurrent)
        rejectUnless(marker==work.id&&declaredCount==operationKeys.size)
        val saved=db.rawQuery("SELECT op_key FROM memory_operations WHERE batch_id=?",arrayOf(work.id)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        rejectUnless(saved==operationKeys,"INCOMPLETE")
        db.rawQuery("SELECT result_id,MAX(result_version) FROM memory_operations WHERE batch_id=? GROUP BY result_id",arrayOf(work.id)).use{c->while(c.moveToNext()){
            val m=repo.memory(db,work.friendId,c.getString(0))
            if(m==null){
                rejectUnless(c.getInt(1)==0&&repo.candidates(db,work.friendId).any{it.valid&&it.proposed.id==c.getString(0)},"SOURCE_CHANGED")
                continue
            }
            rejectUnless(m.valid&&m.evidence.all{repo.sourceValid(db,it.source)},"SOURCE_CHANGED")
            rejectUnless(m.version==c.getInt(1)||acknowledged(db,work,m),"BATCH_REVIEW_REQUIRED")
        }}
        work.targets.forEach{s->db.insertWithOnConflict("memory_retired",null,ContentValues().apply{put("friend_id",work.friendId);put("source_user_id",s.source.turnId);put("source_version",s.source.version);put("batch_id",work.id)},SQLiteDatabase.CONFLICT_IGNORE)}
        val cursor=maxOf(repo.cursor(db,work.friendId),work.targets.last().source.turnId)
        db.insertWithOnConflict("memory_progress",null,ContentValues().apply{put("friend_id",work.friendId);put("cursor",cursor)},SQLiteDatabase.CONFLICT_REPLACE)
        db.update("memory_batches",ContentValues().apply{put("state","COMMITTED");putNull("error");put("next_retry",0)},"id=?",arrayOf(work.id))
        Diagnostics.event("memory.batch.receipt.staged",mapOf("character" to work.friendId,"batch" to work.id,"operation_count" to declaredCount,"cursor" to cursor))
        repo.maintainCore(db,work.friendId)
        // Optional graph errors cannot roll back a valid summary or retire an incomplete one.
        db.execSQL("SAVEPOINT optional_associations")
        try{MemoryAssociations(repo).commit(db,work);db.execSQL("RELEASE optional_associations")}
        catch(_:Exception){db.execSQL("ROLLBACK TO optional_associations");db.execSQL("RELEASE optional_associations")}
        true
        }
        if(lease==null)finish() else lease.guard(finish)
    }
    suspend fun fail(work:MemoryWork,code:String,retryAt:Long=0)=repo.tx{db->db.update("memory_batches",ContentValues().apply{put("state",if(retryAt>0)"WAITING" else "FAILED");put("error",code);put("next_retry",retryAt)},"id=? AND json=? AND state NOT IN('COMMITTED','ARCHIVED')",arrayOf(work.id,work.json().toString()))}
}

