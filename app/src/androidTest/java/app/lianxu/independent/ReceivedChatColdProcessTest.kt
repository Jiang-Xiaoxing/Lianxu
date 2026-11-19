package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Run the two phases in separate instrumentation processes using the same isolated store. */
class ReceivedChatColdProcessTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name get()="receipt-cold-"+InstrumentationRegistry.getArguments().getString("receiptRun")
    @org.junit.Before fun pairedRun(){org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().containsKey("receiptRun"))}
    private val config=ModelConfig("local-proof","本地验证","custom","https://example.invalid/v1","local-only")
    @Test fun prepare()=runBlocking {
        Store(app,name+".db").use{store->
            store.add(listOf(Friend("a","甲","本地验证")),false)
            val vault=ModelVault(app,name,store)
            vault.save(config,"synthetic-local-placeholder")
            vault.setPurpose(ModelPurpose.CHAT,config.id)
            val companion=CompanionRepository(store)
            companion.checkContinuity(CompanionRuntime.owner);companion.beginInput("a")
            val l=ChatLedger(store);val t=l.begin("a","本地验证问题",config.id)
            val repo=ProcessingRepository(store)
            val r=ReceivedChatReply(listOf("第一段","第二段"),"",l.context(t.id).stamp,repo.referenceStamp(store.readableDatabase,"a"),
                stableDigest(config.toString()),companion.snapshot("a"),ReceivedImageEffects(false,"",emptyMap(),emptyList(),emptyList(),null),
                ReceivedMemoryEffects(emptyMap(),0,stableDigest(com.google.gson.Gson().toJson(repo.memoryRules(store.readableDatabase)))),false)
            assertTrue(l.saveReceived(t,r,ChatAttemptLease()){true})
            store.savePreference("proof-pid",android.os.Process.myPid().toString())
            Unit
        }
    }
    @Test fun resumeLocally()=runBlocking {
        Store(app,name+".db").use{store->
            assertNotEquals(store.preference("proof-pid",""),android.os.Process.myPid().toString())
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val vault=ModelVault(app,name,store);val repo=ProcessingRepository(store)
            var attempts=0
            val client=object:TextChatClient {
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult { attempts++;error("unexpected network") }
            }
            try{
                val runner=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{},displayReplies=true)
                val ledger=ChatLedger(store);ledger.recoverInterrupted()
                CompanionRepository(store).checkContinuity(CompanionRuntime.owner)
                withContext(Dispatchers.Main.immediate){
                    runner.restoreReceived()
                    withTimeout(12000){while(runner.previews["a"]?.bubbles?.size!=2){
                        val turn=ledger.all().single();assertEquals("local recovery: "+turn.failure,TurnState.PENDING,turn.state);delay(20)
                    }}
                    val view=requireNotNull(runner.previews["a"])
                    assertEquals(0,view.visible);runner.acknowledge("a",view.turnId,view.attempt,2)
                    withTimeout(12000){while("a" in runner.sending)delay(20)}
                }
                assertEquals(TurnState.COMPLETED,ledger.all().single().state)
                assertEquals(listOf("第一段\n\n第二段"),store.messages().filter{!it.mine}.map{it.text})
                assertEquals(0,attempts)
            }finally{scope.cancel()}
        }
    }
}
