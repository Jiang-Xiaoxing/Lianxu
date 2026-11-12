package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工具回执在完成后的真实状态与顺序：只在回复事务提交后变成功，失败/取消如实未保存；
 * 读、写、多次调用都不重复写入；重开数据库后状态和思考位置不变。
 */
class ToolReceiptStateAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var vault:ModelVault
    private lateinit var name:String;private lateinit var scope:CoroutineScope
    private val replyCalls=AtomicInteger()

    @Before fun prepare()=runBlocking{
        name="tool-receipt-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store);vault=ModelVault(context,name)
        store.add(listOf(Friend("a","合成甲","只属于角色设定的简介")),false)
        vault.save(ModelConfig("fake","合成模型","custom","https://example.invalid/v1","fake-model",64000,4096),"synthetic-only")
        vault.setPurpose(ModelPurpose.CHAT,"fake");scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        repo.saveMemoryRules(MemoryRules(immediateWrite=true))
        replyCalls.set(0)
    }
    @After fun close(){runBlocking{scope.cancel();scope.coroutineContext[Job]?.join()};vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db")}

    private fun runner(reply:suspend(TextChatRequest)->TextChatResult):ConversationRunner{
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            syntheticCompanionStage(request)?.let{return it}
            replyCalls.incrementAndGet()
            return reply(request)
        }}
        return ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
    }
    private fun toolRoundSeen(request:TextChatRequest)=request.messages.any{it.role=="tool"}
    private fun writeCall(id:String)=ChatToolCall(id,"agent_memory_update",
        """{"op":"add","target":"","category":"PREFERENCE_BOUNDARY","about":"USER","content":"喜欢吃意大利面","evidence":["S1"]}""")
    private suspend fun done():ChatTurn=withTimeout(15000){while(true){val t=ChatLedger(store).all().lastOrNull();if(t!=null&&t.state!=TurnState.PENDING)return@withTimeout t;delay(10)};error("unreachable")}
    private suspend fun idle(r:ConversationRunner){withTimeout(15000){while("a" in r.sending)delay(10)}}

    @Test fun thinkingReminderStaysOnWireOnly()=runBlocking{
        val input="那你记一下，我喜欢吃意大利面";var count=0
        val r=runner{request->
            count++;assertTrue(request.messages.last{it.role=="user"}.content.endsWith(CompanionPrompt.THINKING_REMINDER))
            if(!toolRoundSeen(request))TextChatResult.Success("",listOf(writeCall("cn")),displayReasoning="先记下来。") else TextChatResult.Success("记好了。",displayReasoning="再回应。")
        }
        assertTrue(r.send("a",input){});idle(r);assertEquals(TurnState.COMPLETED,done().state);assertEquals(2,count)
        assertEquals(input,store.messages().single{it.mine}.text)
        store.messages().forEach{assertFalse(it.text.contains(CompanionPrompt.THINKING_REMINDER));assertFalse(it.displayReasoning.contains(CompanionPrompt.THINKING_REMINDER))}
        assertFalse(done().toolActivities.toString().contains(CompanionPrompt.THINKING_REMINDER))
    }
    @Test fun aCommittedWriteShowsSavedAndKeepsItsThinkingOffset()=runBlocking{
        val r=runner{request->
            if(!toolRoundSeen(request))TextChatResult.Success("",listOf(writeCall("stage")),displayReasoning="先想一想。")
            else TextChatResult.Success("记下了，意面我也爱吃。",displayReasoning="然后再说。")
        }
        assertTrue(r.send("a","那你记一下，我喜欢吃意大利面"){});idle(r);val turn=done()
        assertEquals(TurnState.COMPLETED,turn.state)
        val activity=turn.toolActivities.single{it.tool=="agent_memory_update"}
        assertEquals("success",activity.state);assertEquals("",activity.detail)
        assertEquals("嗯…我记住了",activity.label())
        assertEquals("回执要记住它出现时的思考位置","先想一想。".length,activity.reasoningAt)
        assertTrue("新回执必须带位置标记",activity.reasoningKnown)
        val message=store.messages().single{!it.mine}
        assertEquals("先想一想。然后再说。",message.displayReasoning)
        val stored=repo.memories("a").single()
        assertEquals("回执要指向真正写入的那条记录",stored.id,activity.memoryId)
        assertNotNull("暂存回执要留下提交时回查的凭据",activity.operation)
        assertTrue(activity.canOpenMemory)
        // 重开数据库：不重复、不回到暂存、关联还在。
        store.close();store=Store(context,"$name.db");repo=ProcessingRepository(store)
        val reopened=ChatLedger(store).all().single()
        val again=reopened.toolActivities.single{it.tool=="agent_memory_update"}
        assertEquals("success",again.state);assertEquals(activity.reasoningAt,again.reasoningAt)
        assertEquals(stored.id,again.memoryId);assertTrue(again.canOpenMemory)
        assertEquals(1,repo.memories("a").size)
    }

    @Test fun aFailedReplyLeavesTheStagedWriteHonestlyUnsaved()=runBlocking{
        val r=runner{request->
            if(!toolRoundSeen(request))TextChatResult.Success("",listOf(writeCall("stage")),displayReasoning="先想一想。")
            else TextChatResult.Failure(ChatFailure.NETWORK)
        }
        assertTrue(r.send("a","那你记一下，我喜欢吃意大利面"){});idle(r);val turn=done()
        assertEquals(TurnState.FAILED,turn.state)
        val activity=turn.toolActivities.single{it.tool=="agent_memory_update"}
        assertEquals("failed",activity.state);assertEquals("没记下",activity.label())
        assertEquals("回话没完成，这条没存下",activity.detail)
        assertNull("没保存的胶囊不能链到任何记录",activity.memoryId)
        assertFalse("失败或取消的胶囊不能点开",activity.canOpenMemory)
        assertTrue(repo.memories("a").isEmpty())
    }

    @Test fun readsAndMultipleWritesKeepCallOrderAndOffsets()=runBlocking{
        val r=runner{request->
            val tools=toolRoundSeen(request)
            when{
                !tools->TextChatResult.Success("",listOf(
                    ChatToolCall("read","agent_recall_search","""{"query":"意面"}"""),
                    writeCall("stage1")),displayReasoning="先查一下。")
                else->TextChatResult.Success("查过了，也记下了。",displayReasoning="再写一条。")
            }
        }
        assertTrue(r.send("a","帮我回忆一下意面，再记一笔"){});idle(r);val turn=done()
        assertEquals(TurnState.COMPLETED,turn.state)
        val activities=turn.toolActivities
        assertEquals(2,activities.size)
        assertEquals("agent_recall_search",activities[0].tool);assertEquals("agent_memory_update",activities[1].tool)
        assertEquals("同一次请求发出的回执位置相同","先查一下。".length,activities[0].reasoningAt)
        assertEquals("先查一下。".length,activities[1].reasoningAt)
        assertEquals(1,repo.memories("a").size)
        // 多次读取不回写、不多记。
        repeat(3){ChatLedger(store).all()}
        assertEquals(1,repo.memories("a").size)
        assertEquals(2,ChatLedger(store).all().single().toolActivities.size)
    }

    @Test fun anEmptyMemoryReadIsReportedAsNotFoundInsteadOfASuccess(){
        runBlocking{
            ChatLedger(store).let{val t=it.begin("a","随便聊聊","fake");it.finish(t.id,1,"好啊")}
            val r=runner{request->
                if(!toolRoundSeen(request))TextChatResult.Success("",listOf(ChatToolCall("read","agent_recall_search","""{"query":"完全不存在的事"}""")),displayReasoning="翻一下。")
                else TextChatResult.Success("没找到。",displayReasoning="空的。")
            }
            assertTrue(r.send("a","你还记得完全不存在的事吗"){});idle(r);val done=done()
            val activity=done.toolActivities.single{it.tool=="agent_recall_search"}
            assertEquals("failed",activity.state);assertEquals("没想起来",activity.label())
            assertEquals("真没命中只留一颗，不挂解释小字","",activity.detail)
            assertEquals("翻一下。".length,activity.reasoningAt)
        }
    }

    @Test fun readingTheLedgerRepeatedlyDoesNotDuplicateReceiptsOrMemories(){
        runBlocking{
            val r=runner{request->
                if(!toolRoundSeen(request))TextChatResult.Success("",listOf(writeCall("stage")),displayReasoning="第一轮。")
                else TextChatResult.Success("记下了。",displayReasoning="第二轮。")
            }
            assertTrue(r.send("a","记一下我喜欢吃意大利面"){});idle(r)
            val first=done();assertEquals(1,first.toolActivities.size)
            repeat(3){val again=ChatLedger(store).all().single();assertEquals(1,again.toolActivities.size);assertEquals("success",again.toolActivities.single().state)}
            assertEquals(1,repo.memories("a").size)
        }
    }

    /**
     * 一次调用改已有记忆：回执仍链到同一条记录，展开读到的是改完后的内容。
     * 这里直接提交一条真实的 update 暂存（走生产的 commit/link 路径）：合成的回查工具会按
     * “本轮已投递的内容不重复展示”把这条挡掉，那属于既有回查规则，不是改写的提交路径。
     */
    @Test fun anUpdateLinksToTheSameRowAndKeepsItsNewText()=runBlocking{
        val add=runner{request->
            if(!toolRoundSeen(request))TextChatResult.Success("",listOf(ChatToolCall("add","agent_memory_update",
                """{"op":"add","target":"","category":"PREFERENCE_BOUNDARY","about":"USER","content":"用户叫小周。","evidence":["S1"]}""")),displayReasoning="先记。")
            else TextChatResult.Success("记住了。",displayReasoning="说完。")
        }
        assertTrue(add.send("a","记一下我叫小周"){});idle(add)
        assertEquals(TurnState.COMPLETED,done().state)
        val original=repo.memories("a").single()
        val ledger=ChatLedger(store)
        val turn=ledger.begin("a","我改名叫小周了","fake")
        val refs=repo.references("a","我改名叫小周了",turn.userMessageId)
        assertTrue(ledger.finish(turn.id,turn.attempt,"好"))
        val sourceVersion=repo.read{db->db.rawQuery("SELECT source_revision FROM chat_turns WHERE id=?",arrayOf(turn.id)).use{it.moveToFirst();it.getLong(0)}}
        val digest="update-digest"
        val stage=JSONObject().put("op","update").put("target_id",original.id).put("target_version",original.version)
            .put("content","用户改名叫小周了。").put("category","PREFERENCE_BOUNDARY").put("about","USER")
            .put("evidence",org.json.JSONArray().put("S1")).put("source_version",sourceVersion)
            .put("content_supplied",true).put("category_supplied",true).put("about_supplied",true)
            .put("candidate",JSONObject.NULL).put("digest",digest)
        repo.tx{db->
            db.insertWithOnConflict("preferences",null,android.content.ContentValues().apply{
                put("key","memory-tools:${turn.id}:${turn.attempt}");put("value",org.json.JSONArray().put(stage).toString())
            },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            ChatToolJournal.put(db,turn.id,ChatToolActivity("upd",turn.attempt,"嗯…我记住了","staged",
                kind=ChatToolActivity.WRITE,tool="agent_memory_update",operation=digest))
            Unit
        }
        val tools=MemoryTools(repo,turn,"我改名叫小周了",refs,active={true})
        repo.tx{db->tools.commit(db)}
        val updated=repo.memories("a").single()
        assertEquals("改的是同一条记录",original.id,updated.id)
        assertEquals("用户改名叫小周了。",updated.text)
        assertEquals(2,updated.version)
        val receipt=ChatLedger(store).activities(turn.id).single()
        assertEquals("update 也要链到同一条记录",original.id,receipt.memoryId)
        // 与真实回复同一条结算路径：提交成功变已保存，关联保留，可点开。
        ChatLedger(store).settleActivities(turn.id,turn.attempt,true,false,true)
        val settled=ChatLedger(store).activities(turn.id).single()
        assertEquals("success",settled.state)
        assertEquals(original.id,settled.memoryId)
        assertTrue(settled.canOpenMemory)
        assertEquals("展开读的是当前行","用户改名叫小周了。",repo.memory(store.readableDatabase,"a",original.id)?.text)
        // 提交没发生时同一条暂存只能算没存下，不能点开。
        val draft=ledger.begin("a","再改一次","fake")
        repo.tx{db->ChatToolJournal.put(db,draft.id,ChatToolActivity("draft",draft.attempt,"嗯…我记住了","staged",
            kind=ChatToolActivity.WRITE,tool="agent_memory_update",operation="draft-op"))}
        repo.tx{db->ChatToolJournal.settle(db,draft.id,draft.attempt,false,false,false)}
        val unsaved=ChatLedger(store).activities(draft.id).single()
        assertEquals("failed",unsaved.state);assertEquals("没记下",unsaved.label())
        assertFalse(unsaved.canOpenMemory);assertNull(unsaved.memoryId)
    }
}
