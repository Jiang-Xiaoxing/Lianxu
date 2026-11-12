package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/**
 * 记忆已经在本轮参考资料里时再回查：工具结果不能是空页，要带编号说明“已在上方参考资料中”，
 * 胶囊显示“想起来了”；真没命中才显示一颗“没想起来”，下面不挂解释小字。
 */
class RecallDedupReceiptAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault
    private lateinit var name:String;private lateinit var scope:CoroutineScope

    @Before fun prepare()=runBlocking{
        name="recall-dedup-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);vault=ModelVault(context,name)
        store.add(listOf(Friend("a","合成甲","只属于角色设定的简介")),false)
        vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid/v1","fake-model",64000,4096),"synthetic-only")
        vault.setPurpose(ModelPurpose.CHAT,"fake");scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        repo.saveMemoryRules(MemoryRules(immediateWrite=true,proactiveRecall=true))
    }
    @After fun close(){runBlocking{scope.cancel();scope.coroutineContext[Job]?.join()};vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db")}

    private fun runner(reply:suspend(TextChatRequest)->TextChatResult):ConversationRunner{
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            syntheticCompanionStage(request)?.let{return it}
            return reply(request)
        }}
        return ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
    }
    private suspend fun done():ChatTurn=withTimeout(20000){while(true){val t=ChatLedger(store).all().lastOrNull();if(t!=null&&t.state!=TurnState.PENDING)return@withTimeout t;delay(10)};error("unreachable")}
    private suspend fun idle(r:ConversationRunner){withTimeout(20000){while("a" in r.sending)delay(10)}}
    private fun addCall()=ChatToolCall("add","agent_memory_update",
        """{"op":"add","target":"","category":"PREFERENCE_BOUNDARY","about":"USER","content":"用户喜欢吃意大利面。","evidence":["S1"]}""")

    @Test fun aMemoryAlreadyInTheReferenceComesBackNamedAndThePillSaysRemembered()=runBlocking{
        val first=runner{request->
            if(request.messages.none{it.role=="tool"})TextChatResult.Success("",listOf(addCall()),displayReasoning="先记。")
            else TextChatResult.Success("记住了。",displayReasoning="说完。")
        }
        assertTrue(first.send("a","记一下，我喜欢吃意大利面"){});idle(first)
        assertEquals(TurnState.COMPLETED,done().state)
        val stored=repo.memories("a").single()

        var readResult:String?=null
        var idSeenInReference=false
        val second=runner{request->
            val tools=request.messages.filter{it.role=="tool"}
            if(tools.isEmpty()){
                idSeenInReference=stored.id in request.messages.flatMap{it.memoryIds}
                TextChatResult.Success("",listOf(ChatToolCall("read","agent_memory_read","""{"query":"意大利面"}""")),displayReasoning="先找。")
            }else{
                readResult=tools[0].content
                TextChatResult.Success("记得，你最喜欢吃意大利面。",displayReasoning="找到了。")
            }
        }
        assertTrue(second.send("a","你还记得我最喜欢吃什么吗"){});idle(second)
        val turn=done();assertEquals(TurnState.COMPLETED,turn.state)
        assertTrue("这条记忆应该已经在本轮参考资料里，才能验到去重",idSeenInReference)
        val raw=requireNotNull(readResult)
        assertTrue("回查不能返回空列表：$raw",raw.contains("已在上方参考资料中"))
        assertTrue("去重不是没找到：$raw",raw.contains("already_in_context"))
        val parsed=JSONObject(raw)
        assertTrue("命中的记忆要带编号",parsed.getJSONArray("results").getJSONObject(0).getString("content").startsWith("tool-"))
        val receipt=turn.toolActivities.single{it.tool=="agent_memory_read"}
        assertEquals("success",receipt.state)
        assertEquals("想起来了",receipt.label())
        assertTrue("想起来了不能挂“没找到”小字",receipt.detail.isBlank())
    }

    @Test fun aRealMissShowsOneCleanNotFoundPill()=runBlocking{
        val r=runner{request->
            if(request.messages.none{it.role=="tool"})TextChatResult.Success("",listOf(ChatToolCall("read","agent_memory_read","""{"query":"完全不存在的事"}""")),displayReasoning="翻一下。")
            else TextChatResult.Success("好像没有。",displayReasoning="空的。")
        }
        assertTrue(r.send("a","你还记得完全不存在的事吗"){});idle(r)
        val turn=done()
        val receipt=turn.toolActivities.single()
        assertEquals("failed",receipt.state);assertEquals("没想起来",receipt.label())
        assertEquals("只显示一颗没想起来，下面不挂小字","",receipt.detail)
    }
}
