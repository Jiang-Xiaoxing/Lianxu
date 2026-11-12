package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** 记忆总结读不动时（读超限 / 不是事件流）：聊天要说清原因、只试一次；修好后点重试要补上并把这轮说完。 */
class ChatStreamRetryAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var ledger:ChatLedger;private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    private val memoryCalls=AtomicInteger()
    @Volatile private var broken:ChatFailure?=ChatFailure.STREAM_PROTOCOL

    @Before fun prepare()=runBlocking{
        name="chat-stream-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        store.add(listOf(Friend("a","合成甲","独立角色甲")),false)
        vault=ModelVault(context,"chat-stream-${System.nanoTime()}");scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        listOf(ModelPurpose.CHAT,ModelPurpose.MEMORY).forEach{p->val c=ModelConfig(p.key,p.title,"custom","https://example.invalid/v1",p.key,contextTokens=if(p==ModelPurpose.MEMORY)128000 else 32000,outputTokens=2048);vault.save(c,"synthetic-key-only");vault.setPurpose(p,c.id)}
        repo.saveMemoryRules(MemoryRules(1,1,15))
    }
    @After fun close(){scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name)}

    /**
     * 现在聊天只在按 token 真塞不下、且有可总结的轮数时才等总结（轮数超过近期轮数不再拦）。
     * 两轮长回复合起来塞不下聊天模型的输入、总结掉一轮后就塞得下，这样这一轮才会真的去等总结。
     * 记忆模型给大一点的窗口，保证一整轮长回复本身装得下，失败只来自注入的 402 / 事件流问题。
     */
    private val longReply="那就早点睡。"+"长".repeat(9000)
    private suspend fun turn(text:String,reply:String):ChatTurn{val t=ledger.begin("a",text,"chat");assertTrue(ledger.finish(t.id,t.attempt,reply));return ledger.all().first{it.id==t.id}}
    private suspend fun await(test:suspend()->Boolean){withTimeout(15000){while(!test())delay(10)}}
    private fun receipt(d:JSONObject):TextChatResult{
        val source=SourceDialogue.from(d.getJSONArray("target_dialogue").getJSONObject(0));val id=d.getString("batch_id")
        val op=JSONObject().put("id","one").put("op","ADD").put("target","N1").put("category","STATE_FACT").put("about","USER")
            .put("text",source.user.text.take(120)).put("evidence",JSONArray().put("S1"))
        return TextChatResult.Success(JSONObject().put("batch_id",id).put("character_id","a")
            .put("operations",JSONArray().put(op)).put("operation_count",1).put("complete",true).put("end_marker",id).toString())
    }
    private fun client()=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        syntheticCompanionStage(request)?.let{return it}
        if(config.modelId=="memory"){
            memoryCalls.incrementAndGet()
            broken?.let{return TextChatResult.Failure(it)}
            return receipt(JSONObject(request.messages.last().content))
        }
        return TextChatResult.Success("合成客户端回复")
    }}
    private suspend fun sendAndAwait(runner:ConversationRunner,input:String):ChatTurn{
        val before=ledger.all().map{it.id}.toSet()
        withContext(Dispatchers.Main){await{runner.send("a",input,{})}}
        await{ledger.all().any{it.id !in before&&it.state!=TurnState.PENDING}}
        return ledger.all().last{it.id !in before}
    }
    private suspend fun retryAndAwait(runner:ConversationRunner,failed:ChatTurn):ChatTurn{
        withContext(Dispatchers.Main){
            await{
                val t=ledger.all().firstOrNull{it.id==failed.id}?:return@await false
                if(t.attempt>failed.attempt)true else{runner.retry(failed);false}
            }
        }
        await{ledger.all().first{it.id==failed.id}.state!=TurnState.PENDING}
        return ledger.all().first{it.id==failed.id}
    }

    private fun blockedChat(code:ChatFailure,shown:String,notice:String,expectedCode:String):Unit=runBlocking{
        repeat(2){turn("加班到很晚，终于回家了",longReply)}
        val shared=client()
        val memory=MemoryEngine(scope,repo,ModelAccess(vault),shared,{})
        val runner=ConversationRunner(scope,store,vault,shared,repo,memory,{},{})
        val failed=sendAndAwait(runner,"今天又加班了")
        assertEquals(TurnState.FAILED,failed.state)
        assertEquals(code,failed.failure)
        assertTrue(failed.failure!!.display, failed.failure!!.display.contains(shown))
        assertEquals("这种错误不该自动重试三次",1,memoryCalls.get())
        assertEquals(expectedCode,MemoryBatchRepository(repo).failure("a")!!.code)
        assertTrue("原话要留在最近窗口",repo.recentSources("a").isNotEmpty())
        assertTrue("失败时没有写入记忆",repo.memories("a").isEmpty())
        assertTrue(memoryFailureText(MemoryBatchRepository(repo).failure("a")!!).contains(notice))
        // 修好后在聊天里点重试：要真的重新总结，并把这一轮说完。
        broken=null
        val retried=retryAndAwait(runner,failed)
        assertEquals(TurnState.COMPLETED,retried.state)
        assertEquals("重试必须真的重新发起总结",2,memoryCalls.get())
        assertEquals(1,repo.memories("a").size)
        val next=sendAndAwait(runner,"那我先去睡了")
        assertEquals("恢复后要能继续聊天",TurnState.COMPLETED,next.state)
    }

    @Test fun unreadableStreamBlocksTheChatWithItsRealCauseAndRetryFinishesTheTurn(){
        blockedChat(ChatFailure.MEMORY_STREAM_PROTOCOL,"事件流","事件流","MODEL_STREAM_PROTOCOL")
    }

    @Test fun oversizedStreamIsNamedAsALimitAndManualRetryRecovers(){
        broken=ChatFailure.STREAM_LIMIT
        blockedChat(ChatFailure.MEMORY_STREAM_LIMIT,"太长","读取上限","MODEL_STREAM_LIMIT")
    }
}
