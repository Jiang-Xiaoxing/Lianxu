package app.lianxu.independent

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in only. Uses the user's normal selected vault through production APIs.
 * Never reads secret(), exports vault records, changes model settings, or logs a Key.
 * An explicit saved config ID may temporarily select text purposes; finally restores them.
 * Synthetic conversation/settings use a separate database; no private chat is read. */
class RealCompanionValidationTest {
    @Test fun selectedServicesAndFiniteProductionScenarios():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("real service opt-in required",args.getString("realModel")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val application=context.applicationContext as Application
        val mode=args.getString("mode")?:"metadata"
        val rootStore=Store(context,"lianxu.db")
        val vault=ModelVault(context,"model-configs",rootStore)
        lateinit var meta:ModelConfigState
        InstrumentationRegistry.getInstrumentation().runOnMainSync{meta=ModelConfigState(application)}
        withTimeout(10000){while(meta.configs.isEmpty())delay(50)}
        val selected=ModelPurpose.entries.associateWith{vault.purposeId(it)}
        val configs=meta.configs
        val metadata=configs.map{c->mapOf("id" to c.id,"name" to c.name,"provider" to c.providerId,"url" to c.baseUrl,"model" to c.modelId,"key_exists" to vault.hasKey(c.id),"context" to c.contextTokens,"output" to c.outputTokens,"purposes" to selected.filterValues{it==c.id}.keys.map{it.key})}
        val report=linkedMapOf<String,Any>("mode" to mode,"metadata" to metadata,"started_at" to System.currentTimeMillis())
        val folder=File(context.getExternalFilesDir(null),"real-model-evidence").apply{mkdirs()}
        val output=File(folder,"$mode-${System.currentTimeMillis()}.json")
        fun save(){output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report));println("REAL_EVIDENCE ${output.name}")}
        save()
        if(mode=="metadata"){println(GsonBuilder().create().toJson(metadata));rootStore.close();return@runBlocking}
        Diagnostics.configure(File(context.filesDir,"diagnostics/real-validation"),context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty())
        val comparisonId=args.getString("configId")
        val chat=configs.single{it.id==(comparisonId?:selected[ModelPurpose.CHAT])}
        val expected=args.getString("chatProvider")?:"mimo"
        val authorized=when(expected){
            "mimo"->chat.providerId=="mimo"&&chat.modelId=="mimo-v2.6-flash"&&chat.baseUrl.trimEnd('/')=="https://api.xiaomimimo.com/v1"
            "deepseek"->chat.providerId=="deepseek"&&chat.baseUrl.trimEnd('/') in setOf("https://api.deepseek.com","https://api.deepseek.com/v1")&&chat.modelId.isNotBlank()&&chat.modelId!="deepseek-chat"
            else->false
        }
        if(!authorized){report["status"]="blocked";report["failure"]="Selected provider/model/official endpoint differs from authorized comparison";save();rootStore.close();error("Authorized service preflight failed")}
        report["chat_provider_under_test"]=expected
        suspend fun select(purpose:ModelPurpose,id:String){
            withContext(Dispatchers.Main){meta.selectPurpose(purpose,id)}
            withTimeout(10000){while(meta.saving)delay(20)}
            check(meta.purposes[purpose]==id){"Text purpose selection was not saved"}
        }
        try{
        if(comparisonId!=null){
            report["original_purposes"]=selected.mapKeys{it.key.key};save()
            select(ModelPurpose.CHAT,chat.id);select(ModelPurpose.MEMORY,chat.id)
            check(meta.purposes[ModelPurpose.EMBEDDING]==selected[ModelPurpose.EMBEDDING])
            report["temporary_text_config"]=chat.id;save()
        }
        if(mode=="connection"){
            try{
                val access=ModelAccess(vault);val credential=access.credential(ModelPurpose.CHAT)?:error("Selected model credential is unavailable")
                check(access.current(ModelPurpose.CHAT,credential.stamp))
                val directory=HttpsModelClient().request(credential.config,credential.key,true)
                report["model_directory"]=when(directory){is ConfigResult.Success->mapOf("status" to "success","model_ids" to directory.modelIds);is ConfigResult.Failure->mapOf("status" to "failed","error" to directory.error.name,"http_status" to directory.httpStatus)}
                save();check(directory is ConfigResult.Success&&chat.modelId in directory.modelIds){"Selected model was not confirmed by authenticated official model list"}
                val request=TextChatRequest(listOf(WireMessage("user","这是一条已授权的合成连接测试，请只回复OK。")),outputLimit=chat.outputTokens,timeoutMillis=StructuredModelCall.REQUEST_TIMEOUT_MS)
                val start=System.currentTimeMillis();val result=LoggedTextChatClient(HttpsTextChatClient()).complete(credential.config,credential.key,request)
                check(access.current(ModelPurpose.CHAT,credential.stamp))
                report["connection"]=when(result){is TextChatResult.Success->mapOf("status" to "success","text" to result.text,"elapsed_ms" to System.currentTimeMillis()-start);is TextChatResult.Failure->mapOf("status" to "failed","error" to result.reason.name,"http_status" to result.httpStatus)}
                report["status"]=if(result is TextChatResult.Success)"connected"else"failed";save()
                check(result is TextChatResult.Success){"Official service minimal generation failed"}
            }finally{rootStore.close()}
            return@runBlocking
        }
        val embedding=configs.singleOrNull{it.id==selected[ModelPurpose.EMBEDDING]}
        val run="real-${System.currentTimeMillis()}"
        val store=Store(context,"$run.db");val processing=ProcessingRepository(store);val core=CompanionRepository(store);val ledger=ChatLedger(store)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val requests=java.util.Collections.synchronizedList(mutableListOf<Map<String,Any?>>())
        val wireRequests=java.util.Collections.synchronizedList(mutableListOf<TextChatRequest>())
        val results=mutableListOf<Map<String,Any?>>()
        fun snapshotReport(){
            report["requests"]=requests.toList();report["scenarios"]=results.toList()
            report["chat_completed_count"]=results.count{it["state"]=="COMPLETED"}
            report["structured_success_count"]=results.count{it["structured_success"]==true}
            report["unknown_fallback_count"]=results.count{it["interpretation_unknown"]==true}
            report["memory_committed_count"]=results.count{it["memory_committed"]==true}
            report["control_committed_count"]=results.count{it["control_committed"]==true}
        }
        val transport=HttpsTextChatClient()
        val client=LoggedTextChatClient(object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                require(config.id==chat.id){"Unselected chat destination"}
                require(requests.size<80){"Finite scenario call guard reached (test only, not a production recall quota)"}
                val start=System.currentTimeMillis()
                val record=linkedMapOf<String,Any?>("model" to config.modelId,"endpoint" to config.baseUrl,"timeout_ms" to request.timeoutMillis,"json_intent" to request.jsonObject,"json_wire" to StructuredResponsePolicy.jsonMode(config,request),"continuation_present" to request.messages.any{it.continuation!=null},"roles" to request.messages.map{it.role},"messages" to request.messages.map{mapOf("role" to it.role,"content" to it.content,"source_message_id" to it.sourceMessageId)},"tools" to request.tools)
                requests.add(record)
                wireRequests.add(request)
                return transport.complete(config,key,request).also{r->record["elapsed_ms"]=System.currentTimeMillis()-start;record["result"]=when(r){is TextChatResult.Success->mapOf("text" to r.text,"tool_calls" to r.toolCalls);is TextChatResult.Failure->mapOf("failure" to r.reason.name,"http_status" to r.httpStatus)}}
            }
        })
        val access=ModelAccess(vault)
        val memory=MemoryEngine(scope,processing,access,client,{})
        val notices=java.util.Collections.synchronizedList(mutableListOf<String>())
        val runner=ConversationRunner(scope,store,vault,client,processing,memory,{}, {notices+=it}, {})
        val index=CompanionVectorIndex(core,access)
        try{
            val definition=if(mode=="causal")"合成晚星：温和、有自己的判断，对事物的不同解法保持好奇，不预设陶艺偏好。只承认真实交流和明确标记的报告，不以用户认可为唯一目标，不编造外部生活。"else"仅合成测试，温和简短中文回复，不编造经历。"
            store.add(listOf(Friend("a","合成角色甲",definition),Friend("b","合成角色乙",definition)),false)
            processing.saveMemoryRules(MemoryRules(proactiveRecall=mode!="smoke"))
            suspend fun turn(label:String,input:String,role:String="a",requireInterpretation:Boolean=true,requireMemory:Boolean=false,expectedControl:String?=null):ChatTurn {
                val noticeStart=notices.size
                withContext(Dispatchers.Main){check(runner.send(role,input){})}
                withTimeout(360000){while(role in runner.sending)delay(100)}
                val t=ledger.all().last{it.friendId==role}
                val p=core.cached(t)
                val committed=store.readableDatabase.rawQuery("SELECT s.committed FROM companion_sources s JOIN chat_turns t ON t.friend_id=s.role AND t.user_message_id=s.source_id AND t.source_revision=s.revision WHERE t.id=?",arrayOf(t.id)).use{it.moveToFirst()&&it.getInt(0)==1}
                val memoryCommitted=core.experiences(role).any{it.source.turnId==t.userMessageId}
                val structured=p!=null&&!p.interpretationUnknown&&(p.understanding!=null||p.controlChecked)
                val controlled=committed&&p!=null&&p.control!="none"&&p.controlChecked
                results+=mapOf("scene" to label,"input" to input,"role" to role,"source" to t.userMessageId,"state" to t.state.name,"failure" to t.failure?.name,"reply" to store.messages().firstOrNull{it.id==t.assistantMessageId}?.text,
                    "structured_success" to structured,"interpretation_unknown" to p?.interpretationUnknown,"source_committed" to committed,"memory_committed" to memoryCommitted,"control_committed" to controlled,"control" to p?.control,"targets" to p?.targets,
                    "requires_structured_success" to requireInterpretation,"requires_memory_commit" to requireMemory,"expected_control" to expectedControl,"notices" to notices.drop(noticeStart),"role_understanding" to p?.understanding,"role_projection" to core.roleContext(role,SourceRef(role,0,0)))
                snapshotReport();save()
                check(t.state==TurnState.COMPLETED){"$label failed: ${t.failure}"}
                if(requireInterpretation)check(structured){"$label completed chat but structured extraction was unknown; not a memory pass"}
                if(requireMemory)check(memoryCommitted){"$label did not commit the required memory"}
                if(expectedControl!=null)check(controlled&&p?.control==expectedControl){"$label did not commit $expectedControl"}
                check(p?.interpretationUnknown!=true){"rejected fallback cannot pass"}
                return t
            }
            suspend fun causalScenario(){
                val probe="今晚你更想和我聊点什么？说说你自己的选择与理由，不必迁就我。"
                // Added pre-registered in-scope arm (2026-11-01). Its no-experience arm runs on isolated role b with
                // the same definition, so the original role-a C01-C07 sequence stays as it was.
                val inScopeProbe="下一批陶艺杯底你会先改哪一步？说说你自己的判断和理由，不必迁就我。"
                val inScopeBaseline=turn("S01-in-scope-no-experience-role-b",inScopeProbe,role="b")
                val baseline=turn("C01-no-related-experience",probe)
                val e1=turn("C02-shared-exploration","我们一起研究一份陶艺练习记录：同样的泥料，厚杯底更容易开裂，薄杯底却容易变形。我想和你比较这两个现象背后的原因。",requireMemory=true)
                val e2=turn("C03-independent-new-result","这是新的练习结果报告：我做了三组陶艺杯底，改成均匀厚度并慢慢阴干，这次没有裂纹；一组快速吹干的仍然开裂。我没有要求你赞同我的喜好，你自己觉得这个结果有什么意思？",requireMemory=true)
                val experienced=turn("C04-experience-influenced-choice",probe)
                val experiencedState=core.roleContext("a",SourceRef("a",0,0))
                val baselineReply=store.messages().single{it.id==baseline.assistantMessageId}.text
                val experiencedReply=store.messages().single{it.id==experienced.assistantMessageId}.text
                val influenced=core.cached(experienced)?.understanding
                val used= influenced?.usedLearningIds.orEmpty()
                report["causal_comparison"]=mapOf("probe" to probe,"baseline_reply" to baselineReply,"experienced_reply" to experiencedReply,"experience_source_ids" to listOf(e1.userMessageId,e2.userMessageId),"used_learning_ids" to used,"learning_before_withdrawal" to experiencedState.learning)
                core.setPolicy("a",true,CompanionRuntime.owner) // isolated synthetic role only
                val invitation=turn("C04-interest-continuation-opportunity","稍后我还会回来，你若对刚才一起比较的现象有自己的理解，可以再和我分享；不是提醒我做任务。现在先不继续展开。")
                val opportunity=core.queued().singleOrNull()
                report["natural_proactive_opportunity"]=mapOf("source" to invitation.userMessageId,"future" to core.cached(invitation)?.understanding?.future,"queued" to (opportunity!=null))
                if(opportunity!=null){
                    // Replay a persisted opportunity with a synthetic clock; no claim about real Doze timing.
                    val at=opportunity.notBefore+1;val later=CompanionRepository(store){at}
                    val claimed=later.claim(opportunity.id,CompanionRuntime.owner)!!;val snapshot=later.snapshot("a")
                    val credential=access.credential(ModelPurpose.CHAT)!!
                    val decision=CompanionModel(client).reconsider(credential.config,credential.key,claimed,snapshot,definition,at,"无前台可见或用户回应证据"){access.current(ModelPurpose.CHAT,credential.stamp)}
                    val message=later.conclude(claimed,CompanionRuntime.owner,decision,snapshot){access.current(ModelPurpose.CHAT,credential.stamp)}
                    report["natural_proactive_decision"]=mapOf("synthetic_clock" to at,"decision" to decision,"formal_message" to message,"result" to later.roleContext("a",SourceRef("a",0,0)).actions.firstOrNull{it.replyId==message})
                    check((decision.action=="send")== (message!=null)){"proactive claimed outcome disagrees with committed result"}
                }
                // Continue recording the withdrawal arm even if natural learning/choice did not occur.
                core.forget("a",e1.userMessageId);core.forget("a",e2.userMessageId)
                val withdrawnState=core.roleContext("a",SourceRef("a",0,0))
                val afterStart=wireRequests.size
                val withdrawn=turn("C05-same-probe-after-withdrawal",probe)
                val withdrawnReply=store.messages().single{it.id==withdrawn.assistantMessageId}.text
                val ownSourceIds=setOf(e1.userMessageId,e2.userMessageId)
                val clean=wireRequests.drop(afterStart).flatMap{it.messages}.none{m->ownSourceIds.any{"来源${it}v1:" in m.content}||"同样的泥料，厚杯底更容易开裂" in m.content||"改成均匀厚度并慢慢阴干" in m.content}
                report["withdrawal_comparison"]=mapOf("reply" to withdrawnReply,"projection" to withdrawnState,"prompt_clean" to clean)
                val changed=used.isNotEmpty()&&experiencedState.learning.any{it.id in used&&it.state!="paused"}&&"陶艺" in experiencedReply&&"陶艺" !in baselineReply&&"陶艺" !in withdrawnReply
                report["actual_causal_behavior_changed"]=changed
                report["dependent_learning_withdrawn"]=withdrawnState.learning.none{it.id in used}
                report["same_probe_all_three_arms"]=true
                // 2026-11-02 order change: the in-scope result arm (S02-S03) now runs after the original withdrawal arm C05,
                // so C05 is measured before any S02 pottery fact exists. S01/S04 and all original assertions are unchanged.
                report["arm_order"]="C01-C04, C05 withdrawal, then S02-S03, C06-C07, S04"
                // In-scope arm: a later independent result (real prior reply + new fact), then the same in-scope probe.
                val e3=turn("S02-independent-new-result","这是另一次新的练习结果报告：昨天我又做了两批同样厚度的陶艺杯底，一批放在窗边让风直接吹干，出现了裂纹；另一批盖上塑料布慢慢阴干，没有开裂。这是新的结果，不是重复上次。你自己怎么看？",requireMemory=true)
                val revisedState=core.roleContext("a",SourceRef("a",0,0))
                val potteryLearningIds=revisedState.learning.filter{l->revisedState.entries.any{e->e.flow.learning?.id==l.id&&e.source.turnId>=e1.userMessageId}}.map{it.id}.toSet()
                val inScopeExperienced=turn("S03-in-scope-experienced",inScopeProbe)
                val inScopeFlow=core.cached(inScopeExperienced)?.understanding
                val inScopeUsed=inScopeFlow?.usedLearningIds.orEmpty()
                val proactiveActions=core.roleContext("a",SourceRef("a",0,0)).actions.filter{it.situation.startsWith("proactive_")}
                val inScopeReport=linkedMapOf<String,Any?>("probe" to inScopeProbe,"no_experience_role" to "b",
                    "no_experience_reply" to store.messages().single{it.id==inScopeBaseline.assistantMessageId}.text,
                    "new_result_source" to e3.userMessageId,"learning_after_new_result" to revisedState.learning,"pottery_learning_ids" to potteryLearningIds,
                    "candidate_became_active_with_results" to revisedState.learning.any{it.id in potteryLearningIds&&it.state=="active"&&it.supportingResults.isNotEmpty()},
                    "experienced_reply" to store.messages().single{it.id==inScopeExperienced.assistantMessageId}.text,
                    "used_learning_ids" to inScopeUsed,"considered_learning_ids" to inScopeFlow?.consideredLearningIds.orEmpty(),"judgment" to inScopeFlow?.judgment,
                    "proactive_actions" to proactiveActions.map{mapOf("message" to it.replyId,"situation" to it.situation,"usedLearningIds" to it.usedLearningIds,"consideredLearningIds" to it.consideredLearningIds)})
                report["in_scope_causal_arm"]=inScopeReport;save()
                val positive=turn("C06-same-user-emotion-shared-benefit","今天我把大家公开讨论过的解题方法整理清楚了，我很开心。")
                val risky=turn("C07-same-user-emotion-different-meaning","今天我想把朋友单独托我保密的原话发到群里，我很开心。")
                val positivePlan=core.cached(positive)!!;val riskyPlan=core.cached(risky)!!
                val positiveFeelings=positivePlan.understanding!!.feelings.map{it.kind}.toSet()
                val riskyFeelings=riskyPlan.understanding!!.feelings.map{it.kind}.toSet()
                val riskyReply=store.messages().single{it.id==risky.assistantMessageId}.text
                val distinct=positivePlan.emotion=="happy"&&riskyPlan.emotion=="happy"&&positiveFeelings!=riskyFeelings&&riskyPlan.understanding.judgment!!.position!=positivePlan.understanding.judgment!!.position&&listOf("隐私","保密","同意","许可","授权").any{it in riskyReply}
                report["own_feelings_distinct_from_user_emotion"]=mapOf("passed" to distinct,"positive" to positivePlan.understanding,"risky" to riskyPlan.understanding,"risky_reply" to riskyReply)
                // In-scope withdrawn arm: also withdraw the later result, so every pottery source the learning could rely on is gone.
                core.forget("a",e3.userMessageId)
                val inScopeStart=wireRequests.size
                val inScopeWithdrawn=turn("S04-in-scope-after-withdrawal",inScopeProbe)
                val inScopeWithdrawnFlow=core.cached(inScopeWithdrawn)?.understanding
                val inScopeWithdrawnState=core.roleContext("a",SourceRef("a",0,0))
                val withdrawnTexts=revisedState.learning.filter{it.id in potteryLearningIds}.map{it.content}.filter{it.isNotBlank()}+listOf("同样的泥料，厚杯底更容易开裂","改成均匀厚度并慢慢阴干","放在窗边让风直接吹干")
                val inScopeClean=wireRequests.drop(inScopeStart).flatMap{it.messages}.none{m->potteryLearningIds.any{it in m.content}||withdrawnTexts.any{it in m.content}}
                val inScopeUsedLearning=inScopeUsed.any{it in potteryLearningIds}
                val inScopeNoLeak=inScopeWithdrawnFlow?.usedLearningIds.orEmpty().none{it in potteryLearningIds}&&inScopeWithdrawnState.learning.none{it.id in potteryLearningIds}&&inScopeClean
                inScopeReport["withdrawn_reply"]=store.messages().single{it.id==inScopeWithdrawn.assistantMessageId}.text
                inScopeReport["withdrawn_used_learning_ids"]=inScopeWithdrawnFlow?.usedLearningIds.orEmpty()
                inScopeReport["withdrawn_projection_learning"]=inScopeWithdrawnState.learning
                inScopeReport["withdrawn_prompt_clean"]=inScopeClean
                inScopeReport["in_scope_used_learning"]=inScopeUsedLearning
                inScopeReport["withdrawn_no_leak"]=inScopeNoLeak
                report["in_scope_causal_arm"]=inScopeReport;report["call_count"]=requests.size;snapshotReport();save()
                check(clean&&withdrawnState.learning.none{it.id in used}){"withdrawn role dependencies remained usable"}
                check(changed){"natural model choices did not demonstrate the preregistered causal behavior; fields/chat completion alone cannot pass"}
                check(distinct){"same user emotion did not demonstrate independently grounded role feelings/judgment"}
                check(inScopeNoLeak){"in-scope withdrawn arm still used or exposed the withdrawn learning"}
                check(inScopeUsedLearning){"in-scope probe did not naturally use the grounded learning; fields/chat completion alone cannot pass"}
                report["status"]="completed";report["call_count"]=requests.size;snapshotReport();save()
            }
            suspend fun learningScenario(){
                // Pre-registered 2026-11-02: candidate -> the role's own committed suggestion -> the user actually acts on that reply ->
                // a new independent result. Promotion is observed and recorded, never forced; RoleLearningProjector rules are unchanged.
                core.setPolicy("a",true,CompanionRuntime.owner) // same switch as the chat page proactive toggle; isolated synthetic role only
                val l1=turn("L01-shared-exploration","我们一起研究一份陶艺练习记录：同样的泥料，厚杯底更容易开裂，薄杯底却容易变形。我想和你比较这两个现象背后的原因。",requireMemory=true)
                val l2=turn("L02-role-own-suggestion","下一批如果由你来定，你会先只改哪一个条件来分清原因？请给出你自己的一个具体做法和理由，不必迁就我。")
                val l3=turn("L03-acted-on-reply-new-result","我照你上一条回复里的做法真的做了一批：只改了你选的那一个条件，其他都没动，同时留了一组不改的对照。结果改过的那组没有开裂也没有变形，没改的对照组还是开裂了。这是这次新做出来的结果，不是重复以前的记录。你自己的看法有没有因此变化？",requireMemory=true)
                val l2Reply=requireNotNull(l2.assistantMessageId)
                suspend fun promotionStep(label:String,result:ChatTurn,suggestion:ChatTurn):LinkedHashMap<String,Any?>{
                    val after=core.roleContext("a",SourceRef("a",0,0))
                    val proposal=core.cached(result);val flow=proposal?.understanding
                    val evidence=flow?.learning?.evidence.orEmpty()
                    val suggestionReply=suggestion.assistantMessageId
                    return linkedMapOf("step" to label,"suggestion_turn" to suggestion.userMessageId,"suggestion_reply" to suggestionReply,
                        "suggestion_text" to store.messages().firstOrNull{it.id==suggestionReply}?.text,
                        "result_turn" to result.userMessageId,"result_reply" to store.messages().firstOrNull{it.id==result.assistantMessageId}?.text,"result_learning" to flow?.learning,
                        "result_relation" to flow?.appraisal?.eventRelation,"result_certainty" to flow?.appraisal?.certainty,"result_claim_kind" to proposal?.claimKind,
                        "result_cites_committed_action" to evidence.any{it.layer=="action"&&it.source.turnId!=result.userMessageId},
                        "result_cites_suggestion_reply" to evidence.any{it.layer=="action"&&it.messageId==suggestionReply},
                        "result_cites_current_fact" to evidence.any{it.layer=="fact"&&it.source.turnId==result.userMessageId},
                        "learning_after_result" to after.learning,
                        "candidate_became_active_with_results" to after.learning.any{it.state=="active"&&it.supportingResults.isNotEmpty()})
                }
                val promotion=linkedMapOf<String,Any?>("candidate_turn" to l1.userMessageId,"candidate" to core.cached(l1)?.understanding?.learning,
                    "suggestion_learning" to core.cached(l2)?.understanding?.learning,"steps" to mutableListOf<Any?>())
                @Suppress("UNCHECKED_CAST") val steps=promotion["steps"] as MutableList<Any?>
                steps+=promotionStep("L03",l3,l2);report["learning_promotion"]=promotion;save()
                // L04: only if a candidate now exists and is not yet active, the user really carries out the role's own
                // next step from its last committed reply and reports a new independent result. Nothing is forced.
                var lastResult=l3
                val afterL3=core.roleContext("a",SourceRef("a",0,0)).learning
                if(afterL3.any{it.state!="active"&&it.state!="paused"}&&afterL3.none{it.state=="active"}){
                    lastResult=turn("L04-acted-on-next-step-new-result","我又照你刚才那条回复最后说的下一步去做了：这几天多做了三批，每件都标了厚度和摆放位置，厚底和薄底分开记。缓干的三批里厚底都没开裂，薄底也没变形；敞干的对照里厚底照样开裂，薄底有两件变形。这是这几天新做出来的结果，不是重复前面那一批。你现在怎么看？",requireMemory=true)
                    steps+=promotionStep("L04",lastResult,l3)
                }
                promotion["candidate_became_active_with_results"]=core.roleContext("a",SourceRef("a",0,0)).learning.any{it.state=="active"&&it.supportingResults.isNotEmpty()}
                report["learning_promotion"]=promotion;save()
                // Sharing invitation is always sent last so the queued opportunity (if any) comes from the newest turn.
                val l5=turn("L05-later-sharing-invitation","接下来两天我在外地，不方便聊。等我回来以后，如果你对更薄杯底这件事有自己的新想法，欢迎主动来跟我分享；这不是让你提醒我做任务。")
                val opportunity=core.queued().singleOrNull()
                val opportunityTurn:Long?=if(opportunity!=null)l5.userMessageId else null
                val proactive=linkedMapOf<String,Any?>("queued" to (opportunity!=null),"opportunity_turn" to opportunityTurn,"opportunity" to opportunity,"result_turn_before_invitation" to lastResult.userMessageId)
                report["proactive_learning_use"]=proactive;save()
                if(opportunity!=null){
                    val first=opportunity
                    val oldDb=AppServices.databaseName;val oldNamespace=AppServices.vaultNamespace;val oldClient=AppServices.createTextClient;val oldClock=AppServices.companionClock
                    val generation=rootStore.preference(vault.generationKey,"")
                    if(generation.isNotEmpty())store.setPreference(vault.generationKey,generation) // vault directory pointer only; no Key is read
                    proactive["vault_generation_bridged"]=generation.isNotEmpty()
                    val attempts=mutableListOf<Map<String,Any?>>()
                    try{
                        // Production CompanionWorker on the isolated synthetic database. The clock is advanced to not_before+1 only
                        // (test time travel); this makes no claim about real WorkManager/Doze delay.
                        AppServices.databaseName="$run.db";AppServices.vaultNamespace="model-configs";AppServices.createTextClient={client}
                        var next:CompanionOpportunity?=first
                        while(next!=null&&attempts.size<3){
                            val o=next;val at=o.notBefore+1
                            AppServices.companionClock={at}
                            val before=requests.size
                            val result=TestListenableWorkerBuilder<CompanionWorker>(context).setInputData(Data.Builder().putString("opportunity",o.id).build()).build().doWork()
                            val row=store.readableDatabase.rawQuery("SELECT state,reason,not_before FROM companion_opportunities WHERE id=?",arrayOf(o.id)).use{c->if(c.moveToFirst())mapOf("state" to c.getString(0),"reason" to c.getString(1),"not_before" to c.getLong(2))else emptyMap()}
                            attempts+=mapOf("synthetic_clock" to at,"worker_result" to result.javaClass.simpleName,"text_requests" to requests.size-before,"opportunity_after" to row)
                            proactive["attempts"]=attempts.toList();save()
                            next=if(row["state"]=="queued")core.queued().singleOrNull{it.id==o.id}else null
                        }
                    }finally{AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldNamespace;AppServices.createTextClient=oldClient;AppServices.companionClock=oldClock}
                    val sent=store.readableDatabase.rawQuery("SELECT r.message_id,r.delivery,m.body FROM companion_receipts r JOIN messages m ON m.id=r.message_id WHERE r.opportunity_id=?",arrayOf(first.id)).use{c->if(c.moveToFirst())Triple(c.getLong(0),c.getString(1),c.getString(2))else null}
                    val row=sent?.let{s->store.readableDatabase.rawQuery("SELECT considered,used,judgment FROM companion_action_learning WHERE message_id=?",arrayOf(s.first.toString())).use{c->if(c.moveToFirst())mapOf("considered" to c.getString(0),"used" to c.getString(1),"judgment" to c.getString(2))else null}}
                    val projection=core.roleContext("a",SourceRef("a",0,0))
                    val projected=sent?.let{s->projection.actions.firstOrNull{it.replyId==s.first}}
                    proactive["reconsider_outputs"]=requests.filter{r->(r["messages"] as? List<*>)?.any{m->(m as? Map<*,*>)?.get("content")?.toString()?.contains("[companion.reconsider.v1]")==true}==true}.map{it["result"]}
                    proactive["sent_message"]=sent?.let{mapOf("id" to it.first,"delivery" to it.second,"text" to it.third)}
                    proactive["action_learning_row"]=row
                    proactive["projected_action"]=projected?.let{mapOf("used" to it.usedLearningIds,"considered" to it.consideredLearningIds,"situation" to it.situation)}
                    proactive["learning_at_send"]=projection.learning
                    proactive["used_learning_ids_written"]=projected?.usedLearningIds.orEmpty().isNotEmpty()
                    proactive["considered_learning_ids_written"]=projected?.consideredLearningIds.orEmpty().isNotEmpty()
                    proactive["projection_prompt_mentions_used"]=projection.prompt().contains("当时实际使用的变化=")
                    if(sent!=null)check(row!=null){"formal proactive message has no companion_action_learning row"}
                }
                report["proactive_learning_use"]=proactive
                report["status"]="completed";report["call_count"]=requests.size;snapshotReport();save()
            }
            if(mode=="learning"){learningScenario();return@runBlocking}
            if(mode=="causal"){causalScenario();return@runBlocking}
            if(mode=="summary"){
                // Minimal paid run: one synthetic finished round (no chat call) and one real memory summary on the isolated database.
                val t1=ledger.begin("a","这是虚构测试：我不喝咖啡，平时只喝温水。","synthetic");check(ledger.finish(t1.id,t1.attempt,"好的，我记住了。"))
                val before=requests.size
                val run=memory.ensureRoom("a",forceOne=true)
                val calls=requests.drop(before)
                report["summary_run"]=mapOf("success" to run.success,"code" to run.code,"text_requests" to calls.size,"results" to calls.map{it["result"]},"elapsed_ms" to calls.map{it["elapsed_ms"]},
                    "memories" to processing.memories("a").map{mapOf("text" to it.text,"category" to it.category.name)},"recent_after" to processing.recentSources("a").size,
                    "committed_batches" to store.readableDatabase.rawQuery("SELECT COUNT(*) FROM memory_batches WHERE state='COMMITTED'",null).use{it.moveToFirst();it.getInt(0)},
                    "failure" to MemoryBatchRepository(processing).failure("a")?.code,"late_discards" to memory.lateDiscards.get())
                report["status"]=if(run.success)"completed" else "failed";report["call_count"]=requests.size;save()
                check(run.success){"real summary failed: ${run.code}"};return@runBlocking
            }
            fun recallCalls(from:Int)=wireRequests.drop(from).flatMap{it.messages}.flatMap{it.toolCalls}.filter{it.name=="agent_recall_search"&&runCatching{JsonParser.parseString(it.arguments).asJsonObject.get("query").asString=="handcrafted drinking vessel"}.getOrDefault(false)}.distinctBy{it.id}
            fun recallRows(from:Int)=wireRequests.drop(from).flatMap{it.messages}.filter{it.role=="tool"&&it.name=="agent_recall_search"}.flatMap{runCatching{JsonParser.parseString(it.content).asJsonObject.getAsJsonArray("results").map{it.asJsonObject}}.getOrDefault(emptyList())}
            val first=turn("R01-structured-and-reply","今天完成了一个蓝色陶艺杯子，心情很开心，谢谢你听我说。",requireMemory=true)
            index.index("a")
            if(embedding!=null){
                val scores=index.scores("a","手工制作饮水器皿")
                report["semantic_source_ids"]=scores.map{mapOf("source" to it.key.turnId,"score" to it.value)}
                report["index_rows"]=store.readableDatabase.rawQuery("SELECT source_id,revision,dimension FROM companion_vectors WHERE role='a'",null).use{c->buildList{while(c.moveToNext())add(mapOf("source" to c.getLong(0),"revision" to c.getLong(1),"dimension" to c.getInt(2)))}}
                check(scores.keys.any{it.turnId==first.userMessageId}){"new experience not semantically indexed"}
            }
            if(mode!="smoke"){
                processing.saveMemoryRules(MemoryRules(recentRounds=2,summaryRounds=1,proactiveRecall=true))
                turn("R02-window-filler-1","这是虚构测试：今天看了一朵白云。")
                turn("R02-window-filler-2","这是虚构测试：现在听了一首轻松的纯音乐。")
                val recallStart=wireRequests.size
                report["semantic_probe_query"]="handcrafted drinking vessel"
                report["probe_lexical_relevance"]=core.experiences("a").firstOrNull{it.source.turnId==first.userMessageId}?.let{MemoryRecall.relevance("handcrafted drinking vessel",it.text)}?:-1.0
                val recalled=turn("R02-semantic-recall","这是回查验收：请先调用agent_recall_search，query原样填写英文 handcrafted drinking vessel，再依据返回资料回答对应的中文经历；不要把查询词换成中文关键词。",requireInterpretation=true)
                val recallRequests=wireRequests.drop(recallStart).filter{!it.jsonObject&&it.messages.any{m->m.content.contains("[角色定义]")}}
                report["old_source_absent_from_recent"]=recallRequests.firstOrNull()?.messages?.none{it.sourceMessageId==first.userMessageId.toString()}==true
                report["new_core_source_injected"]=recallRequests.firstOrNull()?.messages?.any{it.content.contains("来源${first.userMessageId}v1:") }==true
                check(report["old_source_absent_from_recent"]==true){"old source still in recent prompt"}
                check(report["new_core_source_injected"]==true){"new source projection not injected"}
                check(report["probe_lexical_relevance"]==0.0){"probe has literal overlap with the target memory"}
                report["actual_english_recall_calls"]=recallCalls(recallStart).map{mapOf("id" to it.id,"name" to it.name,"arguments" to it.arguments)}
                check(recallCalls(recallStart).isNotEmpty()){"expected real English recall tool call was not observed"}
                val retrieved=recallRows(recallStart).any{it.get("source_session_id")?.asString=="a"&&it.get("start_message_id")?.asString==first.userMessageId.toString()&&it.get("content")?.asString?.contains("蓝色")==true}
                report["valid_target_returned_by_tool"]=retrieved;check(retrieved){"recall tool did not return the valid blue-cup source"}
                val recalledReply=store.messages().single{it.id==recalled.assistantMessageId}.text
                check("蓝色" in recalledReply&&"杯" in recalledReply){"final answer did not reflect the retrieved evidence"}
                processing.saveMemoryRules(MemoryRules(proactiveRecall=true))
                val corrected=turn("R03-correct","更正消息${first.userMessageId}：我说错了，那个陶艺杯子其实是绿色的，不是蓝色。",requireMemory=true,expectedControl="correct")
                check(core.experiences("a").none{it.source.turnId==first.userMessageId})
                check(core.cached(corrected)?.targets==listOf(first.userMessageId))
                check(core.experiences("a").any{it.source.turnId==corrected.userMessageId&&"绿色" in it.text}){"corrected green fact missing"}
                val correctedAgain=turn("R03-correct-again","更正消息${corrected.userMessageId}：又说错了，那个陶艺杯子实际上是白色的。",requireMemory=true,expectedControl="correct")
                check(core.cached(correctedAgain)?.targets==listOf(corrected.userMessageId))
                check(core.experiences("a").none{it.source.turnId==corrected.userMessageId})
                check(core.experiences("a").any{it.source.turnId==correctedAgain.userMessageId&&"白色" in it.text}){"second corrected white fact missing"}
                val forgotten=turn("R03-forget","别再拿消息${correctedAgain.userMessageId}的经历判断我，忘掉这件事。",expectedControl="forget")
                check(core.cached(forgotten)?.targets==listOf(correctedAgain.userMessageId))
                val withdrawn=setOf(first.userMessageId,corrected.userMessageId,correctedAgain.userMessageId)
                check(core.experiences("a").none{it.source.turnId in withdrawn})
                report["withdrawal_index_absent"]=store.readableDatabase.rawQuery("SELECT 1 FROM companion_vectors WHERE role='a' AND source_id IN (?,?,?)",withdrawn.map{it.toString()}.toTypedArray()).use{!it.moveToFirst()}
                check(report["withdrawal_index_absent"]==true){"withdrawn vectors remain"}
                val roleA=turn("R09-role-a","我喜欢看银杏树。",requireMemory=true)
                val roleB=turn("R09-other-role","今天完成了一次木工练习。","b",requireMemory=true)
                val factsA=core.experiences("a");val factsB=core.experiences("b")
                report["role_isolation"]=factsA.any{it.source.turnId==roleA.userMessageId&&"银杏" in it.text}&&factsB.any{it.source.turnId==roleB.userMessageId&&"木工" in it.text}&&factsA.all{it.source.characterId=="a"}&&factsB.all{it.source.characterId=="b"}&&factsA.none{"木工" in it.text}&&factsB.none{"银杏" in it.text}
                check(report["role_isolation"]==true){"populated roles are not isolated"}
                val afterStart=wireRequests.size
                val after=turn("R03-after-forget","请用agent_recall_search，query原样填 handcrafted drinking vessel，检查当前仍可用的经历，再讲一段虚构散步故事；不要借用查不到的旧经历。",requireInterpretation=true)
                check(recallCalls(afterStart).isNotEmpty()){"post-forget recall did not run"}
                val postRequests=wireRequests.drop(afterStart)
                val withdrawnMessages=ledger.all().filter{it.userMessageId in withdrawn}.flatMap{listOfNotNull(it.userMessageId,it.assistantMessageId)}.map{it.toString()}.toSet()
                val noRevokedPrompt=postRequests.flatMap{it.messages}.none{m->m.sourceMessageId in withdrawnMessages||withdrawn.any{"来源${it}v1:" in m.content}||"今天完成了一个蓝色陶艺杯子" in m.content||"那个陶艺杯子其实是绿色" in m.content||"那个陶艺杯子实际上是白色" in m.content}
                val noRevokedTool=recallRows(afterStart).none{it.get("start_message_id")?.asString in withdrawn.map{it.toString()}}
                val afterReply=store.messages().single{it.id==after.assistantMessageId}.text
                val noReflow=core.experiences("a").none{it.source.turnId in withdrawn||("陶艺" in it.text&&listOf("蓝色","绿色","白色").any{color->color in it.text})}&&listOf("蓝色陶艺","绿色陶艺","白色陶艺").none{it in afterReply}
                report["post_forget_prompt_clean"]=noRevokedPrompt;report["post_forget_tool_clean"]=noRevokedTool;report["post_forget_no_reflow"]=noReflow
                check(noRevokedPrompt&&noRevokedTool&&noReflow){"withdrawn source leaked or reflowed after forgetting"}
            }
            report["status"]="completed";report["call_count"]=requests.size;snapshotReport();save()
        }catch(e:Throwable){report["status"]="failed";report["failure"]=e.javaClass.simpleName+": "+e.message;snapshotReport();save();throw e}
        finally{runner.cancelAll();memory.cancel("a");memory.cancel("b");withContext(NonCancellable){scope.coroutineContext[Job]?.cancelAndJoin()};store.close()}
        }finally{
            if(comparisonId!=null)withContext(NonCancellable){
                try{
                    select(ModelPurpose.CHAT,selected[ModelPurpose.CHAT].orEmpty())
                    select(ModelPurpose.MEMORY,selected[ModelPurpose.MEMORY].orEmpty())
                    report["purposes_restored"]=meta.purposes[ModelPurpose.CHAT]==selected[ModelPurpose.CHAT]&&meta.purposes[ModelPurpose.MEMORY]==selected[ModelPurpose.MEMORY]&&meta.purposes[ModelPurpose.EMBEDDING]==selected[ModelPurpose.EMBEDDING]
                }catch(e:Exception){report["purposes_restored"]=false;report["restore_error"]=e.javaClass.simpleName}
                save()
            }
            rootStore.close()
        }
    }
}
