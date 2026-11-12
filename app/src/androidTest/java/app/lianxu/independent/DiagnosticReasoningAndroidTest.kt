package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/**
 * 一次实际对话的导出验证：详细模式下，两段思考、一次工具调用和正式回复都能按轮对回同一轮，
 * 顺序与用户看到的一致；关闭详细时只留状态与字数，不落思考原文；失败时屏幕上出现过的思考仍要保留。
 */
class DiagnosticReasoningAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault
    private lateinit var name:String;private lateinit var scope:CoroutineScope
    private lateinit var dir:File;private lateinit var journal:DiagnosticJournal

    @Before fun prepare()=runBlocking {
        name="diag-reasoning-${System.nanoTime()}"
        store=Store(context,"$name.db");repo=ProcessingRepository(store);vault=ModelVault(context,name)
        store.add(listOf(Friend("a","合成甲","测试")),false)
        vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid/v1","fake-model",64000,4096),"synthetic-only")
        vault.setPurpose(ModelPurpose.CHAT,"fake");scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        dir=File(context.cacheDir,"diag-reasoning-${System.nanoTime()}");journal=DiagnosticJournal(dir,"test")
        Diagnostics.installForTest(journal);Diagnostics.detailed=true
    }
    @After fun close(){runBlocking{scope.cancel();scope.coroutineContext[kotlinx.coroutines.Job]?.join()};Diagnostics.installForTest(null);journal.close();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");dir.deleteRecursively()}

    private fun runner(client:TextChatClient):ConversationRunner{
        val logged=LoggedTextChatClient(client)
        return ConversationRunner(scope,store,vault,logged,repo,MemoryEngine(scope,repo,ModelAccess(vault),logged,{}),{},{})
    }
    private suspend fun awaitTurn(before:Set<String>):ChatTurn=withTimeout(10000){
        while(true){val t=ChatLedger(store).all().lastOrNull{it.id !in before};if(t!=null&&t.state!=TurnState.PENDING)return@withTimeout t;delay(10)}
        error("unreachable")
    }
    private fun rows(bytes:ByteArray)=bytes.toString(Charsets.UTF_8).lineSequence()
        .mapNotNull{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}.toList()
    private fun JsonObject.data():JsonObject=getAsJsonObject("data")?:JsonObject()
    private fun JsonObject.str(key:String):String?=get(key)?.takeIf{!it.isJsonNull}?.asString
    private fun JsonObject.ev()=str("event").orEmpty()

    /** One turn with a real history search: thinking, tool receipt, then thinking and plain reply. */
    private suspend fun oneTurn(thinking:Pair<String,String>):ChatTurn {
        var step=0
        val raw=object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
            override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                syntheticCompanionStage(request)?.let{return it}
                return when(step++){
                    0->{assertTrue("agent_history_search" in request.tools.toString());onEvent(TextStreamEvent.Reasoning(thinking.first));TextChatResult.Success("",listOf(ChatToolCall("c1","agent_history_search","""{"query":"合成旧事"}""")),displayReasoning=thinking.first)}
                    else->{onEvent(TextStreamEvent.Reasoning(thinking.second));onEvent(TextStreamEvent.Content("好呀"));TextChatResult.Success("好呀",displayReasoning=thinking.second)}
                }
            }
        }
        val runner=runner(raw)
        val before=ChatLedger(store).all().map{it.id}.toSet()
        assertTrue(runner.send("a","你好"){})
        return awaitTurn(before)
    }

    @Test fun oneConversationExportsOrderedReasoningAroundTheToolCall():Unit=runBlocking {
        val firstThought="先找找我们聊过的事";val secondThought="查过了，简单回一句"
        val turn=oneTurn(firstThought to secondThought)
        assertEquals("turn ended as ${turn.failure}",TurnState.COMPLETED,turn.state)
        // The turn's own result event is written just after the commit; give the journal a beat.
        delay(500)
        val rows=rows(journal.export(true))
        assertTrue("日志不能空",rows.isNotEmpty())
        // 1. 每条思考都能对回同一轮、同一次请求，而不是靠全文搜索碰巧命中。
        val batches=rows.filter{it.ev()=="model.batch.start"&&it.data().str("turn")==turn.id}
        val batchSpans=batches.mapNotNull{it.data().str("span")}.toSet()
        assertEquals("两次请求各应有一个批次",2,batchSpans.size)
        val responses=rows.filter{it.ev()=="network.response"}.map{it.data()}
        val reasoned=responses.filter{it.str("reasoning_text").let{t->t==firstThought||t==secondThought}}
        assertEquals("两段思考各应有一条响应记录",2,reasoned.size)
        reasoned.forEach{data->
            assertTrue("响应要挂在同一轮的某个批次下",data.str("parent_span") in batchSpans)
            assertFalse("每条响应都要有自己的请求编号",data.str("request_id").isNullOrBlank())
            assertEquals("思考状态应为 returned","returned",data.str("reasoning_status"))
            assertEquals("字数要与原文一致",data.str("reasoning_text")!!.length,data.str("reasoning_chars")?.toInt())
        }
        assertEquals("两段思考应分属两次请求",2,reasoned.mapNotNull{it.str("request_id")}.distinct().size)
        // 2. 顺序：第一段思考 → 工具调用 → 第二段思考 → 正式回复。
        fun indexOfText(text:String)=rows.indexOfFirst{it.data().str("reasoning_text")==text}
        val tool=rows.indexOfFirst{it.ev()=="tool.call"&&it.data().str("turn")==turn.id}
        assertTrue("第一段思考应在工具调用之前",indexOfText(firstThought) in 0 until tool)
        assertTrue("第二段思考应在工具调用之后",indexOfText(secondThought)>tool)
        assertEquals("工具调用要能对应到本轮","c1",rows[tool].data().str("call_id"))
        // 3. 正式回复与界面展示的思考也挂同一轮。
        val result=rows.last{it.ev()=="chat.result"}
        assertEquals(turn.id,result.data().str("turn"))
        assertEquals(turn.attempt.toString(),result.data().str("attempt"))
        assertTrue("第二段思考应在正式回复之前",indexOfText(secondThought)<rows.indexOf(result))
        val requestIds=rows.filter{it.ev()=="network.request"}.mapNotNull{it.data().str("request_id")}.toSet()
        reasoned.forEach{assertTrue("响应编号要能在请求记录里找到",it.str("request_id") in requestIds)}
        val shownRows=rows.filter{it.ev()=="chat.reasoning.shown"&&it.data().str("turn")==turn.id}
        assertEquals("同一轮只应有一条展示记录",1,shownRows.size)
        val shown=shownRows.single()
        assertEquals(turn.id,shown.data().str("turn"))
        assertEquals("success",shown.data().str("outcome"))
        assertTrue("界面展示的思考要落盘",shown.data().str("reasoning_text").orEmpty().contains(secondThought))
        assertTrue("工具回执切入位置要留下",shown.data().getAsJsonArray("breaks").size()>=1)
        assertFalse("流式分片不逐片落盘",rows.any{it.data().str("reasoning_text")?.contains("TextStreamEvent.Reasoning(先想")==true})
    }

    @Test fun failedTurnStillRecordsWhatWasShownAsPartial():Unit=runBlocking {
        val shownThought="失败前屏幕上已经出现的思考"
        val raw=object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
            override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                syntheticCompanionStage(request)?.let{return it}
                onEvent(TextStreamEvent.Reasoning(shownThought))
                return TextChatResult.Failure(ChatFailure.TIMEOUT)
            }
        }
        val runner=runner(raw)
        val before=ChatLedger(store).all().map{it.id}.toSet()
        assertTrue(runner.send("a","你好"){})
        val turn=awaitTurn(before)
        assertEquals(TurnState.FAILED,turn.state)
        delay(500)
        val rows=rows(journal.export(true))
        val shown=rows.last{it.ev()=="chat.reasoning.shown"}
        assertEquals("failure",shown.data().str("outcome"))
        assertEquals("失败前已展示的思考不能记成未返回","partial",shown.data().str("reasoning_status"))
        assertEquals(shownThought,shown.data().str("reasoning_text"))
        assertEquals("失败原因要留下","TIMEOUT",shown.data().str("failure"))
        val response=rows.last{it.ev()=="network.response"}
        assertEquals("partial",response.data().str("reasoning_status"))
        assertEquals(shownThought,response.data().str("reasoning_text"))
    }

    @Test fun detailedOffKeepsStatusButNotTheReasoningText():Unit=runBlocking {
        Diagnostics.detailed=false
        val turn=oneTurn("关闭时不该出现的思考甲" to "关闭时不该出现的思考乙")
        assertEquals(TurnState.COMPLETED,turn.state)
        delay(500)
        val log=journal.export(true).toString(Charsets.UTF_8)
        assertFalse("关闭详细时不记思考原文",log.contains("关闭时不该出现的思考甲"))
        assertFalse(log.contains("关闭时不该出现的思考乙"))
        assertTrue("状态仍要记录",log.contains("\"reasoning_status\""))
        assertTrue("明确说明详细未开启",log.contains("详细诊断未开启"))
    }
}
