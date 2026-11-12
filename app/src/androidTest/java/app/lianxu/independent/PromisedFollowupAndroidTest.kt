package app.lianxu.independent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
class PromisedFollowupAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var core:CompanionRepository
    private lateinit var database:String
    private var now=1800000000000L
    private val owner="promised-test"
    @Before fun setup()=runBlocking {
        database="promised-${System.nanoTime()}.db";store=Store(context,database);core=CompanionRepository(store){now}
        store.add(listOf(Friend("a","测试","测试")),false);core.setPolicy("a",true,owner)
        val ledger=ChatLedger(store);val t=ledger.begin("a","稍后聊","synthetic")
        store.writableDatabase.execSQL("UPDATE messages SET created=?",arrayOf<Any>(now))
        val p=ExperienceProposal(quote="稍后聊",memory="稍后聊")
        val s=core.snapshot("a");assertTrue(core.accept(t,p,s){true});val after=core.snapshot("a")
        assertTrue(ledger.finishWithMemory(t.id,t.attempt,"好的",expectedStamp=ledger.context(t.id).stamp,onCompleted={core.finish(it,t,after,p)}){true})
        store.writableDatabase.execSQL("INSERT INTO companion_opportunities(id,role,source_id,revision,epoch,policy_version,not_before,expires_at,reason,state) SELECT 'followup','a',?,1,epoch,policy_version,?,?, 'test','queued' FROM companion_state WHERE role='a'",arrayOf<Any>(t.userMessageId,now,now+30*RelationshipProjector.DAY))
    }
    @After fun close(){store.close();context.deleteDatabase(database)}
    private fun state()=store.readableDatabase.rawQuery("SELECT state FROM companion_opportunities WHERE id='followup'",null).use{it.moveToFirst();it.getString(0)}
    private fun notBefore()=store.readableDatabase.rawQuery("SELECT not_before FROM companion_opportunities WHERE id='followup'",null).use{it.moveToFirst();it.getLong(0)}
    @Test fun lowMissingStillReconsidersAtNotBefore():Unit=runBlocking {
        val (r,f)=core.relationshipForecast(store.readableDatabase,"a")
        val due=RelationshipProactive.next(r.parameters,r.state.lastContact,emptyList(),now,relationshipDayStart(now),f)
        assertTrue("missing should still be below threshold",due==null||due>now)
        val o=core.claim("followup",owner)!!;assertEquals("running",state());var calls=0
        val model=CompanionModel(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success("""{"action":"send","reason":"回访","text":"现在怎么样？"}""")}})
        val s=core.snapshot("a");val d=model.reconsider(ModelConfig("synthetic","测试","custom","https://example.invalid","test",128000,2048),"synthetic",o,s,"测试",now,"")
        assertEquals(1,calls);assertNotNull(core.conclude(o,owner,d,s){true})
        core.release(o,owner,false);assertEquals("send",state())
    }
    @Test fun legacyExpiryAllowsLateWakeAndDeferralBeyondIt():Unit=runBlocking {
        store.writableDatabase.execSQL("UPDATE companion_opportunities SET expires_at=?",arrayOf<Any>(now-1000))
        val o=core.claim("followup",owner)!!;val next=now+RelationshipProjector.DAY
        core.conclude(o,owner,OpportunityDecision("defer","明天再聊",nextAt=next),core.snapshot("a")){true}
        assertEquals("queued",state());assertEquals(next,notBefore());assertNull(core.claim("followup",owner))
        now=next;assertNotNull(core.claim("followup",owner))
    }
    @Test fun shortDeferralSurvivesSlowModelResponse():Unit=runBlocking {
        val o=core.claim("followup",owner)!!;val snapshot=core.snapshot("a");val requestedAt=now;val next=now+1000
        now+=10_000
        core.conclude(o,owner,OpportunityDecision("defer","很快再聊",nextAt=next),snapshot,decisionAt=requestedAt){true}
        assertEquals("queued",state());assertEquals(next,notBefore());assertNotNull(core.claim("followup",owner))
    }
    @Test fun modelCanCancelAnOldTopicAndCannotBeWokenAgain():Unit=runBlocking {
        val before=core.receipts("a") // Setup already delivered an ordinary chat reply.
        store.writableDatabase.execSQL("UPDATE companion_opportunities SET expires_at=?",arrayOf<Any>(now-1000))
        val o=core.claim("followup",owner)!!
        core.conclude(o,owner,OpportunityDecision("cancel","已经聊完了"),core.snapshot("a")){true}
        assertEquals("cancel",state());assertNull(core.claim("followup",owner));assertEquals(before,core.receipts("a"))
    }
    @Test fun duplicateWakeAndCommitCannotSendTwice():Unit=runBlocking {
        val before=core.receipts("a").map{it.messageId}.toSet()
        val o=core.claim("followup",owner)!!;assertNull(core.claim("followup","another"))
        val snapshot=core.snapshot("a");val decision=OpportunityDecision("send","聊聊结果","怎么样了？")
        assertNotNull(core.conclude(o,owner,decision,snapshot){true})
        assertNull(core.conclude(o,owner,decision,snapshot){true});assertNull(core.claim("followup",owner))
        val added=core.receipts("a").filter{it.messageId !in before}
        assertEquals(1,added.size)
        assertEquals("怎么样了？",store.messages().single{it.id==added.single().messageId}.text)
    }
    @Test fun failedLegacyTaskRetainsTechnicalBackoffPastItsOldExpiry():Unit=runBlocking {
        store.writableDatabase.execSQL("UPDATE companion_opportunities SET expires_at=?",arrayOf<Any>(now-1000))
        val o=core.claim("followup",owner)!!;core.release(o,owner,false)
        assertEquals(1,core.retryable("a"));core.retryFailed("a")
        assertEquals("queued",state());assertEquals(now+ContactRetry.FAILED_RETRY_DELAY_MS,notBefore())
        assertNull(core.claim("followup",owner));now=notBefore();assertNotNull(core.claim("followup",owner))
    }
    @Test fun disabledCancels():Unit=runBlocking {core.setPolicy("a",false,owner);assertNull(core.claim("followup",owner));assertEquals("cancelled",state())}
    @Test fun relationshipStillNeedsThreshold():Unit=runBlocking {store.writableDatabase.execSQL("UPDATE companion_opportunities SET id='relationship:test'");assertNull(core.claim("relationship:test",owner));assertTrue(core.queued().isEmpty())}
    @Test fun recoveryRequeuesWithoutDelay():Unit=runBlocking {
        store.writableDatabase.execSQL("UPDATE companion_opportunities SET state='running',owner=?",arrayOf<Any>(owner))
        core.checkContinuity("new-process");assertEquals("queued",state());assertEquals(now,notBefore())
    }
    @Test fun releaseKeepsRetryable():Unit=runBlocking {
        val o=core.claim("followup",owner)!!
        core.release(o,owner,false);assertEquals("failed",state());assertEquals(now,notBefore())
    }
}
