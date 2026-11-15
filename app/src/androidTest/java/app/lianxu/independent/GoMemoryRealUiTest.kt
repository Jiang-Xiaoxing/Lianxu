package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/** Opt-in actual UI and production runner/tools/SQLite; only the data/vault and observer are isolated. */
class GoMemoryRealUiTest {
    private val supplement get()=InstrumentationRegistry.getArguments().getString("controlledSupplement")=="true"
    private val inputs get()=if(supplement)listOf(
        "我在这个纯虚构故事里长期喜欢喝桂花乌龙，请把这条饮茶偏好保存到记忆里。",
        "这轮是回想工具验收：请先实际调用 agent_recall_search 检索“桂花乌龙”，依据工具命中的记忆回复。即使你从上下文已知道，也请真正执行这次检索；查不到就如实说查不到。",
        "我不能喝咖啡，喝了会心慌。明天我打算去青榕书店借一本植物图鉴。")else listOf(
        "我是纯虚构测试用户叶舟。我的生日是5月20日。明天，我去看展。先不要存记忆，先自然接一句就好。",
        "现在请把我上一条提到的生日和看展计划分别记到记忆里，日期按那条原话。内容可以简洁概括，存好再告诉我。",
        "我不吃香菜，这是长期的饮食偏好，请提取这条偏好记下来，不要混入别的事情。",
        "请实际查一下我们前面的聊天原话，看看我什么时候过生日、什么时候看展，找到后用一句话告诉我。",
        "请实际从已存记忆里回想我的生日和饮食偏好，再告诉我。不要凭空猜。",
        "谢谢。现在不用查也不用记，陪我安静聊一句喝茶的事就好。")
    private val friendId="fictional-ui-memory-20261115"
    private val shared=ControlledGoTimeReferenceTest()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val rows=mutableListOf<JSONObject>()
    private val turns=JSONArray()
    private val samples=JSONArray()
    private val preflight=JSONArray()
    private val lock=Any()
    private val calls=AtomicInteger()
    private var status="incomplete"
    private var summary:JSONObject?=null
    private fun save()=synchronized(lock){
        File(shared.folder,"ui.json").writeText(JSONObject().put("phase","ui").put("status",status)
            .put("purelySynthetic",true).put("realUiRunnerChain",true).put("realChatRead",false)
            .put("maxUserTurns",inputs.size).put("maxMainRequestsIncludingTools",30).put("automaticTestRetries",0)
            .put("requestCount",calls.get()).put("requests",JSONArray(rows)).put("turns",turns)
            .put("completedCount",turns.length()).put("capsuleSamples",samples).put("preflight",preflight).put("summary",summary).toString(2))
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
            check(ConservativeTokens.tokens(Gson().toJson(request))+2048<=24000&&request.outputLimit<=4096){"Synthetic complete request size budget"}
            val row=JSONObject().put("stream",event!=null).put("purpose",Diagnostics.traceState.get()?.purpose)
                .put("startedAt",System.currentTimeMillis()).put("input",request.latestUserText())
            synchronized(lock){rows.add(row);save()}
            val real=HttpsTextChatClient(HttpsConnectionFactory{url->
                check(url.toExternalForm()=="https://opencode.ai/inference/go/openai/v1/chat/completions")
                while(true){val count=calls.get();check(count<30){"Real request budget exhausted"};if(calls.compareAndSet(count,count+1)){synchronized(lock){row.put("request",count+1);save()};break}}
                ControlledGoTimeReferenceTest.ObservedConnection(url.openConnection() as HttpsURLConnection){json->
                    synchronized(lock){
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
            val result=try{if(event==null)real.complete(config,key,request)else real.streamEvents(config,key,request){chunk->
                if(chunk is TextStreamEvent.Content)synchronized(lock){if(!row.has("firstContentAtMs"))row.put("firstContentAtMs",(System.nanoTime()-start)/1_000_000)}
                event(chunk)
            }}finally{synchronized(lock){row.put("elapsedMs",(System.nanoTime()-start)/1_000_000)}}
            synchronized(lock){
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
            db=AppServices.databaseName;vault=AppServices.vaultNamespace;previous=AppServices.createTextClient;previousDetailed=Diagnostics.detailed
            Diagnostics.detailed=false
            try{
                AppServices.databaseName=shared.database;AppServices.vaultNamespace=shared.namespace;AppServices.createTextClient={client}
                stored{store->
                    val v=ModelVault(context,shared.namespace,store)
                    val c=checkNotNull(SelectedModelTestAccess.credential(v,ModelPurpose.CHAT))
                    check(c.config.id=="synthetic-go-model")
                    v.save(c.config.copy(outputTokens=4096),c.key);v.setPurpose(ModelPurpose.MEMORY,c.config.id)
                    store.add(listOf(Friend(friendId,"明溪","温和自然的虚构茶室角色，只用简短中文回应。本次对话和用户资料全是验收用虚构内容。")),true)
                    val rules=MemoryRules(recentRounds=40,summaryRounds=inputs.size,immediateWrite=true)
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
            }catch(e:Throwable){restore();throw AssertionError("Isolated fixture failed: ${e.javaClass.simpleName}")}
        }
        override fun after(){try{save()}finally{restore()}}
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
            ui.waitUntil(30000){ui.onAllNodesWithText("明溪").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("明溪").performClick()
            inputs.forEachIndexed{i,input->
                ui.mainClock.autoAdvance=true
                if(supplement&&i==2)stored{store->store.savePreference(MemoryRules.KEY,MemoryRules(recentRounds=40,summaryRounds=inputs.size,immediateWrite=false).json().put("version",2).toString())}
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
                    if(!loadingShot&&labels.any{it in listOf("正在处理…","让我想一想")}){shot("ui-${i+1}-loading");loadingShot=true}
                    if(firstVisible==null)ledger().lastOrNull{it.id !in priorTurns}?.assistantMessageId?.let{id->if(ui.onAllNodesWithTag("message-$id").fetchSemanticsNodes().isNotEmpty())firstVisible=System.currentTimeMillis()-started}
                }
                waitReal(240000){
                    sample()
                    val all=ledger();all.size==i+1&&all.last().state!=TurnState.PENDING
                }
                val turn=ledger().last();val reply=stored{store->store.messagesFor(friendId).firstOrNull{it.id==turn.assistantMessageId}?.text.orEmpty()}
                val row=JSONObject().put("turn",i+1).put("input",input).put("reply",reply).put("state",turn.state.name)
                    .put("completedAtMs",System.currentTimeMillis()-started).put("toolActivities",JSONArray(turn.toolActivities.map{JSONObject(Gson().toJson(it))})).put("stored",snapshot())
                synchronized(lock){turns.put(row);save()}
                val settle=System.currentTimeMillis();waitReal(2500){sample();System.currentTimeMillis()-settle>=2000}
                row.put("firstReplyNodeAtMs",firstVisible)
                row.put("uiEqualsStoredReply",turn.assistantMessageId?.let{id->ui.onAllNodes(hasTestTag("message-$id") and hasText(reply)).fetchSemanticsNodes().isNotEmpty()}?:false)
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
                            expanded=true;row.put("expandedRecallText",JSONArray(loaded));shot("ui-${i+1}-recall-expanded")
                            ui.onNodeWithTag(tag).performClick()
                        }
                    }
                    if(i==1)check(expanded){"Recall expansion was not observed"}
                }
                check(turn.state==TurnState.COMPLETED){"Actual UI turn failed"}
            }
            ui.mainClock.autoAdvance=true
            ui.onNodeWithTag("chat-role-detail").performClick();ui.onNodeWithTag("role-processing-open").performScrollTo().performClick()
            ui.onNodeWithText("更多").performClick();ui.onNodeWithTag("memory-maintenance").performClick()
            val before=snapshot();check(before.getJSONArray("batches").length()==0){"Unexpected earlier summary; cannot attribute this UI click"}
            ui.onNodeWithTag("memory-retry").performClick();ui.mainClock.autoAdvance=false
            val start=System.currentTimeMillis()
            waitReal(330000){
                val batches=snapshot().getJSONArray("batches")
                batches.length()>0&&batches.getJSONObject(batches.length()-1).getString("state") in setOf("COMMITTED","COMPLETED","FAILED")
            }
            summary=JSONObject().put("triggeredFromUi",true).put("elapsedMs",System.currentTimeMillis()-start).put("before",before).put("after",snapshot())
            shot("ui-summary");save()
            status="completed_pending_independent_review"
        }catch(e:Throwable){status="failed";shot("ui-failed");throw AssertionError("Actual UI validation failed: ${e.javaClass.simpleName}")}
        finally{
            ui.mainClock.autoAdvance=true
            if(diagnosticOwned)runBlocking{File(shared.folder,"diagnostics.jsonl").writeBytes(Diagnostics.export(false))};save()
        }
    }
}
