package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** 真实 HTTPS 客户端收到 402 时，必须变成「余额不足」，而且总结只发一次。 */
class PaymentHttpAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope

    @Before fun setup()=runBlocking {
        name="payment-http-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic")),false)
        val l=ChatLedger(store);val t=l.begin("a","明天还要上班，得加班","synthetic");assertTrue(l.finish(t.id,t.attempt,"那就别熬太晚"))
        vault=ModelVault(context,"payment-http-${System.nanoTime()}");scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    }
    @After fun close(){scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name)}

    @Test fun realHttp402BecomesPaymentAndStopsAfterOneCall():Unit=runBlocking {
        TextTlsMock(status=402,response="""{"error":{"message":"insufficient balance"}}""",maxRequests=3).use{mock->
            val config=ModelConfig("pay","合成付费","custom",mock.base,"synthetic-model",32000,2048)
            vault.save(config,"synthetic-key-only");vault.setPurpose(ModelPurpose.MEMORY,config.id)
            val built=engine(mock.client()).ensureRoom("a",forceOne=true)
            assertEquals("MODEL_PAYMENT",built.code)
            assertEquals("402 只能请求一次",1,mock.calls.get())
            val failure=MemoryBatchRepository(repo).failure("a")
            assertNotNull(failure);assertEquals("MODEL_PAYMENT",failure!!.code)
            assertTrue(MemoryRepairFeedback.reason(failure.code).contains("402"))
            assertTrue(memoryFailureText(failure).contains("402"))
            assertTrue(processingMessage("MODEL_PAYMENT").contains("402"))
            assertTrue(repo.memories("a").isEmpty())
        }
    }

    private fun engine(c:TextChatClient)=MemoryEngine(scope,repo,ModelAccess(vault),c,{},300000)
}
