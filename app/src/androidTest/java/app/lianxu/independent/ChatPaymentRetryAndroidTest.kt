package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** 聊天被记忆总结挡住时：说清 402、不自动重试；充值后点聊天里的重试要真的重新总结并把这轮说完。 */
class ChatPaymentRetryAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var ledger:ChatLedger;private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    private val memoryCalls=AtomicInteger()
    @Volatile private var paymentBroken=true

    @Before fun prepare()=runBlocking{
        name="chat-payment-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        store.add(listOf(Friend("a","合成甲","独立角色甲")),false)
        vault=ModelVault(context,"chat-payment-${System.nanoTime()}");scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
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
            if(paymentBroken)return TextChatResult.Failure(ChatFailure.PAYMENT,402)
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

    @Test fun rechargedChatRetryRunsTheFailedSummaryAndCompletesTheTurn()=runBlocking{
        repeat(2){turn("加班到很晚，终于回家了",longReply)}
        val shared=client()
        val memory=MemoryEngine(scope,repo,ModelAccess(vault),shared,{})
        val runner=ConversationRunner(scope,store,vault,shared,repo,memory,{},{})
        val failed=sendAndAwait(runner,"今天又加班了")
        assertEquals(TurnState.FAILED,failed.state)
        assertEquals(ChatFailure.MEMORY_PAYMENT,failed.failure)
        assertTrue(failed.failure!!.display.contains("402"))
        assertEquals("余额不足只能试一次",1,memoryCalls.get())
        assertTrue(repo.memories("a").isEmpty())
        // 用户充值后，在聊天里点这条消息的重试。
        paymentBroken=false
        val retried=retryAndAwait(runner,failed)
        val timeline=ledger.all().joinToString{"${it.id.take(4)}:${it.state}:${it.attempt}:${it.failure?.name}"}
        assertEquals(timeline,TurnState.COMPLETED,retried.state)
        assertEquals("重试必须真的重新发起总结",2,memoryCalls.get())
        assertEquals(1,repo.memories("a").size)
        assertEquals(6,store.messages().count{it.friendId=="a"})
        // 恢复要能持续：再聊一句也能正常完成，不再被挡住。
        val next=sendAndAwait(runner,"那我先去睡了")
        assertEquals("恢复后应能继续聊天",TurnState.COMPLETED,next.state)
        assertNull(next.failure)
    }

    @Test fun backgroundSummaryFailureAlsoShows402OnTheNextMessage():Unit=runBlocking{
        repeat(2){turn("加班到很晚，终于回家了",longReply)}
        val shared=client()
        val memory=MemoryEngine(scope,repo,ModelAccess(vault),shared,{})
        // 后台在聊天之后自己总结，先撞上 402。
        memory.afterChat("a")
        await{MemoryBatchRepository(repo).failure("a")!=null}
        assertEquals("后台失败也只发一次",1,memoryCalls.get())
        val runner=ConversationRunner(scope,store,vault,shared,repo,memory,{},{})
        val next=sendAndAwait(runner,"今天又加班了")
        assertEquals(ChatFailure.MEMORY_PAYMENT,next.failure)
        assertTrue(next.failure!!.display.contains("402"))
        assertEquals("下一条消息也不能偷偷重试",1,memoryCalls.get())
    }

    @Test fun newMessageAfterFailureShowsTheRealCauseWithoutRetrying():Unit=runBlocking{
        repeat(2){turn("加班到很晚，终于回家了",longReply)}
        val shared=client()
        val memory=MemoryEngine(scope,repo,ModelAccess(vault),shared,{})
        val runner=ConversationRunner(scope,store,vault,shared,repo,memory,{},{})
        val first=sendAndAwait(runner,"今天又加班了")
        assertEquals(ChatFailure.MEMORY_PAYMENT,first.failure)
        paymentBroken=false
        // 还没点重试，先发新消息：不该偷偷重试，但仍要说清是余额不足。
        val second=sendAndAwait(runner,"那我先去睡了")
        val timeline=ledger.all().joinToString{"${it.id.take(4)}:${it.state}:${it.attempt}:${it.failure?.name}"}
        assertEquals("$timeline calls=${memoryCalls.get()}",ChatFailure.MEMORY_PAYMENT,second.failure)
        assertTrue(second.failure!!.display.contains("402"))
        assertEquals("没有点重试就不能自己跑",1,memoryCalls.get())
    }
}
