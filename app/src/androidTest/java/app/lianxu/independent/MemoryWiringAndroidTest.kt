package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Full chain: the character writes a memory through agent_memory_update, the turn commits it,
 *  and later turns must show exactly what happens to it on the way into the reply references. */
class MemoryWiringAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault
    private lateinit var name:String;private lateinit var scope:CoroutineScope
    private val requests=mutableListOf<TextChatRequest>()
    private var wrote=false
    private val memoryText="旅人明天上班还得加班"
    @Before fun prepare()=runBlocking {
        name="memory-wiring-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);vault=ModelVault(context,name)
        store.add(listOf(Friend("a","合成角色","只属于角色设定的简介")),false)
        vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid/v1","fake-model",64000,4096),"synthetic-only")
        vault.setPurpose(ModelPurpose.CHAT,"fake");scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    }
    @After fun close(){runBlocking{scope.cancel();scope.coroutineContext[Job]?.join()};vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db")}
    private fun writeArgs()=JSONObject().put("op","add").put("category","STATE_FACT").put("about","USER")
        .put("content",memoryText).put("evidence",JSONArray().put("S1")).toString()
    private fun runner():ConversationRunner {
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                requests+=request
                syntheticCompanionStage(request)?.let{return it}
                if(!wrote&&"agent_memory_update" in request.tools.toString()&&request.messages.none{it.role=="tool"}){
                    wrote=true
                    return TextChatResult.Success("",listOf(ChatToolCall("w1","agent_memory_update",writeArgs())))
                }
                return syntheticFinalReply(request,TextChatResult.Success("合成回复"))
            }
        }
        return ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
    }
    private suspend fun settled(before:Set<String>?=null):ChatTurn=withTimeout(15000){while(true){
        val t=ChatLedger(store).all().lastOrNull{it.id !in (before?:emptySet())}
        if(t!=null&&t.state!=TurnState.PENDING)return@withTimeout t;delay(10)
    };error("unreachable")}
    private suspend fun waitIdle(r:ConversationRunner)=withTimeout(15000){while("a" in r.sending)delay(10)}
    /** One more turn; returns every message the model actually received, joined (refs live in their own block). */
    private suspend fun turn(r:ConversationRunner,input:String):String {
        val before=ChatLedger(store).all().map{it.id}.toSet();requests.clear()
        assertTrue("send rejected: $input",r.send("a",input){})
        val t=settled(before);assertEquals("turn failed: ${t.failure}",TurnState.COMPLETED,t.state);waitIdle(r)
        return requests.first().messages.joinToString("\n"){it.content}
    }
    @Test fun toolWrittenMemorySurvivesTheWholeChain()=runBlocking{
        repo.saveMemoryRules(MemoryRules(proactiveRecall=true,immediateWrite=true))
        val r=runner()
        val first=turn(r,"明天还要上班啊啊啊")
        // Stage 1: it really landed, with the evidence rule satisfied.
        val saved=repo.memories("a")
        assertEquals("stage1 saved rows",1,saved.size)
        assertEquals("stage1 content",memoryText,saved.single().text)
        assertTrue("stage1 valid",saved.single().valid);assertEquals("stage1 life",MemoryLife.ACTIVE,saved.single().life)
        // Stage 2: usable memories really contain it.
        val usable=repo.read{db->repo.usableMemories(db,"a")}
        assertEquals("stage2 usable rows",1,usable.size)
        // Stage 3: the next, unrelated reply still carries it, because a fresh memory is core-eligible.
        val unrelated=turn(r,"哎")
        println("WIRING unrelated_core=${"核心记忆" in unrelated} unrelated_keyword=${"关键词记忆" in unrelated} unrelated_text=${memoryText in unrelated}")
        assertTrue("stage3 the fresh memory must be a core row",memoryText in unrelated&&"核心记忆" in unrelated)
        // Stage 4: a related question keeps it too.
        val related=turn(r,"明天还要加班吗")
        println("WIRING related_core=${"核心记忆" in related} related_keyword=${"关键词记忆" in related} related_text=${memoryText in related}")
        assertTrue("stage4 a related question still carries it",memoryText in related)
        assertTrue("first turn still replies",first.isNotEmpty())
    }
}
