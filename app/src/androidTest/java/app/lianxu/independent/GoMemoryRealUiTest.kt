package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Opt-in actual UI and production runner/tools/SQLite; only the data/vault and observer are isolated. */
class GoMemoryRealUiTest {
    private val forgetDiagnosis get()=InstrumentationRegistry.getArguments().getString("controlledForgetDiagnosis")=="true"
    private val captureEvidence get()=normalSample||forgetDiagnosis
    private val normalSample get()=InstrumentationRegistry.getArguments().getString("controlledNormalSample")=="true"
    private val sampleNumber get()=InstrumentationRegistry.getArguments().getString("sampleNumber")?.toIntOrNull()?.also{check(it>0)}?:1
    private val sampleFacts get()=listOf("茉莉白茶|木刻|青榕书店","桂花乌龙|陶艺|雨巷画廊","陈皮普洱|摄影|白鹭湖","蜜桃红茶|刺绣|松风公园","柠檬绿茶|篆刻|南桥工坊","玫瑰花茶|编织|云杉图书馆","薄荷清茶|观鸟|枫林步道","山楂果茶|折纸|青石展馆","金桂红茶|素描|春岚茶室","洛神花茶|园艺|溪岸花圃")[(sampleNumber-1)%10].split("|").let{listOf(it[0],it[1],"${it[2]}第${sampleNumber}展区")}
    private val contactMode get()=InstrumentationRegistry.getArguments().getString("controlledContact")=="true"
    private val contactOnly get()=contactMode&&InstrumentationRegistry.getArguments().getString("controlledContactOnly")=="true"
    private val mutationMode get()=InstrumentationRegistry.getArguments().getString("controlledMutation")=="true"
    private val correctionMode get()=InstrumentationRegistry.getArguments().getString("controlledCorrection")=="true"||forgetDiagnosis
    private val keepIsolated get()=contactMode||mutationMode||correctionMode||normalSample
    private val requestLimit get()=if(normalSample)Int.MAX_VALUE else if(correctionMode||contactOnly)12 else if(mutationMode)20 else 30
    private val supplement get()=!keepIsolated&&InstrumentationRegistry.getArguments().getString("controlledSupplement")=="true"
    private val inputs get()=if(forgetDiagnosis)listOf(
        "我是纯虚构旅人听澜。我长期喜欢桂花乌龙，请把这个饮茶偏好记住。",
        "更正我刚才说的饮茶偏好：我其实长期喜欢茉莉白茶，桂花乌龙说错了。请停用那条旧说法，并记住更正后的偏好。",
        "请忘掉我刚才说的茉莉白茶偏好，以后不要再引用这条喜好。原聊天保留就好。")else if(normalSample){
        val f=sampleFacts
        if(contactMode)listOf("我是纯虚构旅人青禾${sampleNumber}号。现在在喝${f[0]}。请大约30秒后主动来问我杯子里的茶凉了没有；这次问过就不再续约。")
        else listOf("我是纯虚构旅人叶舟${sampleNumber}号。我长期喜欢喝${f[0]}，周末的固定爱好是${f[1]}。先自然接一句就好。","我平时最常去的地方是${f[2]}。我长期不吃香菜，点餐时都要去掉。这些都是这个故事里的固定偏好，陪我聊一句就好。")
    }else if(correctionMode)listOf(
        "我是纯虚构旅人听澜。我长期喜欢桂花乌龙，请把这个饮茶偏好记住。",
        "更正我刚才说的饮茶偏好：我其实长期喜欢茉莉白茶，桂花乌龙说错了。请停用那条旧说法，并记住更正后的偏好。")else if(mutationMode)listOf(
        "我是纯虚构旅人听澜。我长期喜欢桂花乌龙，请把这个饮茶偏好记住。",
        "更正我刚才说的饮茶偏好：我其实长期喜欢茉莉白茶，桂花乌龙说错了。请停用那条旧说法，并记住更正后的偏好。",
        "请忘掉我刚才说的茉莉白茶偏好，以后不要再引用这条喜好。原聊天保留就好。",
        "我不能吃花生，吃了会发痒。明天我打算去青榕书店借植物图鉴。先自然聊一句，不用当场记。")else if(contactMode)listOf(
        "我是虚构旅人青禾。我喜欢的茶叫雨巷桂花乌龙，请把这个茶名偏好记住。另外，请大约30秒后主动来问我杯子里的茶凉了没有；这次问过就不再续约。",
        "我刚才说喜欢喝哪一种茶？别只靠这会儿的聊天，翻翻你保存的那条记忆再告诉我。",
        "那请读取现在还有效的角色记忆，确认我喜欢的茶名，照记忆原文复述给我。").take(if(contactOnly)1 else 3)else if(supplement)listOf(
        "我在这个纯虚构故事里长期喜欢喝桂花乌龙，请把这条饮茶偏好保存到记忆里。",
        "这轮是回想工具验收：请先实际调用 agent_recall_search 检索“桂花乌龙”，依据工具命中的记忆回复。即使你从上下文已知道，也请真正执行这次检索；查不到就如实说查不到。",
        "我不能喝咖啡，喝了会心慌。明天我打算去青榕书店借一本植物图鉴。")else listOf(
        "我是纯虚构测试用户叶舟。我的生日是5月20日。明天，我去看展。先不要存记忆，先自然接一句就好。",
        "现在请把我上一条提到的生日和看展计划分别记到记忆里，日期按那条原话。内容可以简洁概括，存好再告诉我。",
        "我不吃香菜，这是长期的饮食偏好，请提取这条偏好记下来，不要混入别的事情。",
        "请实际查一下我们前面的聊天原话，看看我什么时候过生日、什么时候看展，找到后用一句话告诉我。",
        "请实际从已存记忆里回想我的生日和饮食偏好，再告诉我。不要凭空猜。",
        "谢谢。现在不用查也不用记，陪我安静聊一句喝茶的事就好。")
    private val friendId get()=if(forgetDiagnosis)"fictional-forget-diagnosis-$sampleNumber"else if(normalSample)"fictional-normal-${if(contactMode)"contact"else"summary"}-$sampleNumber"else if(correctionMode)"fictional-ui-correction-fix-20261115"else if(mutationMode)"fictional-ui-mutation-20261115"else if(contactMode)"fictional-ui-contact-20261115"else"fictional-ui-memory-20261115"
    private val friendName get()=if(normalSample)"知夏${sampleNumber}号"else if(correctionMode)"时雨"else if(mutationMode)"听雨"else if(contactMode)"知夏"else"明溪"
    private val shared=ControlledGoTimeReferenceTest()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val rows=mutableListOf<JSONObject>()
    private val turns=JSONArray()
    private val samples=JSONArray()
    private val preflight=JSONArray()
    private val lock=Any()
    private val calls=AtomicInteger()
    private val inFlight=AtomicInteger()
    private val syntheticSummaryFaults=JSONArray()
    @Volatile private var injectSummaryFault=false
    private var status="incomplete"
    private var summary:JSONObject?=null
    private var contactObservation:JSONObject?=null
    private fun safeResponse(value:Any,key:String):Any=when(value){
        is JSONObject->JSONObject().apply{value.keys().forEach{k->put(k,safeResponse(value.get(k),key))}}
        is JSONArray->JSONArray().apply{for(i in 0 until value.length())put(safeResponse(value.get(i),key))}
        is String->Diagnostics.redact(value,listOf(key))
        else->value
    }
    private fun save()=synchronized(lock){
        File(shared.folder,"ui.json").writeText(JSONObject().put("phase","ui").put("status",status)
            .put("purelySynthetic",true).put("realUiRunnerChain",true).put("realChatRead",false)
            .put("normalProductDefaults",normalSample).put("forgetDiagnosis",forgetDiagnosis).put("sampleNumber",sampleNumber).put("sampleKind",if(forgetDiagnosis)"forget"else if(contactMode)"contact"else"summary")
            .put("maxUserTurns",inputs.size).put("maxMainRequestsIncludingTools",if(normalSample)JSONObject.NULL else requestLimit).put("automaticTestRetries",0).put("manualReruns",0)
            .put("requestCount",calls.get()).put("requests",JSONArray(rows)).put("turns",turns)
            .put("completedCount",turns.length()).put("capsuleSamples",samples).put("preflight",preflight).put("summary",summary).put("syntheticSummaryFaults",syntheticSummaryFaults).put("contactObservation",contactObservation).toString(2))
    }
    private fun <T> stored(block:suspend(Store)->T):T=runBlocking{
        val store=Store(context,shared.database);try{block(store)}finally{store.close()}
    }
    private fun snapshot()=stored{store->
        val repo=ProcessingRepository(store)
        JSONObject().put("memories",JSONArray(repo.memories(friendId).map{it.json()}))
            .put("candidates",JSONArray(repo.candidates(friendId).map{JSONObject(Gson().toJson(it))}))
            .put("pills",JSONArray(ChatMemoryPills.read(store.readableDatabase,friendId).map{JSONObject(Gson().toJson(it))}))
            .put("task",JSONObject(Gson().toJson(repo.memoryStatus(friendId))))
            .put("batches",store.readableDatabase.rawQuery("SELECT id,state,attempt,error FROM memory_batches WHERE friend_id=? ORDER BY rowid",arrayOf(friendId)).use{c->JSONArray().apply{while(c.moveToNext())put(JSONObject().put("id",c.getString(0)).put("state",c.getString(1)).put("attempt",c.getInt(2)).put("error",c.getString(3)))}})
            .apply{if(mutationMode||correctionMode||normalSample){
                put("summaryFailure",MemoryBatchRepository(repo).failure(friendId)?.let{JSONObject(Gson().toJson(it))})
                put("blockedSources",store.readableDatabase.rawQuery("SELECT source_id FROM companion_blocks WHERE role=? ORDER BY source_id",arrayOf(friendId)).use{c->JSONArray().apply{while(c.moveToNext())put(c.getLong(0))}})
            };if(keepIsolated){
                val companion=CompanionRepository(store)
                put("contactPolicy",companion.policy(friendId))
                put("opportunities",store.readableDatabase.rawQuery("SELECT id,state,not_before,attempt,reason,fail_kind,fail_at FROM companion_opportunities WHERE role=? ORDER BY rowid",arrayOf(friendId)).use{c->JSONArray().apply{while(c.moveToNext())put(JSONObject().put("id",c.getString(0)).put("state",c.getString(1)).put("notBefore",c.getLong(2)).put("attempt",c.getInt(3)).put("reason",c.getString(4)).put("failKind",c.getString(5)).put("failAt",if(c.isNull(6))null else c.getLong(6)))}})
                put("receipts",JSONArray(companion.receipts(friendId).map{JSONObject(Gson().toJson(it))}))
                put("receiptLinks",store.readableDatabase.rawQuery("SELECT message_id,opportunity_id,delivery FROM companion_receipts WHERE role=? AND opportunity_id IS NOT NULL",arrayOf(friendId)).use{c->JSONArray().apply{while(c.moveToNext())put(JSONObject().put("messageId",c.getLong(0)).put("opportunityId",c.getString(1)).put("delivery",c.getString(2)))}})
                put("messages",JSONArray(store.messagesFor(friendId).map{JSONObject().put("id",it.id).put("mine",it.mine).put("text",it.text).put("created",it.created)}))
                put("contactFailure",companion.latestContactFailure(friendId)?.let{JSONObject(Gson().toJson(it))})
            }}
    }
    private fun shot(name:String){
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(shared.folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private val client=object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=record(config,key,request,null)
        override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit)=record(config,key,request,onEvent)
        private suspend fun record(config:ModelConfig,key:String,request:TextChatRequest,event:(suspend(TextStreamEvent)->Unit)?):TextChatResult{
            check(config.providerId=="opencode-go"&&config.modelId=="deepseek-v4.1-flash"&&config.baseUrl=="https://opencode.ai/inference/go/openai/v1")
            if(captureEvidence){
                val d=ModelTokenPolicy.defaults(config.providerId,config.baseUrl,config.modelId)
                check(config.contextTokens==d.context&&config.outputTokens==d.chat&&config.memoryOutputTokens==d.memory)
                check(request.outputLimit==if(Diagnostics.traceState.get()?.purpose=="memory")d.memory else d.chat){"Normal purpose budget changed"}
            }else check(ConservativeTokens.tokens(Gson().toJson(request))+2048<=24000&&request.outputLimit<=4096){"Synthetic complete request size budget"}
            if(mutationMode&&injectSummaryFault&&Diagnostics.traceState.get()?.purpose=="memory"){
                val source=JSONObject(request.latestUserText());check(source.getString("character_id")==friendId)
                val batch=source.getString("batch_id")
                val op=JSONObject().put("op","ADD").put("target","N1").put("category","PREFERENCE_BOUNDARY").put("about","USER").put("text","纯合成缺id故障注入，不得保存").put("evidence",JSONArray().put("S1"))
                val raw=JSONObject().put("batch_id",batch).put("character_id",friendId).put("operations",JSONArray().put(op)).put("operation_count",1).put("complete",true).put("end_marker",batch).toString()
                synchronized(lock){check(syntheticSummaryFaults.length()<3);syntheticSummaryFaults.put(JSONObject().put("synthetic",true).put("networkOpened",false).put("fault","missing_operation_id").put("input",source).put("response",raw));save()}
                event?.invoke(TextStreamEvent.Content(raw))
                return TextChatResult.Success(raw)
            }
            val row=JSONObject().put("stream",event!=null).put("purpose",Diagnostics.traceState.get()?.purpose)
                .put("startedAt",System.currentTimeMillis()).put("input",if(request.messages.any{it.role=="user"})request.latestUserText()else"[system-only request]")
                .put("reconsider",request.messages.any{"[companion.reconsider.v1]" in it.content})
                .put("toolResults",JSONArray(request.messages.filter{it.role=="tool"}.map{JSONObject().put("name",it.name).put("callId",it.toolCallId).put("output",Diagnostics.redact(it.content,listOf(key)))}))
            if(captureEvidence)row.put("outputLimit",request.outputLimit).put("configContext",config.contextTokens).put("configChatOutput",config.outputTokens).put("configMemoryOutput",config.memoryOutputLimit()).put("traceLinks",JSONObject(Diagnostics.traceState.get()?.links.orEmpty()))
                .put("inputMessages",JSONArray(request.messages.map{m->JSONObject().put("role",m.role).put("content",Diagnostics.redact(m.content,listOf(key))).put("name",m.name).put("toolCallId",m.toolCallId).put("toolCalls",JSONArray(m.toolCalls.map{JSONObject().put("id",it.id).put("name",it.name).put("arguments",Diagnostics.redact(it.arguments,listOf(key)))}))}))
            if(forgetDiagnosis)row.put("wireEvidenceComplete",false)
            if(contactMode&&request.messages.any{it.role=="tool"&&it.name==ImmediateContact.NAME})row.put("contactStateBeforeCall",snapshot())
            if(normalSample&&row.optBoolean("reconsider"))row.put("contactStateAtReconsiderCall",snapshot())
            synchronized(lock){rows.add(row);save()}
            val real=HttpsTextChatClient(HttpsConnectionFactory{url->
                check(url.toExternalForm()=="https://opencode.ai/inference/go/openai/v1/chat/completions")
                while(true){val count=calls.get();check(count<requestLimit){"Real request budget exhausted"};if(calls.compareAndSet(count,count+1)){synchronized(lock){row.put("request",count+1);save()};break}}
                ControlledGoTimeReferenceTest.ObservedConnection(url.openConnection() as HttpsURLConnection,observeRequest=if(captureEvidence)({body:JSONObject->synchronized(lock){
                    row.put("wireRequest",JSONObject().apply{for(k in listOf("model","max_tokens","max_completion_tokens","response_format","stream"))if(body.has(k))put(k,body.get(k));if(forgetDiagnosis)for(k in listOf("tools","messages","tool_choice"))if(body.has(k))put(k,safeResponse(body.get(k),key))})
                    if(forgetDiagnosis)row.put("wireEvidenceComplete",row.getJSONObject("wireRequest").let{it.has("messages")&&it.has("tools")&&it.has("tool_choice")});save()
                }})else null){json->
                    synchronized(lock){
                        if(captureEvidence){
                            val chunks=row.optJSONArray("wireResponseChunks")?:JSONArray().also{row.put("wireResponseChunks",it)}
                            val safe=JSONObject();for(k in listOf("model","usage"))if(json.has(k))safe.put(k,json.get(k))
                            json.optJSONArray("choices")?.let{choices->safe.put("choices",JSONArray().apply{for(i in 0 until choices.length()){
                                val source=choices.getJSONObject(i);val choice=JSONObject();for(k in listOf("index","finish_reason"))if(source.has(k))choice.put(k,source.get(k))
                                for(kind in listOf("message","delta"))source.optJSONObject(kind)?.let{m->choice.put(kind,JSONObject().apply{for(k in listOf("role","content","reasoning_content","tool_calls"))if(m.has(k))put(k,m.get(k))})}
                                put(choice)
                            }})};chunks.put(safeResponse(safe,key))
                        }
                        val meta=row.optJSONObject("responseMetadata")?:JSONObject().also{row.put("responseMetadata",it)}
                        json.optString("model").takeIf{it.matches(Regex("[A-Za-z0-9._:/-]{1,160}"))&&!it.contains(key)}?.let{meta.put("model",it)}
                        json.optJSONObject("usage")?.let{u->
                            val safe=JSONObject();for(k in listOf("prompt_tokens","completion_tokens","total_tokens"))u.optLong(k,-1).takeIf{it>=0}?.let{safe.put(k,it)}
                            u.optDouble("cost",Double.NaN).takeIf{it.isFinite()&&it>=0}?.let{safe.put("cost",it)}
                            u.optJSONObject("opencode")?.let{b->val billing=JSONObject();for(k in listOf("allowance","list_cost"))b.optDouble(k,Double.NaN).takeIf{it.isFinite()&&it>=0}?.let{billing.put(k,it)};safe.put("opencode",billing)}
                            meta.put("usage",safe)
                        }
                    }
                }
            })
            val start=System.nanoTime()
            inFlight.incrementAndGet()
            val result=try{if(event==null)real.complete(config,key,request)else real.streamEvents(config,key,request){chunk->
                if(chunk is TextStreamEvent.Content)synchronized(lock){if(!row.has("firstContentAtMs"))row.put("firstContentAtMs",(System.nanoTime()-start)/1_000_000)}
                event(chunk)
            }}catch(e:kotlinx.coroutines.CancellationException){synchronized(lock){row.put("status","cancelled");save()};throw e}
            finally{inFlight.decrementAndGet();synchronized(lock){row.put("elapsedMs",(System.nanoTime()-start)/1_000_000)}}
            synchronized(lock){
                if(forgetDiagnosis)row.put("wireEvidenceComplete",row.optJSONObject("wireRequest")?.let{it.has("messages")&&it.has("tools")&&it.has("tool_choice")}==true)
                when(result){
                    is TextChatResult.Success->{row.put("status","success").put("returnedToolCalls",JSONArray(result.toolCalls.map{JSONObject().put("name",it.name).put("arguments",it.arguments)}));if(event==null)row.put("returnedStructuredText",Diagnostics.redact(result.text,listOf(key)))}
                    is TextChatResult.Failure->row.put("status","failure").put("reason",result.reason.name).put("httpStatus",result.httpStatus)
                };save()
            }
            return result
        }
    }
    @get:Rule(order=0) val isolated=object:ExternalResource(){
        private lateinit var db:String;private lateinit var vault:String;private lateinit var previous:()->TextChatClient
        private var previousDetailed=false
        private fun restore(){AppServices.databaseName=db;AppServices.vaultNamespace=vault;AppServices.createTextClient=previous;Diagnostics.detailed=previousDetailed}
        override fun before(){
            org.junit.Assume.assumeTrue("explicit controlled opt-in",InstrumentationRegistry.getArguments().getString("controlledGo")=="true")
            check(listOf(contactMode,mutationMode,correctionMode).count{it}<=1){"Conflicting controlled modes"}
            check(!normalSample||!mutationMode&&!correctionMode&&!supplement){"Normal samples cannot inject or reuse old cases"}
            db=AppServices.databaseName;vault=AppServices.vaultNamespace;previous=AppServices.createTextClient;previousDetailed=Diagnostics.detailed
            Diagnostics.detailed=false
            try{
                AppServices.databaseName=shared.database;AppServices.vaultNamespace=shared.namespace;AppServices.createTextClient={client}
                stored{store->
                    val v=ModelVault(context,shared.namespace,store)
                    val c=checkNotNull(SelectedModelTestAccess.credential(v,ModelPurpose.CHAT))
                    check(c.config.id=="synthetic-go-model")
                    val configured=if(captureEvidence){val d=ModelBudgetDraft().forModel(c.config.providerId,c.config.baseUrl,c.config.modelId);c.config.copy(contextTokens=d.context.toInt(),outputTokens=d.chat.toInt(),memoryOutputTokens=d.memory.toInt())}else c.config.copy(outputTokens=4096)
                    v.save(configured,c.key);v.setPurpose(ModelPurpose.MEMORY,c.config.id)
                    if(normalSample)preflight.put(JSONObject().put("step","newProductDefaultConfig").put("context",configured.contextTokens).put("chatOutput",configured.outputTokens).put("memoryOutput",configured.memoryOutputLimit()).put("basis","ModelBudgetDraft new UI defaults, not user custom config"))
                    store.add(listOf(Friend(friendId,friendName,"温和自然的虚构茶室角色，只用简短中文回应。本次对话和用户资料全是验收用虚构内容。")),true)
                    val rules=if(normalSample)MemoryRules()else MemoryRules(recentRounds=40,summaryRounds=inputs.size,immediateWrite=true)
                    store.writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",MemoryRules.KEY);put("value",rules.json().put("version",1).toString())},SQLiteDatabase.CONFLICT_REPLACE)
                    val code=InstalledBuild.read(context).versionCode
                    if(LocalReleaseNotes.forVersion(code)!=null)LocalUpdateRepository(store).acknowledge(code)
                    suspend fun inspect(name:String,block:suspend()->Unit){
                        val row=JSONObject().put("step",name);preflight.put(row)
                        try{block();row.put("status","passed")}catch(e:Throwable){row.put("status","failed").put("errorType",e.javaClass.simpleName).put("frames",JSONArray(e.stackTrace.take(10).map{"${it.className}.${it.methodName}:${it.lineNumber}"}));save();throw e}
                    }
                    if(InstrumentationRegistry.getArguments().getString("controlledSkipWarmup")!="true"){
                    inspect("recoverLedger"){ChatLedger(store).recoverInterrupted()}
                    inspect("continuity"){CompanionRepository(store).checkContinuity(CompanionRuntime.owner)}
                    inspect("friends"){store.friends()};inspect("fingerprints"){store.messageFingerprints()};inspect("messages"){store.messages()}
                    inspect("turns"){ChatLedger(store).all()};inspect("receipts"){CompanionRepository(store).receipts()}
                    inspect("pills"){ChatMemoryPills.read(store.readableDatabase)}
                    inspect("avatar"){RoleAssets(ProcessingRepository(store)).avatar(friendId)};inspect("persona"){store.persona()}
                    }
                    save()
                }
            }catch(e:Throwable){if(!keepIsolated)restore();throw AssertionError("Isolated fixture failed: ${e.javaClass.simpleName}")}
        }
        override fun after(){try{
            if(keepIsolated){
                val cleanup=JSONObject().put("servicesKeptIsolatedUntilHostForceStop",true)
                try{
                    stored{CompanionRepository(it).setPolicy(friendId,false,CompanionRuntime.owner)};cleanup.put("rolePaused",true)
                    val manager=WorkManager.getInstance(context)
                    manager.cancelAllWorkByTag("companion:$friendId").result.get(15,TimeUnit.SECONDS);cleanup.put("roleCancelOperationCompleted",true)
                    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
                    var settled=false
                    while(!settled&&System.nanoTime()<until){settled=inFlight.get()==0&&manager.getWorkInfosByTag("companion:$friendId").get(2,TimeUnit.SECONDS).all{it.state.isFinished};if(!settled)Thread.sleep(50)}
                    cleanup.put("requestsAndWorkStatesSettled",settled).put("inFlight",inFlight.get())
                    if(!settled)cleanup.put("cleanupFailed",true).put("reason","role_shutdown_wait_expired")
                }catch(e:Throwable){cleanup.put("errorType",e.javaClass.simpleName).put("cleanupFailed",true)}
                synchronized(lock){contactObservation=(contactObservation?:JSONObject()).put("roleCleanup",cleanup);save()}
            };save()
        }finally{if(!keepIsolated)restore() /* Isolated host force-stops before reopening cleanup; no default-DB window. */}}
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun waitReal(limit:Long,predicate:()->Boolean){
        val until=System.nanoTime()+limit*1_000_000
        while(System.nanoTime()<until){ui.mainClock.advanceTimeBy(32);Thread.sleep(32);if(predicate())return}
        error("Actual UI wait expired")
    }
    @Test fun sixChatTurnsAndManualSummary(){
        fun ledger()=stored{ChatLedger(it).all().filter{t->t.friendId==friendId}}
        var diagnosticOwned=false
        try{
            Diagnostics.configure(File(context.filesDir,"diagnostics/${shared.namespace}"),"controlled-ui-20261115");Diagnostics.detailed=false;diagnosticOwned=true
            ui.waitUntil(30000){ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("tab-0").performClick()
            ui.waitUntil(30000){ui.onAllNodesWithText(friendName).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText(friendName).performClick()
            if(contactMode){
                ui.onNodeWithTag("chat-role-detail").performClick();ui.onNodeWithTag("role-companion-open").performScrollTo().performClick()
                ui.waitUntil(10000){runCatching{ui.onNodeWithTag("companion-enabled").assertIsEnabled();true}.getOrDefault(false)}
                ui.onNodeWithTag("companion-enabled").performClick()
                ui.waitUntil(10000){stored{CompanionRepository(it).policy(friendId)}=="enabled"}
                if(ui.onAllNodesWithTag("companion-background-close").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("companion-background-close").performClick()
                val enabled=snapshot();synchronized(lock){contactObservation=JSONObject().put("enabledFromActualUi",true).put("enabledSnapshot",enabled);save()};shot("ui-contact-enabled")
                ui.runOnIdle{ViewModelProvider(ui.activity)[AppState::class.java].open("chat",friendId)}
            }
            inputs.forEachIndexed{i,input->
                if(contactMode&&i==2&&synchronized(lock){(0 until turns.length()).any{n->val a=turns.getJSONObject(n).getJSONArray("toolActivities");(0 until a.length()).any{a.getJSONObject(it).optString("tool")=="agent_memory_read"&&a.getJSONObject(it).optString("state")=="success"}}})return@forEachIndexed
                ui.mainClock.autoAdvance=true
                if(supplement&&i==2)stored{store->store.savePreference(MemoryRules.KEY,MemoryRules(recentRounds=40,summaryRounds=inputs.size,immediateWrite=false).json().put("version",2).toString())}
                if(mutationMode&&i==3)stored{store->store.savePreference(MemoryRules.KEY,MemoryRules(recentRounds=40,summaryRounds=inputs.size,immediateWrite=false).json().put("version",2).toString())}
                ui.waitUntil(30000){ui.onAllNodes(hasTestTag("message-input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("message-input").performTextInput(input)
                ui.waitUntil(30000){ui.onAllNodes(hasTestTag("send-message") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
                val priorTags=ledger().flatMap{it.toolActivities}.map{"tool-result-${it.id}"}.toSet()
                val priorTurns=ledger().map{it.id}.toSet()
                val started=System.currentTimeMillis();ui.onNodeWithTag("send-message").performClick();ui.mainClock.autoAdvance=false
                var loadingShot=false;var lastSample=""
                var firstVisible:Long?=null
                fun sample(){
                    val newTags=ui.onAllNodes(SemanticsMatcher("tool capsule"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tool-result-")==true},useUnmergedTree=true).fetchSemanticsNodes().mapNotNull{it.config.getOrNull(SemanticsProperties.TestTag)}.filter{it !in priorTags}
                    val labels=newTags.flatMap{tag->ui.onAllNodes(hasAnyAncestor(hasTestTag(tag)),useUnmergedTree=true).fetchSemanticsNodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty().map{t->t.text}}}
                    val sample=labels.joinToString("|")
                    if(sample!=lastSample){synchronized(lock){samples.put(JSONObject().put("turn",i+1).put("atMs",System.currentTimeMillis()-started).put("capsuleTags",JSONArray(newTags)).put("labels",JSONArray(labels)))};lastSample=sample}
                    if(!loadingShot&&labels.any{it in listOf("正在处理…","正在记住","让我想一想")}){shot("ui-${i+1}-loading");loadingShot=true}
                    if(firstVisible==null)ledger().lastOrNull{it.id !in priorTurns}?.assistantMessageId?.let{id->if(ui.onAllNodesWithTag("message-$id").fetchSemanticsNodes().isNotEmpty())firstVisible=System.currentTimeMillis()-started}
                }
                waitReal(240000){
                    sample()
                    val all=ledger();all.size==i+1&&all.last().state!=TurnState.PENDING
                }
                if(correctionMode)waitReal(5000){ledger().lastOrNull()?.let{t->t.state!=TurnState.PENDING&&t.toolActivities.none{it.state in setOf("running","staged")}}==true}
                val turn=ledger().last();val reply=stored{store->store.messagesFor(friendId).firstOrNull{it.id==turn.assistantMessageId}?.text.orEmpty()}
                val row=JSONObject().put("turn",i+1).put("input",input).put("reply",reply).put("state",turn.state.name)
                    .put("completedAtMs",System.currentTimeMillis()-started).put("toolActivities",JSONArray(turn.toolActivities.map{JSONObject(Gson().toJson(it))})).put("stored",snapshot())
                    .put("userMessageId",turn.userMessageId).put("sourceRevision",stored{store->store.readableDatabase.rawQuery("SELECT source_revision FROM chat_turns WHERE id=?",arrayOf(turn.id)).use{check(it.moveToFirst());it.getLong(0)}})
                synchronized(lock){turns.put(row);save()}
                val settle=System.currentTimeMillis();waitReal(2500){sample();System.currentTimeMillis()-settle>=2000}
                val uiMatches=turn.assistantMessageId?.let{id->ui.onAllNodes(hasTestTag("message-$id") and hasText(reply)).fetchSemanticsNodes().isNotEmpty()}?:false
                synchronized(lock){row.put("firstReplyNodeAtMs",firstVisible).put("uiEqualsStoredReply",uiMatches);save()}
                if(correctionMode){
                    val memories=stored{ProcessingRepository(it).memories(friendId)}
                    if(i==0){
                        val original=memories.filter{m->m.valid&&"桂花乌龙" in m.text&&m.evidence.any{e->e.side=="user"&&e.source.turnId==turn.userMessageId&&e.source.version==row.getLong("sourceRevision")}&&turn.toolActivities.any{it.tool=="agent_memory_update"&&it.state=="success"&&it.memoryId==m.id}}
                        check(original.size==1){"Original preference was not uniquely saved and linked"}
                        synchronized(lock){row.put("originalPreferenceMemoryId",original.single().id);save()}
                    }
                    if(i==1){
                        val old=turns.getJSONObject(0).getString("originalPreferenceMemoryId")
                        val control=turn.toolActivities.indexOfFirst{it.tool==JointControl.NAME&&it.title=="更正"&&it.state=="success"}
                        val write=turn.toolActivities.indexOfFirst{it.tool=="agent_memory_update"&&it.state=="success"&&memories.any{m->m.id==it.memoryId&&m.valid&&"茉莉白茶" in m.text&&m.evidence.any{e->e.side=="user"&&e.source.turnId==turn.userMessageId&&e.source.version==row.getLong("sourceRevision")}}}
                        check(control>=0&&write>control&&memories.any{it.id==old&&!it.valid}){"Actual correction, subsequent new commit or old-source withdrawal was not verified"}
                        synchronized(lock){row.put("correctionWriteVerified",true);if(forgetDiagnosis)row.put("replacementPreferenceMemoryId",turn.toolActivities[write].memoryId);save()}
                    }
                    if(forgetDiagnosis&&i==2){
                        val replacementId=turns.getJSONObject(1).getString("replacementPreferenceMemoryId")
                        val replacement=memories.singleOrNull{it.id==replacementId}
                        val directReplacement=stored{store->ProcessingRepository(store).memory(store.readableDatabase,friendId,replacementId)?.json()}
                        val targetSource=turns.getJSONObject(1).getLong("userMessageId")
                        val final=row.getJSONObject("stored")
                        val blocked=final.getJSONArray("blockedSources")
                        val messages=final.getJSONArray("messages")
                        synchronized(lock){row.put("forgetObservation",JSONObject().put("requestedOldSource",targetSource).put("currentRequestSource",turn.userMessageId)
                            .put("replacementMemoryId",replacementId).put("replacementListed",replacement!=null).put("currentTableRowPresent",directReplacement!=null)
                            .put("replacementValid",directReplacement?.optBoolean("valid")?:JSONObject.NULL).put("replacementVersion",directReplacement?.optLong("version")?:JSONObject.NULL)
                            .put("replacementCurrentRow",directReplacement?:JSONObject.NULL)
                            .put("sourceBlocked",(0 until blocked.length()).any{blocked.getLong(it)==targetSource})
                            .put("originalChatPreserved",(0 until messages.length()).any{messages.getJSONObject(it).getLong("id")==targetSource&&messages.getJSONObject(it).getString("text")==inputs[1]}));save()}
                    }
                    ui.mainClock.autoAdvance=true
                    ui.runOnIdle{
                        (ui.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0)
                        ui.activity.currentFocus?.clearFocus()
                    }
                    ui.waitForIdle()
                    val owner="thinking-toggle-message-${turn.assistantMessageId}"
                    val toggles=ui.onAllNodes(hasContentDescription("收起思考过程"),useUnmergedTree=true).fetchSemanticsNodes().mapNotNull{it.config.getOrNull(SemanticsProperties.TestTag)}.filter{it.startsWith(owner)}
                    toggles.forEach{tag->ui.onNodeWithTag("chat-history").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).performClick()}
                    turn.toolActivities.forEachIndexed{n,a->
                        val tag="tool-result-${a.id}"
                        ui.onNodeWithTag("chat-history").performScrollToNode(hasTestTag(tag));ui.onNodeWithTag(tag).assertIsDisplayed();shot("ui-${i+1}-capsule-${n+1}")
                        if(a.tool=="agent_memory_update"&&a.state=="success"&&a.memoryId!=null){
                            ui.onNodeWithTag("tool-receipt-toggle-${a.id}").performClick()
                            val expected=stored{store->ProcessingRepository(store).memory(store.readableDatabase,friendId,a.memoryId!!)?.text.orEmpty()};check(expected.isNotBlank())
                            val peekText=hasText(expected) and hasAnyAncestor(hasTestTag("memory-peek-${a.id}"))
                            waitReal(5000){ui.onAllNodes(peekText).fetchSemanticsNodes().isNotEmpty()}
                            ui.onNodeWithTag("chat-history").performScrollToNode(peekText)
                            ui.onNode(peekText).assertIsDisplayed()
                            synchronized(lock){row.put("expandedWriteText",expected).put("committedWriteCapsuleDisplayed",true);save()};shot("ui-${i+1}-memory")
                            ui.onNodeWithTag("tool-receipt-toggle-${a.id}").performClick()
                        }
                    }
                }
                shot("ui-${i+1}")
                if(supplement){
                    var expanded=false
                    for(id in turn.toolActivities.map{it.id}.distinct()){
                        val tag="recall-toggle-$id"
                        if(ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()){
                            ui.onNodeWithTag(tag).performScrollTo().performClick()
                            var loaded=emptyList<String>()
                            val expected=row.getJSONObject("stored").getJSONArray("memories").getJSONObject(0).getString("text")
                            waitReal(5000){
                                val content=ui.onAllNodes(hasAnyAncestor(hasTestTag("recall-results-$id")),useUnmergedTree=true).fetchSemanticsNodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty().map{t->t.text}}
                                val ready=content.any{it.isNotBlank()&&expected in it}&&"正在读取…" !in content
                                if(ready)loaded=content
                                ready
                            }
                            expanded=true;synchronized(lock){row.put("expandedRecallText",JSONArray(loaded));save()};shot("ui-${i+1}-recall-expanded")
                            ui.onNodeWithTag(tag).performClick()
                        }
                    }
                    if(i==1)check(expanded){"Recall expansion was not observed"}
                }
                check(turn.state==TurnState.COMPLETED){"Actual UI turn failed"}
            }
            if(correctionMode){
                val final=snapshot();synchronized(lock){contactObservation=(contactObservation?:JSONObject()).put("correctionFinalSnapshot",final);save()}
            }else if(contactMode){
                ui.mainClock.autoAdvance=true
                ui.runOnIdle{ViewModelProvider(ui.activity)[AppState::class.java].home()}
                val before=snapshot()
                val first=turns.getJSONObject(0);val expectedId="$friendId:${first.getLong("userMessageId")}:${first.getLong("sourceRevision")}"
                val evidence=synchronized(lock){rows.firstOrNull{r->r.optString("input")==inputs.first()&&r.optJSONArray("toolResults")?.let{a->(0 until a.length()).any{n->val t=a.getJSONObject(n);t.optString("name")==ImmediateContact.NAME&&runCatching{JSONObject(t.getString("output")).optBoolean("success")}.getOrDefault(false)}}==true}?.let{JSONObject(it.toString())}}
                val initial=evidence?.optJSONObject("contactStateBeforeCall")?.optJSONArray("opportunities")
                val original=(0 until (initial?.length()?:0)).map{initial!!.getJSONObject(it)}.firstOrNull{it.getString("id")==expectedId}
                var confirmedProactiveId:Long?=null;var confirmedProactiveText:String?=null
                synchronized(lock){contactObservation=(contactObservation?:JSONObject()).put("beforeWait",before).put("waitStartedAt",System.currentTimeMillis()).put("expectedIdFromRealSource",expectedId).put("successToolEvidence",evidence).put("originalOpportunity",original);save()}
                if(original!=null){
                    val initialDue=mapOf(expectedId to original.getLong("notBefore"))
                    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(if(normalSample)420 else 170)
                    var ended=false
                    while(!ended&&System.nanoTime()<until){
                        Thread.sleep(250)
                        val state=snapshot();synchronized(lock){contactObservation!!.put("afterWait",state).put("observedAt",System.currentTimeMillis());save()}
                        val opportunities=state.getJSONArray("opportunities")
                        val links=state.getJSONArray("receiptLinks")
                        val messages=state.getJSONArray("messages")
                        val delivered=(0 until links.length()).map{links.getJSONObject(it)}.firstNotNullOfOrNull{link->if(link.getString("opportunityId") !in initialDue)null else (0 until messages.length()).map{messages.getJSONObject(it)}.firstOrNull{message->message.getLong("id")==link.getLong("messageId")&&!message.getBoolean("mine")&&message.getString("text").isNotBlank()}}
                        if(delivered!=null){confirmedProactiveId=delivered.getLong("id");confirmedProactiveText=delivered.getString("text");synchronized(lock){contactObservation!!.put("confirmedProactiveMessageId",confirmedProactiveId).put("confirmedProactiveText",confirmedProactiveText);save()}}
                        val terminal=if(contactOnly)setOf("silent","cancel","cancelled","failed")else setOf("send","silent","cancel","cancelled","failed")
                        val deferCandidate=synchronized(lock){rows.lastOrNull{it.optBoolean("reconsider")&&it.optString("status")=="success"}?.optString("returnedStructuredText")?.let{runCatching{JSONObject(it).optString("action")=="defer"}.getOrDefault(false)}==true}
                        val dueDecision=normalSample&&deferCandidate&&(0 until opportunities.length()).any{n->val o=opportunities.getJSONObject(n);o.getString("id")==expectedId&&o.optInt("attempt")>0&&o.optString("failKind").isBlank()&&o.getString("state")=="queued"&&o.getLong("notBefore")!=initialDue[expectedId]}
                        if(dueDecision)synchronized(lock){contactObservation!!.put("deferredAfterDue",true);save()}
                        ended=dueDecision||delivered!=null||(0 until opportunities.length()).any{n->val o=opportunities.getJSONObject(n);o.getString("id") in initialDue&&(o.getString("state") in terminal||!contactOnly&&o.getString("state")=="queued"&&o.getLong("notBefore")!=initialDue[o.getString("id")])}
                    }
                    synchronized(lock){contactObservation!!.put("observedStateChange",ended);save()}
                }else synchronized(lock){contactObservation!!.put("firstArrangementNotConfirmed",true);save()}
                shot("ui-contact-after-wait")
                ui.runOnIdle{ViewModelProvider(ui.activity)[AppState::class.java].open("chat",friendId)}
                if(contactOnly){
                    ui.runOnIdle{(ui.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(ui.activity.window.decorView.windowToken,0);ui.activity.currentFocus?.clearFocus()}
                    if(confirmedProactiveId!=null){
                        val id=confirmedProactiveId!!;val expected=checkNotNull(confirmedProactiveText)
                        ui.waitUntil(10000){ViewModelProvider(ui.activity)[AppState::class.java].messages.any{it.id==id&&!it.mine&&it.text==expected}}
                        ui.onNodeWithTag("chat-history").performScrollToNode(hasTestTag("message-$id"));ui.onNode(hasTestTag("message-$id") and hasText(expected)).assertIsDisplayed()
                        synchronized(lock){contactObservation!!.put("displayedProactiveMessageId",id);save()}
                    }
                }
                ui.waitForIdle();shot("ui-contact-final-chat")
            }else{
            ui.mainClock.autoAdvance=true
            ui.onNodeWithTag("chat-role-detail").performClick();ui.onNodeWithTag("role-processing-open").performScrollTo().performClick()
            ui.onNodeWithText("更多").performClick();ui.onNodeWithTag("memory-maintenance").performClick()
            val before=snapshot();check(before.getJSONArray("batches").length()==0){"Unexpected earlier summary; cannot attribute this UI click"}
            if(mutationMode){
                synchronized(lock){summary=JSONObject().put("syntheticFailurePhase",true).put("before",before);save()}
                injectSummaryFault=true
                try{
                    ui.onNodeWithTag("memory-retry").performClick();ui.mainClock.autoAdvance=false
                    waitReal(30000){val s=snapshot();val batches=s.getJSONArray("batches");synchronized(lock){syntheticSummaryFaults.length()==3}&&batches.length()>0&&batches.getJSONObject(batches.length()-1).getString("state")=="FAILED"&&s.optJSONObject("summaryFailure")?.optInt("attempts")==3&&!stored{friendId in it.memorySummary.roles.value}}
                }finally{injectSummaryFault=false;ui.mainClock.autoAdvance=true}
                val failed=snapshot();val unchanged=before.getJSONArray("memories").toString()==failed.getJSONArray("memories").toString()
                val failure=failed.getJSONObject("summaryFailure");check(failure.getString("code")=="FORMAT"&&"缺少必填字段 id" in failure.getString("detail")){"Unexpected synthetic validation reason"}
                ui.waitUntil(10000){ui.onAllNodesWithTag("memory-batch-failure").fetchSemanticsNodes().any{n->n.config.getOrNull(SemanticsProperties.Text).orEmpty().any{"缺少必填字段 id" in it.text}}}
                ui.onNodeWithTag("memory-batch-failure").assertIsDisplayed()
                val detailed=ui.onNodeWithTag("memory-batch-failure").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString{it.text}
                val shown=ui.onNodeWithTag("memory-task-status").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString{it.text}
                check(shown==processingMessage("FORMAT")){"Failure task text was not shown"}
                synchronized(lock){summary!!.put("syntheticFailure",failed).put("memoriesUnchangedAfterSyntheticFailure",unchanged).put("failureUiText",shown).put("detailedFailureUiText",detailed);save()}
                shot("ui-summary-synthetic-failed");check(unchanged){"Synthetic invalid summary changed memories"}
                ui.waitUntil(10000){runCatching{ui.onNodeWithTag("memory-retry").assertIsEnabled();true}.getOrDefault(false)}
            }
            val realBaseline=calls.get()
            val retryBatch=if(mutationMode)summary!!.getJSONObject("syntheticFailure").getJSONArray("batches").getJSONObject(0).getString("id")else null
            synchronized(lock){if(mutationMode)summary!!.put("manualRetryRealRequestBaseline",realBaseline).put("manualRetryBatchId",retryBatch).put("manualRetryClickedAt",System.currentTimeMillis());save()}
            if(normalSample)synchronized(lock){summary=JSONObject().put("firstTriggerNewBatch",true).put("manualReruns",0).put("before",before).put("triggerRequestBaseline",realBaseline).put("configuredRules",MemoryRules().json());save()}
            ui.onNodeWithTag("memory-retry").performClick();ui.mainClock.autoAdvance=false
            val start=System.currentTimeMillis()
            waitReal(330000){
                val batches=snapshot().getJSONArray("batches")
                val actualRetry= !mutationMode||synchronized(lock){rows.any{r->r.optInt("request")>realBaseline&&r.optString("purpose")=="memory"&&r.optString("status") in setOf("success","failure")&&runCatching{JSONObject(r.getString("input")).getString("batch_id")==retryBatch}.getOrDefault(false)}}&&!stored{friendId in it.memorySummary.roles.value}
                batches.length()>0&&batches.getJSONObject(batches.length()-1).getString("state") in setOf("COMMITTED","COMPLETED","FAILED")&&actualRetry&&(!normalSample||!stored{friendId in it.memorySummary.roles.value})
            }
            val after=snapshot()
            synchronized(lock){summary=(summary?:JSONObject()).put("triggeredFromUi",true).put("elapsedMs",System.currentTimeMillis()-start).put("before",before).put("after",after);save()}
            if(normalSample){
                ui.mainClock.autoAdvance=true;ui.waitForIdle()
                waitReal(10000){ui.onAllNodesWithTag("memory-task-status").fetchSemanticsNodes().any{n->n.config.getOrNull(SemanticsProperties.Text).orEmpty().none{"正在" in it.text}}}
                val shown=ui.onNodeWithTag("memory-task-status").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString{it.text}
                synchronized(lock){summary!!.put("finalTaskUiText",shown);save()}
            }
            shot("ui-summary");save()
            }
            status="completed_pending_independent_review"
        }catch(e:Throwable){status="failed";shot("ui-failed");throw AssertionError("Actual UI validation failed: ${e.javaClass.simpleName}")}
        finally{
            ui.mainClock.autoAdvance=true
            if(diagnosticOwned)runBlocking{File(shared.folder,"diagnostics.jsonl").writeBytes(Diagnostics.export(captureEvidence))};save()
        }
    }
}
