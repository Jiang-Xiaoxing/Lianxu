package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Production display gate with synthetic transport and isolated SQLite; no provider calls. */
class ConversationInterruptAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope;private lateinit var name:String
    @Before fun setup()=runBlocking {
        name="interrupt-runner-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);vault=ModelVault(context,name)
        store.add(listOf(Friend("a","甲","合成角色"),Friend("b","乙","合成角色")),false)
        vault.save(ModelConfig("fake","合成","custom","https://example.invalid/v1","fake",64000,4096),"synthetic-only")
        vault.setPurpose(ModelPurpose.CHAT,"fake");scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    }
    @After fun close() {
        runBlocking{scope.cancel();scope.coroutineContext[Job]?.join()}
        vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db")
    }
    private fun runner(client:TextChatClient)=ConversationRunner(scope,store,vault,client,repo,
        MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{},displayReplies=true)
    private suspend fun until(test:suspend()->Boolean)=withTimeout(10000){while(!test())delay(10)}
    @Test fun newReplyDoesNotWaitForCancelledTransportAndLateCleanupCannotClearIt()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val returned=CompletableDeferred<Unit>()
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                return if(request.latestUserText().contains("第一句"))withContext(NonCancellable){
                    entered.complete(Unit);release.await();returned.complete(Unit);TextChatResult.Success("不应出现的旧尾巴")
                }else TextChatResult.Success("补发后的回答")
            }
        }
        val r=runner(client)
        try {
            withContext(Dispatchers.Main){assertTrue(r.send("a","第一句"){})}
            withTimeout(10000){entered.await()}
            withContext(Dispatchers.Main){assertTrue(r.send("a","补一句"){})}
            until{withContext(Dispatchers.Main){r.previews["a"]?.bubbles==listOf("补发后的回答")}}
            val fresh=withContext(Dispatchers.Main){requireNotNull(r.previews["a"])}
            release.complete(Unit);withTimeout(10000){returned.await()};delay(100)
            withContext(Dispatchers.Main){
                assertEquals(fresh.turnId,r.previews["a"]?.turnId);assertTrue("a" in r.sending)
                r.acknowledge("a",fresh.turnId,fresh.attempt,1)
            }
            until{ChatLedger(store).all().lastOrNull()?.state==TurnState.COMPLETED}
            assertEquals(listOf("第一句","补一句"),store.messages().filter{it.mine}.map{it.text})
            assertEquals(listOf("补发后的回答"),store.messages().filterNot{it.mine}.map{it.text})
        }finally{release.complete(Unit)}
    }
    @Test fun visibleFailurePrefixIsDurableAndRetryDoesNotDuplicateIt()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var retry=false
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=error("stream expected")
            override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {
                if(retry){
                    assertTrue(request.messages.any{"已经说过的话" in it.content&&"看见的部分" in it.content})
                    return TextChatResult.Success("看见的部分\n\n后续回答")
                }
                onEvent(TextStreamEvent.Content("看见的部分\n\n没有显示完的尾段"))
                entered.complete(Unit);release.await();return TextChatResult.Failure(ChatFailure.NETWORK)
            }
        }
        val r=runner(client);val l=ChatLedger(store)
        try {
            withContext(Dispatchers.Main){r.send("a","原话") {}}
            withTimeout(10000){entered.await()}
            val p=withContext(Dispatchers.Main){requireNotNull(r.previews["a"])}
            withContext(Dispatchers.Main){r.acknowledge("a",p.turnId,p.attempt,1)}
            until{l.spoken(p.turnId)=="看见的部分"}
            release.complete(Unit)
            until{l.all().single().state==TurnState.FAILED&&withContext(Dispatchers.Main){"a" !in r.sending}}
            assertEquals("看见的部分",l.spoken(p.turnId));retry=true
            withContext(Dispatchers.Main){r.retry(l.all().single())}
            until{withContext(Dispatchers.Main){r.previews["a"]?.bubbles==listOf("看见的部分","后续回答")}}
            val next=withContext(Dispatchers.Main){requireNotNull(r.previews["a"])}
            withContext(Dispatchers.Main){r.acknowledge("a",next.turnId,next.attempt,2)}
            until{l.all().single().state==TurnState.COMPLETED}
            assertEquals("看见的部分\n\n后续回答",l.spoken(p.turnId));assertEquals(1,store.messages().count{!it.mine})
        }finally{release.complete(Unit)}
    }
}
