package app.lianxu.independent

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实服务商跑一次完整总结：只有把密钥放进 App 私有目录 `files/sf-key.txt`（第一行 Key，可选第二行模型 ID，
 * 可选第三行服务商地址）时才会执行，否则跳过。密钥不属于仓库内容，跑完由用例删除；默认硅基流动 `deepseek-ai/DeepSeek-V3.2`。
 */
class RealProviderMemoryPillTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile get()=File(context.filesDir,"sf-key.txt")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var name:String?=null

    @After fun cleanup(){
        keyFile.delete()
        name?.let{n->runBlocking{runCatching{Store(context,n).close()};context.deleteDatabase(n)}}
        scope.cancel()
    }

    @Test fun aRealProviderSummaryCommitsExactlyOnePillMatchingTheRealWrites()=runBlocking<Unit>{
        Assume.assumeTrue("没有 files/sf-key.txt，跳过真实服务商用例",keyFile.isFile)
        val lines=keyFile.readLines().map{it.trim()}.filter{it.isNotEmpty()}
        val key=lines.first()
        val model=lines.getOrNull(1)?:"deepseek-ai/DeepSeek-V3.2"
        // 第三行可选：换成另一个 OpenAI 兼容服务商的地址；不写仍是硅基流动。
        val base=lines.getOrNull(2)?:"https://api.siliconflow.cn/v1"
        val dbName="pill-real-${System.nanoTime()}.db";name=dbName
        val store=Store(context,dbName);val repo=ProcessingRepository(store);val vault=ModelVault(context,dbName,store)
        try{
            store.add(listOf(Friend("a","A","Synthetic")),false)
            val ledger=ChatLedger(store)
            listOf(
                "明天还要上班，得加班到很晚",
                "下周要去外地三天，周三回来",
                "花摊那几天麻烦你帮我照看"
            ).forEachIndexed{i,text->
                val turn=ledger.begin("a",text,"synthetic")
                check(ledger.finish(turn.id,1,if(i==0)"那就别熬太晚，记得吃饭。" else "好，我记着了。"))
            }
            val config=ModelConfig("sf-real","真实服务商调用","custom",base,model,64000,4096)
            vault.save(config,key);vault.setPurpose(ModelPurpose.MEMORY,config.id)
            val calls=AtomicInteger()
            val real=AppServices.createTextClient()
            val counting=object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {calls.incrementAndGet();return real.complete(config,key,request)}
                override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult {calls.incrementAndGet();return real.stream(config,key,request,onChunk)}
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult {calls.incrementAndGet();return real.streamEvents(config,key,request,onEvent)}
            }
            val engine=MemoryEngine(scope,repo,ModelAccess(vault),counting,{})
            val run=withTimeout(240_000){engine.ensureRoom("a",forceOne=true)}
            Log.e("PILLREAL","result=${run.success}/${run.code} calls=${calls.get()} memories=${repo.memories("a").size}")
            assertTrue("真实服务商总结应成功，实际 ${run.code}（请求 ${calls.get()} 次）",run.success)
            val db=store.readableDatabase
            val pills=ChatMemoryPills.read(db,"a")
            assertEquals("真实提交后只应有一颗胶囊",1,pills.size)
            val pill=pills.single()
            val valid=repo.memories("a").count{it.valid}
            assertEquals("胶囊计数必须等于本次真正落库的记忆数",valid,pill.savedCount)
            assertEquals("胶囊计数必须与操作表一致",ChatMemoryPills.savedCount(db,pill.batchId),pill.savedCount)
            assertEquals(if(valid>0)"已整理 $valid 条记忆" else "记忆已整理",pill.text)
            assertEquals("重新读取不能多出第二颗",1,ChatMemoryPills.read(db,"a").size)
            Log.e("PILLREAL","pill=${pill.text} batch=${pill.batchId} anchored=${pill.afterMessageId}")
        }finally{store.close();vault.list().forEach{vault.delete(it.id)}}
    }
}
