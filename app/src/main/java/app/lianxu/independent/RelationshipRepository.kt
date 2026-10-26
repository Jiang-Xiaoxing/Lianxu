package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

data class ObservedFeeling(val mood:String?,val intensity:String?,val energy:String?,val observedAt:Long,val source:SourceRef) {
    fun json()=JSONObject().put("mood",mood?:JSONObject.NULL).put("intensity",intensity?:JSONObject.NULL).put("energy",energy?:JSONObject.NULL).put("observedAt",observedAt).put("character",source.characterId).put("turn",source.turnId).put("sourceVersion",source.version)
    companion object{fun from(j:JSONObject)=ObservedFeeling(if(j.isNull("mood"))null else j.getString("mood"),if(j.isNull("intensity"))null else j.getString("intensity"),if(j.isNull("energy"))null else j.getString("energy"),j.getLong("observedAt"),SourceRef(j.getString("character"),j.getLong("turn"),j.getLong("sourceVersion")))}
}
data class RelationshipState(val values:Map<RelationshipDimension,RelationshipValue> = emptyMap(),val feeling:ObservedFeeling?=null,val version:Int=0,val cursor:Long=0) {
    fun json()=JSONObject().put("values",JSONObject().apply{values.forEach{(dim,v)->put(dim.name,JSONObject().put("score",v.score).put("statement",v.statement))}}).put("feeling",feeling?.json()?:JSONObject.NULL).put("version",version).put("cursor",cursor)
    companion object{fun from(j:JSONObject):RelationshipState{val a=j.optJSONObject("values")?:JSONObject();return RelationshipState(RelationshipDimension.entries.mapNotNull{d->a.optJSONObject(d.name)?.let{d to RelationshipValue(it.getInt("score"),it.getString("statement"))}}.toMap(),j.optJSONObject("feeling")?.let{ObservedFeeling.from(it)},j.optInt("version"),j.optLong("cursor"))}}
}
data class RelationshipWork(val id:String,val friendId:String,val targets:List<SourceDialogue>,val policyVersion:Int,val characterStamp:String,val configStamp:String,val previous:RelationshipState){
    fun json()=JSONObject().put("id",id).put("character",friendId).put("targets",JSONArray().apply{targets.forEach{put(it.json())}}).put("policyVersion",policyVersion).put("characterStamp",characterStamp).put("configStamp",configStamp).put("previous",previous.json())
    companion object{fun from(j:JSONObject):RelationshipWork{val a=j.getJSONArray("targets");return RelationshipWork(j.getString("id"),j.getString("character"),(0 until a.length()).map{SourceDialogue.from(a.getJSONObject(it))},j.getInt("policyVersion"),j.getString("characterStamp"),j.getString("configStamp"),RelationshipState.from(j.getJSONObject("previous")))}}
}
class RelationshipRepository(private val repo:ProcessingRepository){
    internal fun state(db:SQLiteDatabase,friendId:String)=db.rawQuery("SELECT json FROM relationship_state WHERE friend_id=?",arrayOf(friendId)).use{if(it.moveToFirst())RelationshipState.from(JSONObject(it.getString(0))) else RelationshipState()}
    suspend fun state(friendId:String)=repo.read{state(it,friendId)}
    suspend fun setEnabled(friendId:String,enabled:Boolean,choice:String?,modelAvailable:Boolean)=repo.tx{db->
        val old=repo.policy(db,friendId).first
        if(!enabled){repo.savePolicy(db,friendId,old.copy(relationshipEnabled=false));return@tx}
        rejectUnless(modelAvailable,"NO_RELATIONSHIP_MODEL")
        val saved=state(db,friendId);val pending=repo.sources(db,friendId,saved.cursor)
        rejectUnless(pending.isEmpty()||choice in setOf("BACKFILL","FROM_NOW"),"HISTORY_CONFIRMATION")
        val selected=choice?:"FROM_NOW"
        if(selected=="FROM_NOW"&&pending.isNotEmpty()){
            val updated=saved.copy(version=saved.version+1,cursor=pending.last().source.turnId)
            save(db,friendId,updated,"history-choice")
        }
        repo.savePolicy(db,friendId,old.copy(relationshipEnabled=true,historyChoice=selected))
    }
    suspend fun setAutomatic(friendId:String,value:Boolean,modelAvailable:Boolean,choice:String?=null)=repo.tx{db->
        val p=repo.policy(db,friendId).first
        rejectUnless(!value||(p.relationshipEnabled&&p.historyChoice!="UNCONFIRMED"&&modelAvailable),"HISTORY_CONFIRMATION")
        val saved=state(db,friendId);val pending=repo.sources(db,friendId,saved.cursor)
        rejectUnless(!value||pending.isEmpty()||choice in setOf("BACKFILL","FROM_NOW"),"HISTORY_CONFIRMATION")
        if(value&&choice=="FROM_NOW"&&pending.isNotEmpty())save(db,friendId,saved.copy(version=saved.version+1,cursor=pending.last().source.turnId),"auto-history-choice")
        repo.savePolicy(db,friendId,p.copy(relationshipAutomatic=value,historyChoice=choice?:p.historyChoice))
    }
    internal fun save(db:SQLiteDatabase,friendId:String,next:RelationshipState,task:String){
        val json=next.json().put("task",task).toString()
        db.insertOrThrow("relationship_history",null,ContentValues().apply{put("friend_id",friendId);put("version",next.version);put("json",json);put("boundary",next.cursor);put("created",System.currentTimeMillis())})
        db.insertWithOnConflict("relationship_state",null,ContentValues().apply{put("friend_id",friendId);put("json",json);put("version",next.version);put("cursor",next.cursor)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    suspend fun begin(friendId:String,id:String,configStamp:String,session:String,manual:Boolean):RelationshipWork?=repo.tx{db->
        val (p,revision)=repo.policy(db,friendId)
        rejectUnless(p.relationshipEnabled&&p.historyChoice!="UNCONFIRMED","DISABLED")
        val running=db.rawQuery("SELECT state,error FROM relationship_jobs WHERE friend_id=?",arrayOf(friendId)).use{if(it.moveToFirst())it.getString(0) to if(it.isNull(1))"" else it.getString(1) else null}
        rejectUnless(running?.first!="RUNNING","BUSY")
        if(!manual&&running?.first=="FAILED"&&running.second !in setOf("INTERRUPTED","MODEL_TIMEOUT"))return@tx null
        val old=state(db,friendId);val targets=repo.sources(db,friendId,old.cursor)
        if(targets.isEmpty())return@tx null
        if(!manual&&!RelationshipPolicy.shouldSchedule(p.relationshipEnabled,p.relationshipAutomatic,true,targets.size,p.relationshipInterval))return@tx null
        val work=RelationshipWork(id,friendId,targets,revision,repo.characterStamp(db,friendId),configStamp,old)
        db.insertWithOnConflict("relationship_jobs",null,ContentValues().apply{put("friend_id",friendId);put("id",id);put("json",work.json().toString());put("state","RUNNING");put("owner_session",session);put("created",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_REPLACE)
        work
    }
    suspend fun resume(friendId:String,id:String,configStamp:String,session:String):RelationshipWork?=repo.tx{db->
        val old=db.rawQuery("SELECT json,state,error FROM relationship_jobs WHERE friend_id=?",arrayOf(friendId)).use{c->if(c.moveToFirst()&&c.getString(1)=="FAILED"&&c.getString(2)=="INTERRUPTED")RelationshipWork.from(JSONObject(c.getString(0))) else null}?:return@tx null
        val (p,version)=repo.policy(db,friendId)
        if(!p.relationshipEnabled||!p.relationshipAutomatic||p.historyChoice=="UNCONFIRMED")return@tx null
        rejectUnless(version==old.policyVersion&&configStamp==old.configStamp&&repo.characterStamp(db,friendId)==old.characterStamp&&repo.sourcesMatch(db,old.targets)&&state(db,friendId).version==old.previous.version,"SOURCE_CHANGED")
        val work=old.copy(id=id)
        db.update("relationship_jobs",ContentValues().apply{put("id",id);put("json",work.json().toString());put("state","RUNNING");putNull("error");put("owner_session",session);put("created",System.currentTimeMillis())},"friend_id=?",arrayOf(friendId));work
    }
    suspend fun complete(work:RelationshipWork,response:JSONObject,configCurrent:()->Boolean,lease:ChatAttemptLease):Boolean=repo.tx{db->lease.commit{
        val currentJob=db.rawQuery("SELECT id,state FROM relationship_jobs WHERE friend_id=?",arrayOf(work.friendId)).use{it.moveToFirst()&&it.getString(0)==work.id&&it.getString(1)=="RUNNING"}
        rejectUnless(currentJob,"STALE_TASK")
        rejectUnless(repo.policy(db,work.friendId).second==work.policyVersion&&repo.policy(db,work.friendId).first.relationshipEnabled&&repo.characterStamp(db,work.friendId)==work.characterStamp,"SOURCE_CHANGED")
        rejectUnless(configCurrent()&&repo.sourcesMatch(db,work.targets)&&state(db,work.friendId).version==work.previous.version,"SOURCE_CHANGED")
        val next=propose(db,work,response)
        save(db,work.friendId,next,work.id)
        db.update("relationship_jobs",ContentValues().apply{put("state","COMPLETED");putNull("error")},"friend_id=? AND id=?",arrayOf(work.friendId,work.id));true
    }}
    internal fun propose(db:SQLiteDatabase,work:RelationshipWork,response:JSONObject):RelationshipState {
        rejectUnless(response.opt("complete")==true&&response.getString("task_id")==work.id&&response.getString("character_id")==work.friendId)
        val obj=response.getJSONObject("dimensions");rejectUnless(obj.keys().asSequence().all{k->RelationshipDimension.entries.any{it.name==k}})
        val proposal=RelationshipDimension.entries.associateWith{d->
            val j=obj.optJSONObject(d.name)
            if(j==null||j.isNull("score"))null else {
                val score=j.get("score");rejectUnless(score is Int||score is Long)
                val statement=j.get("statement");rejectUnless(statement is String&&statement.isNotBlank()&&statement.length<=500)
                ProposedRelationshipValue((score as Number).toLong().coerceIn(0,100).toInt(),statement as String)
            }
        }
        val rawPrompts=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(work.friendId))).use{if(it.moveToFirst())it.getString(0) else ""}
        val prompts=RolePromptStore.decode(rawPrompts)
        val values=RelationshipPolicy.update(work.previous.values,proposal,prompts.initialScores)
        var feeling=work.previous.feeling
        response.optJSONObject("feeling")?.let{j->
            fun optional(key:String):String? {if(j.isNull(key))return null;val v=j.get(key);rejectUnless(v is String&&v.length<=120);return (v as String).takeIf{it.isNotBlank()}}
            val mood=optional("mood");val intensity=optional("intensity");val energy=optional("energy")
            if(mood!=null||intensity!=null||energy!=null){val last=work.targets.last();feeling=ObservedFeeling(mood,intensity,energy,last.assistant.created,last.source)}
        }
        return RelationshipState(values,feeling,work.previous.version+1,work.targets.last().source.turnId)
    }
    suspend fun fail(work:RelationshipWork,code:String)=repo.tx{db->db.update("relationship_jobs",ContentValues().apply{put("state","FAILED");put("error",code)},"friend_id=? AND id=? AND state='RUNNING'",arrayOf(work.friendId,work.id))}
    suspend fun recoverAbandoned(session:String)=repo.tx{db->db.update("relationship_jobs",ContentValues().apply{put("state","FAILED");put("error","INTERRUPTED")},"state='RUNNING' AND (owner_session IS NULL OR owner_session<>?)",arrayOf(session))}
    suspend fun reference(friendId:String):PromptReference?=repo.read{db->reference(db,friendId)}
    internal fun reference(db:SQLiteDatabase,friendId:String):PromptReference? {
        if(!repo.policy(db,friendId).first.relationshipEnabled)return null
        val s=state(db,friendId);if(s.values.isEmpty())return null
        val text=buildString{
            append("已保存关系参考（不覆盖人设、用户要求或工具权限；依赖与嫉妒并非越高越好，不默认恋爱关系）：\n")
            s.values.forEach{(d,v)->append("${d.label} ${v.score}/100：${v.statement}\n")}
            s.feeling?.let{f->if(repo.sourceValid(db,f.source))append("历史观察，非此刻判断：${java.time.Instant.ofEpochMilli(f.observedAt)}，心情=${f.mood?:"未观察"}，情绪强度=${f.intensity?:"未观察"}，精力=${f.energy?:"未观察"}。") else append("上次感受来源已变化，未注入该临时观察。")}
        }
        return PromptReference("已保存六维关系 v${s.version}",text,true)
    }
    suspend fun history(friendId:String)=repo.read{db->db.rawQuery("SELECT json FROM relationship_history WHERE friend_id=? ORDER BY version DESC",arrayOf(friendId)).use{c->buildList{while(c.moveToNext())add(RelationshipState.from(JSONObject(c.getString(0))))}}}
}
