package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Adapts our optional recall tools and FusedMemoryWriter to Lianxu's source/version transactions. */
internal class MemoryTools(private val repo:ProcessingRepository,private val turn:ChatTurn,private val input:String,
    private val initial:ProcessingRepository.References,private val active:()->Boolean) {
    private data class Target(val id:String,val version:Int,val candidate:String?=null)
    private val targets=linkedMapOf<String,Target>()
    private val gson=Gson()
    private val stageKey="memory-tools:${turn.id}:${turn.attempt}"
    private val usageKey="memory-recall-usage:${turn.id}"
    private fun pref(key:String)=repo.store.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun savePref(key:String,value:String){repo.store.writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    private val rulesState=repo.memoryRules(repo.store.readableDatabase)
    private val rules=rulesState.first
    private fun authorized(write:Boolean=false)=active()&&repo.memoryRules(repo.store.readableDatabase)==rulesState&&(if(write)rules.immediateWrite else rules.proactiveRecall)
    private val budget=CompanionRecallBudget({CompanionRecallSettings(rules.recallRounds,rules.recallTokens,rules.recallPerCall)}, {pref(usageKey)}, {savePref(usageKey,it)})
    private val reader=object:AgentRecallReader {
        override suspend fun isAvailable(sessionId:String)=sessionId==turn.friendId&&authorized()
        override suspend fun searchRecall(sessionId:String,terms:List<String>,limit:Int)=repo.read{db->
            repo.usableMemories(db,sessionId).filter{m->terms.any{it in (m.text+" "+m.tags.joinToString(" ")+" "+m.eventDate.orEmpty()).lowercase()}}
                .map{m->AgentRecallCandidate(m.id,"companion_memory",m.category.label,repo.describe(m),m.tags,sessionId,startedAt=m.eventDate,current=true)}
                .sortedWith(compareByDescending<AgentRecallCandidate>{recallScore(it,terms)}.thenBy{it.id}).take(limit)
        }
        override suspend fun searchHistory(sessionId:String,terms:List<String>,limit:Int)=repo.sources(sessionId).flatMap{listOf(it.user,it.assistant)}
            .filter{m->terms.any{it in m.text.lowercase()}}.sortedWith(compareByDescending<Message>{m->terms.sumOf{if(it in m.text.lowercase())it.length.coerceAtMost(8) else 0}}.thenByDescending{it.created})
            .take(limit).map(::history)
        override suspend fun readHistory(sessionId:String,anchorMessageId:String,before:Int,after:Int):List<AgentHistoryMessage>{
            val rows=repo.sources(sessionId).flatMap{listOf(it.user,it.assistant)};val index=rows.indexOfFirst{it.id.toString()==anchorMessageId}
            if(index<0)return emptyList();return rows.subList((index-before).coerceAtLeast(0),(index+after+1).coerceAtMost(rows.size)).map(::history)
        }
    }
    private fun history(m:Message)=AgentHistoryMessage(m.id.toString(),if(m.mine)"user" else "assistant",m.text,Instant.ofEpochMilli(m.created).toString())
    private val recall=AgentRecallToolHandler(turn.friendId,reader,true)
    private fun register(id:String,version:Int,candidate:String?=null):String {
        targets.entries.firstOrNull{it.value==Target(id,version,candidate)}?.let{return it.key}
        return "M${targets.size+1}".also{targets[it]=Target(id,version,candidate)}
    }
    private fun writeDefinition():Map<String,Any> {
        fun field(type:String,description:String)=mapOf("type" to type,"description" to description)
        return mapOf("type" to "function","function" to mapOf("name" to "agent_memory_update","description" to "根据本轮用户S1原话存记忆或澄清待确认记忆；写入随最终回复同一事务完成。保留人工/锁定保护。未知目标直接失败。",
            "parameters" to mapOf("type" to "object","properties" to mapOf(
                "op" to mapOf("type" to "string","enum" to listOf("add","update","cancel","complete","retract")),
                "target" to field("string","已有记忆或待确认的M编号；add不填"),"content" to field("string","第一人称忠于原话的一句话"),
                "category" to mapOf("type" to "string","enum" to MemoryCategory.entries.filter{it.modern}.map{it.name}),
                "about" to mapOf("type" to "string","enum" to MemoryAbout.entries.map{it.name}),
                "evidence" to mapOf("type" to "array","items" to mapOf("type" to "string","enum" to listOf("S1")),"minItems" to 1,"maxItems" to 1),
                "time_text" to field("string","本轮原话中的单日时间词，未知留空"),"date_type" to mapOf("type" to "string","enum" to listOf("day","habit","birthday")),
                "tags" to mapOf("type" to "array","items" to mapOf("type" to "string")),"doubt" to field("string","仍拿不准时填写理由，不能伪装成已确定事实")),"required" to listOf("op","evidence"))))
    }
    suspend fun run(config:ModelConfig,key:String,request:TextChatRequest,client:TextChatClient):TextChatResult {
        if(!rules.proactiveRecall&&!rules.immediateWrite){
            if(!active())return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
            val result=client.complete(config,key,CompanionPrompt.forSend(request,emptyList()))
            return if(result is TextChatResult.Success&&result.toolCalls.isNotEmpty())TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE) else result
        }
        repo.read{db->
            initial.valid.map{it.memoryId}.filter{it.isNotBlank()}.distinct().forEach{id->repo.memory(db,turn.friendId,id)?.let{register(it.id,it.version)}}
            repo.candidates(db,turn.friendId).filter{it.valid&&initial.pending.any{p->it.id in p}}.forEach{c->register(c.targetId?:c.proposed.id,c.targetVersion?:0,c.id)}
        }
        val mapping=targets.entries.joinToString("\n"){(ref,t)->"$ref -> ${t.candidate?:t.id}@${t.version}"}
        val messages=request.messages.toMutableList().apply{add((size-2).coerceAtLeast(0),WireMessage("system","[记忆工具编号]\nS1 仅指本轮用户原话。$mapping\n${CompanionRecallBudget.QUERY_RULES}",memoryIds=initial.valid.map{it.memoryId}.filter{it.isNotBlank()}))}
        val definitions=(if(rules.proactiveRecall)buildAgentRecallToolDefinitions(true) else emptyList())+(if(rules.immediateWrite)listOf(writeDefinition()) else emptyList())+(if(rules.proactiveRecall)listOf(mapOf("type" to "function","function" to mapOf("name" to "agent_memory_read","description" to "按问题读取当前有效角色记忆","parameters" to mapOf("type" to "object","properties" to mapOf("query" to mapOf("type" to "string")),"required" to listOf("query"))))) else emptyList())
        // Read rounds are quota-limited; a separate total step bound also limits write-only loops.
        repeat(8){step->
            currentCoroutineContext().ensureActive();if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
            val actor="${turn.id}:${turn.attempt}:$step"
            val preparedMessages=CompanionPrompt.forSend(request.copy(messages=messages.toList()),definitions).messages
            val maps=preparedMessages.map{m->buildMap<String,Any>{put("role",m.role);put("content",m.content);if(m.name.isNotEmpty())put("name",m.name);if(m.toolCalls.isNotEmpty())put("tool_calls",m.toolCalls.map{mapOf("id" to it.id,"type" to "function","function" to mapOf("name" to it.name,"arguments" to it.arguments))});if(m.sourceMessageId.isNotEmpty())put(CompanionRecallBudget.SOURCE_META,mapOf("id" to m.sourceMessageId,"revision" to stableDigest(m.content)));if(m.memoryIds.isNotEmpty())put("_companion_memory_ids",m.memoryIds)}}
            val prepared=budget.prepare(actor,maps,definitions,config.contextTokens,request.outputLimit)
            val offered=if(step==7)emptyList() else prepared.tools
            val outgoing=CompanionPrompt.forSend(request.copy(messages=messages.toList()),offered)
            val outgoingMaps=maps.mapIndexed{i,m->m+("content" to outgoing.messages[i].content)}
            if(estimateLocalMessagesTokens(outgoingMaps)+ConservativeTokens.tokens(gson.toJson(offered))+request.outputLimit>config.contextTokens)return TextChatResult.Failure(ChatFailure.CONTEXT_CAPACITY)
            val result=client.complete(config,key,outgoing)
            if(step==0&&offered.isNotEmpty()&&result is TextChatResult.Failure&&result.reason==ChatFailure.UNSUPPORTED_TOOLS){
                if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
                val fallback=client.complete(config,key,CompanionPrompt.forSend(request,emptyList()))
                return if(fallback is TextChatResult.Success&&fallback.toolCalls.isNotEmpty())TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE) else fallback
            }
            if(result !is TextChatResult.Success||result.toolCalls.isEmpty())return result
            if(result.toolCalls.size>8||result.toolCalls.any{c->c.name !in offered.map(::toolNameOf)})return TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE)
            messages+=WireMessage("assistant",result.text,result.toolCalls)
            for(call in result.toolCalls){
                Diagnostics.event("tool.call",mapOf("character" to turn.friendId,"turn" to turn.id,"step" to step,"call_id" to call.id,"name" to call.name,"arguments" to call.arguments))
                if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
                val output=try {
                    val args=JSONObject(call.arguments)
                    if(call.name=="agent_memory_update")stage(call,args)
                    else {rejectUnless(authorized(),"TOOLS_DISABLED");budget.read(actor){
                        val converted=gson.fromJson(call.arguments,Map::class.java).entries.filter{it.value!=null}.associate{it.key.toString() to it.value!!}
                        val name=if(call.name=="agent_memory_read")"agent_recall_search" else call.name
                        val raw=recall.execute(name,converted)
                        @Suppress("UNCHECKED_CAST")
                        val rows=(raw["results"] as? List<Map<String,Any>>)?.map{row->
                            val id=row["id"] as? String
                            val m=id?.let{repo.read{db->repo.memory(db,turn.friendId,it)}}
                            if(m!=null)row+("memory_ref" to register(m.id,m.version)) else row
                        }
                        if(rows==null)raw else raw+("results" to rows)
                    }}
                }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){mapOf("success" to false,"error" to ((e as? ProcessingRejected)?.code?:"invalid_arguments"))}
                messages+=WireMessage("tool",gson.toJson(output),toolCallId=call.id,name=call.name)
                Diagnostics.event("tool.result",mapOf("character" to turn.friendId,"turn" to turn.id,"call_id" to call.id,"name" to call.name,"output" to output))
            }
        }
        return TextChatResult.Failure(ChatFailure.FORMAT)
    }
    private suspend fun stage(call:ChatToolCall,args:JSONObject):Map<String,Any> = repo.tx{db->
        rejectUnless(authorized(true)&&pending(db),"SOURCE_CHANGED")
        val e=args.optJSONArray("evidence");rejectUnless(e?.length()==1&&e.getString(0)=="S1","EVIDENCE")
        val op=args.getString("op");rejectUnless(op in setOf("add","update","cancel","complete","retract"))
        val target=if(op=="add"){rejectUnless(args.optString("target").isEmpty());null}else targets[args.getString("target")]?:throw ProcessingRejected("UNKNOWN_TARGET")
        val old=target?.let{repo.memory(db,turn.friendId,it.id)}
        if(target!=null&&target.candidate==null)rejectUnless(old!=null&&old.version==target.version&&old.valid,"SOURCE_CHANGED")
        val proposed=target?.candidate?.let{id->repo.candidates(db,turn.friendId).firstOrNull{it.id==id&&it.valid}?:throw ProcessingRejected("SOURCE_CHANGED")}
        val base=old?:proposed?.proposed
        val content=args.optString("content").ifBlank{base?.text.orEmpty()};rejectUnless(content.isNotBlank()&&content.length<=4000)
        val category=args.optString("category").ifBlank{base?.category?.name.orEmpty()};rejectUnless(MemoryCategory.valueOf(category).modern)
        val about=args.optString("about").ifBlank{base?.about?.name.orEmpty()};MemoryAbout.valueOf(about)
        val date=args.optString("time_text");rejectUnless(date.isEmpty()||date in input,"DATE_EVIDENCE")
        rejectUnless(args.optString("date_type","day") in setOf("day","habit","birthday"))
        val sourceVersion=db.rawQuery("SELECT source_revision FROM chat_turns WHERE id=?",arrayOf(turn.id)).use{rejectUnless(it.moveToFirst(),"SOURCE_CHANGED");it.getLong(0)}
        val data=JSONObject(args.toString()).put("source_version",sourceVersion).put("content_supplied",args.optString("content").isNotBlank()).put("category_supplied",args.optString("category").isNotBlank()).put("about_supplied",args.optString("about").isNotBlank()).put("content",content).put("category",category).put("about",about).put("target_id",target?.id?:JSONObject.NULL).put("target_version",target?.version?:0).put("candidate",target?.candidate?:JSONObject.NULL)
        val digest=stableDigest(canonicalJson(data));data.put("digest",digest)
        val stages=JSONArray(pref(stageKey)?:"[]");rejectUnless(stages.length()<16,"TOOL_LIMIT")
        if((0 until stages.length()).none{stages.getJSONObject(it).getString("digest")==digest}){stages.put(data);savePref(stageKey,stages.toString())}
        mapOf("success" to true,"status" to "staged_until_reply_commit","operation" to digest,"note" to "最终回复成功保存时一并写入；失败或取消不会改动原记忆")
    }
    private fun pending(db:SQLiteDatabase)=db.rawQuery("SELECT 1 FROM chat_turns t JOIN messages m ON m.id=t.user_message_id WHERE t.id=? AND t.friend_id=? AND t.attempt=? AND t.state='PENDING' AND m.body=?",arrayOf(turn.id,turn.friendId,turn.attempt.toString(),input)).use{it.moveToFirst()}
    /** Invoked inside ChatLedger's final reply transaction, after its ownership/context/config guards. */
    fun commit(db:SQLiteDatabase){
        Diagnostics.event("tool.commit.staged",mapOf("character" to turn.friendId,"turn" to turn.id,"attempt" to turn.attempt))
        val raw=pref(stageKey)?:return;rejectUnless(repo.memoryRules(db)==rulesState&&rules.immediateWrite,"SOURCE_CHANGED");val stages=JSONArray(raw)
        val source=repo.sources(db,turn.friendId).firstOrNull{it.requestId==turn.id}?:throw ProcessingRejected("SOURCE_CHANGED")
        rejectUnless(source.user.text==input,"SOURCE_CHANGED")
        val written=mutableMapOf<String,Int>()
        val resolved=mutableMapOf<String,ProcessingRepository.Candidate>()
        for(i in 0 until stages.length()){
            val op=stages.getJSONObject(i);rejectUnless(source.source.version==op.getLong("source_version"),"SOURCE_CHANGED");val target=if(op.isNull("target_id"))null else op.getString("target_id");var old=target?.let{repo.memory(db,turn.friendId,it)}
            val candidate=if(op.isNull("candidate"))null else (resolved[op.getString("candidate")]?:repo.candidates(db,turn.friendId).firstOrNull{it.id==op.getString("candidate")&&it.valid})?:throw ProcessingRejected("SOURCE_CHANGED")
            if(target!=null&&old!=null)rejectUnless(old.version==(written[target]?:op.getInt("target_version")),"SOURCE_CHANGED")
            if(target!=null&&old==null)rejectUnless(candidate!=null&&candidate.targetId==null,"SOURCE_CHANGED")
            val kind=op.getString("op");val expression=if(!op.has("time_text")&&!op.has("date_type"))old?.dateExpression else op.optString("time_text").takeIf{it.isNotBlank()&&op.optString("date_type")!="habit"}
            val dateType=if(op.has("date_type"))op.getString("date_type") else old?.dateType
            val birthday=if(dateType=="birthday")MemoryDates.birthday(expression) else null
            val eventDate=if(birthday!=null)birthday.first else if(!op.has("time_text")&&!op.has("date_type"))old?.eventDate else MemoryDates.resolve(expression,source.user.created,source.zone)
            val content=if(!op.optBoolean("content_supplied")&&old!=null)old.text else op.getString("content")
            val category=if(!op.optBoolean("category_supplied")&&old!=null)old.category else MemoryCategory.valueOf(op.getString("category"))
            val about=if(!op.optBoolean("about_supplied")&&old!=null)old.about else MemoryAbout.valueOf(op.getString("about"))
            val id=old?.id?:candidate?.proposed?.id?:"tool-"+stableDigest(turn.id+op.getString("digest")).take(40)
            val evidence=MemoryEvidence(source.source,"user",input,input,source.user.created,source.zone)
            val tags=op.optJSONArray("tags")?.let{a->MemoryFacets.tags((0 until a.length()).map{a.getString(it)})}?:old?.tags?:emptyList()
            var value=StoredMemory(id,turn.friendId,category,content,when(kind){"cancel"->MemoryLife.CANCELLED;"complete"->MemoryLife.COMPLETED;"retract"->MemoryLife.RETRACTED;else->old?.life?:MemoryLife.ACTIVE},old?.core?:false,old?.locked?:false,false,(old?.version?:0)+1,true,expression,eventDate,MemoryEvidenceRules.merge(old?.evidence.orEmpty(),listOf(evidence)),about,tags,old?.dueExpression,old?.dueDate,old?.expiresAt,dateType,if(birthday!=null)birthday.second else if(!op.has("date_type")&&!op.has("time_text"))old?.annualMonthDay else null)
            if(kind=="add"&&old==null&&candidate==null){
                old=repo.memories(db,turn.friendId).firstOrNull{it.valid&&MemoryEvidenceRules.sameFact(it,value)&&MemoryEvidenceRules.overlaps(it.evidence,listOf(evidence))}
                old?.let{value=value.copy(id=it.id,version=it.version+1,evidence=MemoryEvidenceRules.merge(it.evidence,listOf(evidence)))}
            }
            if(candidate==null&&op.optString("doubt").isBlank()&&old!=null&&MemoryEvidenceRules.repeated(old,value,listOf(evidence))){written[old.id]=old.version;continue}
            if(old?.locked==true||old?.manual==true||op.optString("doubt").isNotBlank()){
                repo.candidate(db,turn.friendId,target,value,"本轮澄清建议，人工或锁定内容保持保护；${op.optString("doubt")}","tool-candidate-"+stableDigest(turn.id+op.getString("digest")).take(40))
            }else{
                repo.saveMemory(db,value);written[value.id]=value.version
                if(candidate!=null){db.update("memory_candidates",ContentValues().apply{put("resolved",1)},"id=? AND friend_id=?",arrayOf(candidate.id,turn.friendId));resolved[candidate.id]=candidate}
            }
        }
        db.delete("preferences","key=?",arrayOf(stageKey))
    }
}
