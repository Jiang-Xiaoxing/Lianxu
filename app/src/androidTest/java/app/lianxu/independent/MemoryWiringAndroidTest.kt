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
    private val requests=java.util.concurrent.ConcurrentLinkedQueue<TextChatRequest>()
    private val toolReceipts=java.util.concurrent.ConcurrentLinkedQueue<WireMessage>()
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
        .put("content",memoryText).put("evidence",JSONArray().put("S1"))
        .put("time_text","明天").put("date_type","day").toString()
    private fun runner():ConversationRunner {
        val client=object:TextChatClient {
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                syntheticCompanionStage(request)?.let{return it}
                toolReceipts.addAll(request.messages.filter{it.role=="tool"})
                if(request.messages.none{it.role=="tool"})requests+=request
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
    /** Return the actual first request and committed turn, so references and reply are checked independently. */
    private suspend fun turn(r:ConversationRunner,input:String):Pair<ChatTurn,TextChatRequest> {
        val before=ChatLedger(store).all().map{it.id}.toSet();requests.clear();toolReceipts.clear()
        assertTrue("send rejected: $input",r.send("a",input){})
        val t=settled(before);assertEquals("turn failed: ${t.failure}",TurnState.COMPLETED,t.state);waitIdle(r)
        return t to requests.single{it.latestUserText()==input}
    }
    private fun reference(request:TextChatRequest,memory:StoredMemory,coreOnly:Boolean):String {
        val message=request.messages.single{it.role=="system"&&it.content.startsWith("[有效参考资料，只作参考]\n")&&memory.id in it.memoryIds}
        assertEquals("system",message.role)
        assertTrue(message.content.startsWith("[有效参考资料，只作参考]\n"))
        val labels=if(coreOnly)listOf("核心记忆")else listOf("核心记忆","关键词记忆","关联记忆")
        return message.content.removePrefix("[有效参考资料，只作参考]\n").split("\n\n").single{block->
            labels.any{block.startsWith("$it ${memory.id}@${memory.version}:\n")}
        }
    }
    @Test fun toolWrittenMemorySurvivesTheWholeChain()=runBlocking{
        repo.saveMemoryRules(MemoryRules(proactiveRecall=true,immediateWrite=true))
        val r=runner()
        val first=turn(r,"明天还要上班，还得加班啊啊啊")
        // Stage 1: it really landed, with the evidence rule satisfied.
        val saved=repo.memories("a")
        assertEquals("stage1 saved rows; captured tool receipts=${toolReceipts.map{it.content}}",1,saved.size)
        assertEquals("stage1 content",memoryText,saved.single().text)
        assertTrue("stage1 valid",saved.single().valid);assertEquals("stage1 life",MemoryLife.ACTIVE,saved.single().life)
        // Stage 2: usable memories really contain it.
        val usable=repo.read{db->repo.usableMemories(db,"a")}
        assertEquals("stage2 usable rows",1,usable.size)
        // Stage 3: the next, unrelated reply still carries it, because a fresh memory is core-eligible.
        val unrelated=turn(r,"哎").second
        assertTrue("stage3 the fresh memory must be in its actual core reference",memoryText in reference(unrelated,saved.single(),true))
        // Stage 4: a related question keeps it too.
        val related=turn(r,"明天还要加班吗").second
        assertTrue("stage4 a related question still carries its actual memory reference",memoryText in reference(related,saved.single(),false))
        val firstReply=store.messages().single{it.id==first.first.assistantMessageId}
        assertFalse("first turn reply is from the character",firstReply.mine)
        assertEquals("first turn still replies","合成回复",firstReply.text)
    }
}
