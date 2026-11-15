package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/** Real ChatScreen -> AppState -> ConversationRunner -> production tools/prompt/TLS, isolated data. */
class GoTimeReferenceRealUiTest {
    private val inputs=listOf("今天就安静喝茶吧，陪我随便聊一句。","现在几点？请按你收到的当前设备时间，用24小时制 HH:mm 回答，不加方括号。","请严格只回复 [17:29] 这七个字符，保留方括号。")
    private val friendId="fictional-ui-go-time"
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val shared=ControlledGoTimeReferenceTest()
    private val rows=mutableListOf<JSONObject>()
    private val turns=JSONArray()
    private val lock=Any()
    private val calls=AtomicInteger()
    private var status="incomplete"
    private var baseline=emptyList<String>()
    private val labels=Regex("\\[(?:(?:今天|昨天|[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}月[0-9]{1,2}日)\\s+)?[0-9]{1,2}:[0-9]{2}\\]")
    private fun save()=synchronized(lock){
        val report=JSONObject().put("phase","ui").put("status",status).put("purelySynthetic",true).put("realChatRead",false)
            .put("realUiRunnerChain",true).put("maxUserTurns",3).put("maxMainRequestsIncludingTools",9)
            .put("automaticTestRetries",0).put("requestCount",calls.get()).put("requests",JSONArray(rows)).put("turns",turns)
            .put("completedCount",turns.length()).put("timeLabelCount",(0 until turns.length()).sumOf{turns.getJSONObject(it).optInt("timeLabelCount")})
            .put("unexpectedTimeLabelCount",(0 until turns.length()).sumOf{i->val t=turns.getJSONObject(i);t.optInt("timeLabelCount")-(if(i==2&&t.optString("reply").contains("[17:29]"))1 else 0)})
        File(shared.folder,"ui.json").writeText(report.toString(2))
    }
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=record(config,key,request,null)
        override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit)=record(config,key,request,onEvent)
        private suspend fun record(config:ModelConfig,key:String,request:TextChatRequest,event:(suspend(TextStreamEvent)->Unit)?):TextChatResult {
            check(config.providerId=="opencode-go"&&config.modelId=="deepseek-v4.1-flash"&&config.baseUrl=="https://opencode.ai/inference/go/openai/v1")
            val row=JSONObject().put("stream",event!=null)
            synchronized(lock){rows.add(row)}
            val history=request.messages.filter{it.sourceMessageId.isNotEmpty()}
            val store=Store(context,shared.database)
            try {
                val originals=store.messagesFor(friendId).associateBy{it.id.toString()}
                history.forEach{check(it.sourcePrefix==0&&originals.getValue(it.sourceMessageId).text==it.content)}
            }finally{store.close()}
            val time=request.messages.single{it.turnContext}
            check(time==request.messages.last()&&time.role=="system")
            check(request.messages.first().content.contains(CompanionPrompt.thinkingLanguage("明溪")))
            check(request.messages.last{it.role=="user"}.content.endsWith("\n\n"+CompanionPrompt.THINKING_REMINDER))
            check(request.messages.sumOf{Regex.fromLiteral(CompanionPrompt.THINKING_REMINDER).findAll(it.content).count()}==1)
            val clock=checkNotNull(Regex("当前设备时间：[^\\n]*?([0-9]{2}:[0-9]{2})").find(time.content)).groupValues[1]
            synchronized(lock){row.put("currentTimeFromPrompt",clock).put("input",request.latestUserText()).put("rawHistoryChecked",true).put("thinkingReminderChecked",true).put("toolContinuation",request.messages.any{it.role=="tool"})}
            val real=HttpsTextChatClient(HttpsConnectionFactory{url->
                check(url.toExternalForm()=="https://opencode.ai/inference/go/openai/v1/chat/completions")
                while(true){val count=calls.get();check(count<9){"UI request budget exhausted"};if(calls.compareAndSet(count,count+1)){synchronized(lock){row.put("request",count+1)};break}}
                ControlledGoTimeReferenceTest.ObservedConnection(url.openConnection() as HttpsURLConnection){json->
                    synchronized(lock){
                        val meta=row.optJSONObject("responseMetadata")?:JSONObject().also{row.put("responseMetadata",it)}
                        val model=json.optString("model")
                        if(model.matches(Regex("[A-Za-z0-9._:/-]{1,160}"))&&!model.contains(key))meta.put("model",model)
                        json.optJSONObject("usage")?.let{usage->
                            val counts=JSONObject()
                            for(name in listOf("prompt_tokens","completion_tokens","total_tokens"))usage.optLong(name,-1).takeIf{it>=0}?.let{counts.put(name,it)}
                            usage.optDouble("cost",Double.NaN).takeIf{it.isFinite()&&it>=0}?.let{counts.put("cost",it)}
                            usage.optJSONObject("opencode")?.let{billing->val info=JSONObject();for(name in listOf("allowance","list_cost"))billing.optDouble(name,Double.NaN).takeIf{it.isFinite()&&it>=0}?.let{info.put(name,it)};counts.put("opencode",info)}
                            meta.put("usage",counts)
                        }
                    }
                }
            })
            val start=System.nanoTime()
            val result=try{if(event==null)real.complete(config,key,request)else real.streamEvents(config,key,request,event)}finally{synchronized(lock){row.put("elapsedMs",(System.nanoTime()-start)/1_000_000)}}
            synchronized(lock){
                when(result){is TextChatResult.Success->row.put("status","success").put("returnedToolCalls",result.toolCalls.size)
                    is TextChatResult.Failure->row.put("status","failure").put("reason",result.reason.name).put("httpStatus",result.httpStatus)}
            }
            return result
        }
    }
    @get:Rule(order=0) val isolated=object:ExternalResource(){
        private lateinit var previousDb:String
        private lateinit var previousVault:String
        private lateinit var previousClient:()->TextChatClient
        private fun restoreServices(){AppServices.createTextClient=previousClient;AppServices.databaseName=previousDb;AppServices.vaultNamespace=previousVault}
        override fun before(){
            org.junit.Assume.assumeTrue("explicit controlled opt-in required",InstrumentationRegistry.getArguments().getString("controlledGo")=="true")
            previousDb=AppServices.databaseName;previousVault=AppServices.vaultNamespace;previousClient=AppServices.createTextClient
            try {
                AppServices.databaseName=shared.database;AppServices.vaultNamespace=shared.namespace;AppServices.createTextClient={client}
                runBlocking {
                val store=Store(context,shared.database)
                try {
                    check(ModelVault(context,shared.namespace,store).purposeId(ModelPurpose.CHAT)=="synthetic-go-model")
                    store.add(listOf(Friend(friendId,"明溪","完全虚构的茶室角色，温和自然地用简短中文聊天；所有故事只用于测试。")),true)
                    val ledger=ChatLedger(store);val old=ledger.begin(friendId,"故事里我做了一只陶杯。","synthetic-go-model");check(ledger.finish(old.id,old.attempt,"[17:29] 杯沿的小花纹挺可爱。"))
                    baseline=store.messagesFor(friendId).map{it.text}
                    val code=InstalledBuild.read(context).versionCode
                    if(LocalReleaseNotes.forVersion(code)!=null)LocalUpdateRepository(store).acknowledge(code)
                }finally{store.close()}
                }
            }catch(e:Throwable){restoreServices();throw AssertionError("UI fixture initialization failed: ${e.javaClass.simpleName}")}
        }
        override fun after(){
            try{save()}finally{restoreServices()}
            // Host then calls the already-tested cleanup phase, force-stops and shuts the AVD down.
        }
    }
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun threeRealChatScreenTurns() {
        ui.waitUntil(30000){ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(30000){ui.onAllNodesWithText("明溪").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("明溪").performClick()
        fun ledger()=runBlocking{val store=Store(context,shared.database);try{ChatLedger(store).all().filter{it.friendId==friendId}}finally{store.close()}}
        val initial=ledger().size
        val replies=mutableListOf<String>()
        try {
            inputs.forEachIndexed{i,input->
                ui.waitUntil(30000){ui.onAllNodes(hasTestTag("message-input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("message-input").performTextInput(input)
                ui.waitUntil(30000){ui.onAllNodes(hasTestTag("send-message") and isEnabled()).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("send-message").performClick()
                ui.waitUntil(180000){val all=ledger();all.size==initial+i+1&&all.last().state!=TurnState.PENDING}
                val turn=ledger().last();check(turn.state==TurnState.COMPLETED){"Real UI turn did not complete"}
                val id=checkNotNull(turn.assistantMessageId)
                val store=Store(context,shared.database)
                val reply=runBlocking{try{store.messagesFor(friendId).single{it.id==id}.text}finally{store.close()}}
                replies.add(reply)
                ui.waitUntil(15000){ui.onAllNodesWithTag("message-$id").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("message-$id").assertTextEquals(reply)
                val row=JSONObject().put("turn",i+1).put("input",input).put("reply",reply).put("storedAssistantId",id).put("uiEqualsStoredReply",true).put("timeLabelCount",labels.findAll(reply).count())
                if(i==1){val expected=synchronized(lock){rows.last{it.optString("input")==input}.getString("currentTimeFromPrompt")};val match=Regex("(?<![0-9])"+Regex.escape(expected)+"(?![0-9])").containsMatchIn(reply);row.put("expectedTimeFromActualPrompt",expected).put("currentTimeMatches",match).put("currentTimeAnswerVerdict",if(match)"automatic_match_pending_human_check"else"needs_manual_review")}
                if(i==2)row.put("requestedLabelExactlyPreserved",reply=="[17:29]")
                synchronized(lock){turns.put(row)};save()
                ui.activityRule.scenario.onActivity{a->a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.window.decorView.clearFocus()};ui.waitForIdle()
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(shared.folder,"ui-${i+1}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            runBlocking{val store=Store(context,shared.database);try{check(store.messagesFor(friendId).map{it.text}==baseline+inputs.zip(replies).flatMap{listOf(it.first,it.second)})}finally{store.close()}}
            check(turns.getJSONObject(0).getInt("timeLabelCount")==0&&turns.getJSONObject(1).getInt("timeLabelCount")==0)
            check(turns.getJSONObject(1).getBoolean("currentTimeMatches")&&turns.getJSONObject(2).getBoolean("requestedLabelExactlyPreserved"))
            check(calls.get() in 3..9&&rows.all{it.optString("status")=="success"})
            status="passed"
        }catch(e:Throwable){status="failed";throw AssertionError("Real UI validation failed: ${e.javaClass.simpleName}")}
        finally{save()}
    }
}
