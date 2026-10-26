package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Synthetic-only end-to-end repository and fake provider; never contacts a real API. */
class RoleExamplesTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repository:RoleExamplesRepository
    private lateinit var vault:ModelVault
    private lateinit var dbName:String
    private val config=ModelConfig("synthetic","Synthetic examples","custom","https://example.invalid/v1","mock",64000,1024)
    @Before fun setup()=runBlocking {
        dbName="examples-${System.nanoTime()}.db";store=Store(context,dbName);repository=RoleExamplesRepository(store)
        store.add(listOf(Friend("a","A","Synthetic A"),Friend("b","B","Synthetic B")),false)
        vault=ModelVault(context,"examples-${System.nanoTime()}");vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
    }
    @After fun cleanup()=runBlocking {vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(dbName);Unit}
    private suspend fun rounds(n:Int,id:String="a") {repeat(n){val ledger=ChatLedger(store);val t=ledger.begin(id,"今天和你聊聊 ${System.nanoTime()}",config.id);assertTrue(ledger.finish(t.id,1,"我愿意慢慢听你说，也想陪你一起散步。"))}}
    private fun engine(block:suspend(TextChatRequest)->TextChatResult)=RoleExamplesEngine(repository,ModelAccess(vault),object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=block(request)})
    private fun pick(request:TextChatRequest):TextChatResult {val j=JSONObject(request.messages.last().content);val c=j.getJSONArray("candidates");return TextChatResult.Success("{\"ids\":[\"${c.getJSONObject(0).getString("id")}\",\"${c.getJSONObject(1).getString("id")}\"]}")}
    private suspend fun prompts(id:String="a")=RolePromptStore.decode(store.preference(RolePromptStore.key(id),""))
    @Test fun invitationCountsOnlyCompletedTurnsAndNeverImportsOldRoles()=runBlocking {
        assertNull(repository.get("seed-lin"));assertEquals(ExamplesState.WAITING,repository.get("a")!!.state)
        rounds(19);val failed=ChatLedger(store).begin("a","失败的合成消息",config.id);ChatLedger(store).fail(failed.id,1,ChatFailure.NETWORK)
        assertEquals(ExamplesState.WAITING,repository.get("a")!!.state)
        rounds(1);assertEquals(ExamplesState.OFFERED,repository.get("a")!!.state);assertEquals(ExamplesState.WAITING,repository.get("b")!!.state)
        assertFalse(prompts().examplesEnabled)
    }
    @Test fun consentDoesNotCallProviderSelectionUsesOnlyOwnOriginalsAndPersists()=runBlocking {
        rounds(20);rounds(20,"b");var calls=0
        val e=engine{r->calls++;assertTrue(r.tools.isEmpty());val data=JSONObject(r.messages.last().content);assertEquals("A",data.getString("character"));assertEquals(15,data.getJSONArray("candidates").length());pick(r)}
        val consent=e.consent()!!;assertEquals(0,calls);assertEquals(ExamplesState.OFFERED,repository.get("a")!!.state)
        e.select("a",consent);assertEquals(1,calls)
        val row=repository.get("a")!!;assertEquals(ExamplesState.ACTIVE,row.state);assertTrue(row.optedIn);assertEquals(row.selected.map{it.pair},prompts().examples);assertTrue(prompts().examplesEnabled)
        assertEquals(ExamplesState.OFFERED,repository.get("b")!!.state);assertFalse(prompts("b").examplesEnabled)
        assertEquals(row,RoleExamplesRepository(store).get("a"))
        e.select("a",consent);assertEquals(1,calls)
    }
    @Test fun declinedAndInterruptedDoNotAutomaticallyCall()=runBlocking {
        rounds(20);repository.stop("a");val e=engine{error("must not call")};e.select("a",e.consent()!!);assertEquals(ExamplesState.DECLINED,repository.get("a")!!.state)
        rounds(20,"b");assertNotNull(repository.claim("b"));repository.recover();assertEquals(ExamplesState.FAILED,repository.get("b")!!.state);assertFalse(prompts("b").examplesEnabled)
    }
    @Test fun invalidResponseFailsWithoutWritingAndExplicitRetryWorks()=runBlocking {
        rounds(20);var calls=0;val e=engine{r->calls++;if(calls==1)TextChatResult.Success("{\"ids\":[\"invented\",\"also-invented\"]}") else pick(r)}
        e.select("a",e.consent()!!);assertEquals(ExamplesState.FAILED,repository.get("a")!!.state);assertFalse(prompts().examplesEnabled)
        e.select("a",e.consent()!!);assertEquals(2,calls);assertEquals(ExamplesState.ACTIVE,repository.get("a")!!.state)
    }
    @Test fun cancellationAndDuplicateClicksCannotActivateLateResults()=runBlocking {
        rounds(20);val entered=CompletableDeferred<Unit>();var calls=0
        val e=engine{calls++;entered.complete(Unit);awaitCancellation()};val consent=e.consent()!!
        val job=launch{e.select("a",consent)};entered.await();e.select("a",consent);assertEquals(1,calls);job.cancelAndJoin()
        assertEquals(ExamplesState.FAILED,repository.get("a")!!.state);assertFalse(prompts().examplesEnabled)
    }
    @Test fun windowClosesAt40BeforeRetirementAndKeepsSavedPairs()=runBlocking {
        rounds(20);val e=engine(::pick);e.select("a",e.consent()!!);val pairs=prompts().examples
        rounds(19);assertEquals(ExamplesState.ACTIVE,repository.get("a")!!.state)
        rounds(1);assertEquals(ExamplesState.DISABLED,repository.get("a")!!.state);assertEquals(pairs,prompts().examples);assertFalse(prompts().examplesEnabled);assertTrue(prompts().examplesClosed)
    }
    @Test fun lateResultCannotReopenFullWindowOrChangedModel()=runBlocking {
        rounds(20);val e=engine{r->rounds(20);pick(r)};e.select("a",e.consent()!!);assertEquals(ExamplesState.DISABLED,repository.get("a")!!.state);assertFalse(prompts().examplesEnabled)
        rounds(20,"b");val changed=engine{r->vault.save(config.copy(modelId="changed"),"synthetic-only");pick(r)};changed.select("b",changed.consent()!!);assertEquals(ExamplesState.FAILED,repository.get("b")!!.state);assertFalse(prompts("b").examplesEnabled)
    }
    @Test fun changedOrDeletedSourcesCannotActivateAndActiveSourcesAreRechecked()=runBlocking {
        rounds(20);val selected=repository.claim("a")!!;val chosen=selected.candidates.take(2)
        store.writableDatabase.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE id=?",arrayOf(chosen.first().id))
        assertFalse(repository.complete("a",selected,chosen){true});assertFalse(prompts().examplesEnabled)
        val e=engine(::pick);e.select("a",e.consent()!!);val row=repository.get("a")!!
        store.writableDatabase.execSQL("INSERT OR REPLACE INTO message_state(message_id,revision,deleted) SELECT user_message_id,1,1 FROM chat_turns WHERE id=?",arrayOf(row.selected.first().id))
        assertEquals(ExamplesState.DISABLED,repository.get("a")!!.state);assertFalse(prompts().examplesEnabled);assertEquals(2,prompts().examples.size)
    }
    @Test fun scansPast100InvalidMessagesAndNeverSendsBeforeEligible()=runBlocking {
        repeat(60){store.exchange(Friend("a","A","Synthetic"),"not a ledger turn","not a ledger turn")}
        rounds(20);val e=engine(::pick);e.select("a",e.consent()!!);assertEquals(ExamplesState.ACTIVE,repository.get("a")!!.state)
    }
    @Test fun newScenesDoNotInvalidateChosenOriginalsAndPromptSavePreservesThem()=runBlocking {
        rounds(20);val selection=repository.claim("a")!!;val chosen=selection.candidates.takeLast(2)
        assertTrue(repository.complete("a",selection,chosen){true})
        val ledger=ChatLedger(store);val t=ledger.begin("a","今天确实有一点难过",config.id);assertTrue(ledger.finish(t.id,1,"我想陪你慢慢聊，先听你说说发生了什么。"))
        assertEquals(ExamplesState.ACTIVE,repository.get("a")!!.state)
        store.updateRolePrompts("a"){it.copy(expression="合成表达更新")}
        assertEquals(chosen.map{it.pair},prompts().examples);assertTrue(prompts().examplesEnabled)
        val built=CompanionPrompt.build(Friend("a","A","Synthetic A"),prompts(),emptyList(),"新的合成消息",capacity=64000) as PromptBuild.Ready
        chosen.forEach{c->assertTrue(built.request.messages.any{it.role=="user"&&it.content==c.pair.user});assertTrue(built.request.messages.any{it.role=="assistant"&&it.content==c.pair.assistant})}
    }

}
