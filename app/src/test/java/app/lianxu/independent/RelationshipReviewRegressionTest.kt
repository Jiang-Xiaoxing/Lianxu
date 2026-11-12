package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Review-only requirements probes. No transport, DB, app or user-data writes. */
class RelationshipReviewRegressionTest {
    private val p=RelationTemplate.DIRECT.parameters
    private fun row(id:Long)=RelationInput(SourceRef("r",id,1),0,0,"关于关系",formatVersion=2,explanationPolicy=1)
    @Suppress("UNCHECKED_CAST") private fun recordGuide(source:Long)=
        ((CompanionRecord.definition(source,true)["function"] as Map<String,Any>)["description"] as String)
    private fun replay(rows:List<RelationInput>)=RelationCursor(RelationStart.FRIENDS,p).also{c->rows.forEach(c::apply)}
    private fun context(current:SourceRef,rows:List<RelationInput>,quotes:Map<Long,String>):RoleContext {
        val state=replay(rows).state()
        return RoleContext("r",current,(rows.map{it.source}+current).associateBy{it.turnId},emptyList(),emptyList(),emptyList(),emptyList(),
            relationInputs=rows,relationRecent=RelationshipRecall.recent(rows,state,0){quotes.getValue(it.turnId)},relationState=state)
    }
    private suspend fun submit(input:String,ctx:RoleContext,relation:String):RolePlanner.Reply {
        val raw="""{"appraisal":{"event":"已完成对旧事的相关弥补","meaning":"本次实际弥补已完成","certainty":"reported","relevance":"旧事得到补救","attribution":"user_report","situation":"chat","eventRelation":"new_event","uncertainty":[],"evidence":[{"source":${ctx.current.turnId},"layer":"fact"}]},"关系输入":$relation}"""
        val client=object:TextChatClient { override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("我知道你已经补做了。") }
        val cfg=ModelConfig("synthetic","合成","custom","https://example.invalid","synthetic",64000,2048)
        val planner=RolePlanner(client)
        val facts=ExperienceProposal(claimKind="reported_event")
        val base=CompanionPrompt.build(Friend("r","合成角色","合成人设"),RolePrompts(),emptyList(),input,capacity=64000,outputLimit=2048,adaptive=true) as PromptBuild.Ready
        val prepared=planner.prepareReply(base,cfg,input,facts,ctx,0,false,emptyMap(),0) as PromptBuild.Ready
        val wire=prepared.request.messages.joinToString("\n"){it.content}
        assertTrue("Real prepared notes must say the knot can be made up for",wire.contains("可以弥补心结"))
        // The field guide now travels with the record tool, not the reply prompt.
        assertTrue("Real record guide must allow completed repair without prior promise",recordGuide(ctx.current.turnId).contains("无需预先作出承诺"))
        return planner.respond(cfg,"synthetic",input,facts,ctx,0,prepared.request,{true},{0},false){request,record->
            record?.invoke(raw)
            client.complete(cfg,"synthetic",request)
        }
    }
    @Test fun completedRelevantRemedyWithoutPriorPromiseShouldRepair()=runBlocking {
        val hurt=row(1).copy(b=.9)
        val ctx=context(SourceRef("r",2,1),listOf(hurt),mapOf(1L to "昨天你未经我同意公开了我的私密照片"))
        val result=submit("我已把昨天未经你同意公开的私密照片删掉了，并让转发的人也删除了。",ctx,
            """{"消息":"2","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无","修复心结":"1","弥补已完成":true}""")
        val cursor=replay(listOf(hurt));val before=cursor.state().tension
        cursor.apply(result.relationInput!!)
        println("NO_PRIOR_PROMISE before=$before after=${cursor.state().tension} repairs=${result.relationInput.repairs} issues=${result.relationIssues}")
        assertTrue("Completed relevant remediation must be eligible without a prior promise",cursor.state().tension<before)
    }
    @Test fun completedRemedyWithExplanationShouldKeepItsFulfillment()=runBlocking {
        val hurt=row(1).copy(v=.9)
        val promise=row(2).copy(promise=true)
        val ctx=context(SourceRef("r",3,1),listOf(hurt,promise),mapOf(1L to "昨天没有赴电影约定",2L to "今天一起补看电影"))
        val result=submit("昨天没有赴约是因为工作临时有事。今天我们已经按补偿约定把电影补看完了。",ctx,
            """{"消息":"3","信息类别":"关于关系","P":"无","C":"中","V":"无","B":"无","兑现约定":"2","修复心结":"1","弥补已完成":true,"解释":{"事件":"1","类别":"工作忙","判断":"有原因"}}""")
        val cursor=replay(listOf(hurt,promise));cursor.apply(result.relationInput!!)
        println("EXPLANATION_WITH_REMEDY stored=${Gson().toJson(result.relationInput)} fulfilledBy=${cursor.state().promises.single().fulfilledBy}")
        assertEquals("The completed promise must remain fulfilled when a valid explanation is also present",ctx.current,cursor.state().promises.single().fulfilledBy)
    }
    @Test fun changingRepairTargetVersionShouldNotDeleteStillValidActionOnBackupRestore(){
        val hurt=row(1).copy(b=.9)
        val promise=row(2).copy(promise=true)
        val action=row(3).copy(c=.6,fulfills=promise.source,repairs=hurt.source,repairCompleted=true)
        val root=JsonParser.parseString("""{"roles":[{"character":{"id":"r"},"tables":{"messages":[{"id":1,"body":"旧事"},{"id":2,"body":"补偿约定"},{"id":3,"body":"已完成"}],"chat_turns":[{"user_message_id":1,"source_revision":2},{"user_message_id":2,"source_revision":1},{"user_message_id":3,"source_revision":1}],"message_state":[],"companion_sources":[]},"preferences":{}}]}""").asJsonObject
        val rows=root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject["companion_sources"].asJsonArray
        for(input in listOf(hurt,promise,action))rows.add(JsonObject().apply{
            addProperty("source_id",input.source.turnId);addProperty("revision",input.source.version)
            addProperty("proposal",Gson().toJson(ExperienceProposal(relationInput=input)))
        })
        // This matches live relationshipInputs: only the old source revision is no longer current.
        val live=replay(listOf(promise,action)).state()
        val report=RelationshipBackup.sanitize(root)
        val restoredInputs=rows.mapNotNull{Gson().fromJson(it.asJsonObject["proposal"].asString,ExperienceProposal::class.java).relationInput}
        val restored=replay(restoredInputs).state()
        println("BACKUP_AFTER_TARGET_REVISION liveTrust=${live.trust} restoredTrust=${restored.trust} skipped=${report.skipped} keptSources=${restoredInputs.map{it.source.turnId}}")
        assertEquals("A stale optional repair reference must not erase the valid completed action",live.trust,restored.trust,1e-12)
    }
}
