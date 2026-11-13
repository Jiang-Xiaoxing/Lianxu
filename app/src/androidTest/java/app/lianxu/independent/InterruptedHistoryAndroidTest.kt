package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Isolated SQLite only. No provider, user database or credentials. */
class InterruptedHistoryAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var name:String
    @Before fun setup()=runBlocking {
        name="interrupted-history-${System.nanoTime()}.db";store=Store(context,name)
        store.add(listOf(Friend("a","甲","合成角色"),Friend("b","乙","合成角色")),false)
    }
    @After fun close(){store.close();context.deleteDatabase(name)}
    @Test fun visiblePrefixSurvivesFailureAndRetryAppendsToSameMessage()=runBlocking {
        val l=ChatLedger(store);val t=l.begin("a","原话","fake");val epoch=AppMutationGate.epoch()
        assertTrue(l.recordSpoken(t.id,1,"已显示",epoch));l.fail(t.id,1,ChatFailure.NETWORK)
        val savedId=l.all().single().assistantMessageId
        assertEquals("已显示",l.spoken(t.id))
        val retry=requireNotNull(l.retry(t.id,"fake"));assertEquals(2,retry.attempt)
        assertFalse(l.recordSpoken(t.id,1,"已显示\n\n旧任务晚到",epoch))
        assertTrue(l.finish(t.id,2,"已显示\n\n重试接着说"))
        assertEquals(savedId,l.all().single().assistantMessageId)
        assertEquals(1,store.messages().count{!it.mine})
        assertEquals("已显示\n\n重试接着说",l.spoken(t.id))
    }
    @Test fun processRecoveryKeepsOnlyDurableDisplayedPrefix()=runBlocking {
        val before=ChatLedger(store,"old-session");val t=before.begin("a","原话","fake")
        assertTrue(before.recordSpoken(t.id,1,"看见的部分",AppMutationGate.epoch()))
        store.close();store=Store(context,name)
        val recovered=ChatLedger(store,"new-session");recovered.recoverInterrupted()
        assertEquals(TurnState.INTERRUPTED,recovered.all().single().state)
        assertEquals("看见的部分",recovered.spoken(t.id))
        val next=recovered.begin("a","补一句","fake")
        assertEquals(listOf("原话","看见的部分"),recovered.context(next.id).recent.map{it.text})
        assertEquals(1,ProcessingRepository(store).sources("a").size)
    }
    @Test fun failureThenNewInputMakesTheOldPartialSummarizable()=runBlocking {
        val l=ChatLedger(store);val old=l.begin("a","先前原话","fake")
        l.recordSpoken(old.id,1,"已说",AppMutationGate.epoch());l.fail(old.id,1,ChatFailure.NETWORK)
        val fresh=l.begin("a","追加原话","fake")
        assertEquals(listOf("先前原话","已说"),l.context(fresh.id).recent.map{it.text})
        assertEquals(TurnState.SUPERSEDED,l.all().first().state)
        assertNull(l.retry(old.id,"fake"))
        assertEquals(1,ProcessingRepository(store).sources("a").size)
    }
    @Test fun delayedSaveNeverTruncatesOrCrossesResetEpoch()=runBlocking {
        val l=ChatLedger(store);val t=l.begin("a","原话","fake");val epoch=AppMutationGate.epoch()
        l.recordSpoken(t.id,1,"一\n\n二",epoch)
        l.recordSpoken(t.id,1,"一",epoch)
        assertEquals("一\n\n二",l.spoken(t.id))
        assertFalse(l.recordSpoken(t.id,1,"一\n\n二\n\n错误世代",epoch-1))
        assertEquals("一\n\n二",l.spoken(t.id))
    }
    @Test fun onlyShownPrefixEntersHistoryAndMemorySources()=runBlocking {
        val l=ChatLedger(store);val t=l.begin("a","第一句","fake")
        assertTrue(l.interrupt(t.id,t.attempt,"已显示"))
        assertFalse(l.interrupt(t.id,t.attempt,"晚到尾段"))
        val next=l.begin("a","补一句","fake")
        assertEquals(listOf("第一句","已显示"),l.context(next.id).recent.map{it.text})
        val sources=ProcessingRepository(store).sources("a")
        assertEquals(1,sources.size);assertEquals("已显示",sources.single().assistant?.text)
        assertTrue(ProcessingRepository(store).read{ProcessingRepository(store).sourceValid(it,sources.single().source)})
        assertTrue(ProcessingRepository(store).sources("b").isEmpty())
    }
    @Test fun userOnlyInterruptionIsStillARealSummarizableSource()=runBlocking {
        val l=ChatLedger(store);val t=l.begin("a","别漏掉这句","fake")
        assertTrue(l.interrupt(t.id,t.attempt,""))
        val source=ProcessingRepository(store).sources("a").single()
        assertNull(source.assistant);assertEquals("别漏掉这句",source.user.text)
        assertEquals(1,store.messages().size)
    }
    @Test fun stoppedLeaseCannotOverwriteOrCompleteTheOldTurn()=runBlocking {
        val l=ChatLedger(store);val t=l.begin("a","第一句","fake");val lease=ChatAttemptLease()
        lease.cancel();assertTrue(l.interrupt(t.id,t.attempt,"看见的部分"))
        assertFalse(l.finish(t.id,t.attempt,"未显示的尾段",lease))
        assertFalse(l.finish(t.id,t.attempt+1,"错误尝试"))
        assertEquals(TurnState.SUPERSEDED,l.all().single().state)
        assertEquals("看见的部分",store.messages().single{!it.mine}.text)
    }
    @Test fun stoppedHistoryUpdatesContactWithoutInventingAnOpportunity()=runBlocking {
        val ledger=ChatLedger(store);val repo=ProcessingRepository(store);var now=2_000_000_000_000L
        val core=CompanionRepository(store){now}
        for((role,spoken) in listOf("a" to "已显示前缀","b" to "")){
            core.setPolicy(role,true,"synthetic")
            val old=ledger.begin(role,"旧问候","fake");assertTrue(ledger.finish(old.id,1,"旧回复"))
            repo.tx{it.execSQL("UPDATE messages SET created=? WHERE id=?",arrayOf<Any>(now,old.userMessageId))}
            now+=RelationshipProjector.DAY
            val stopped=ledger.begin(role,"这次联系是真实的","fake");assertTrue(ledger.interrupt(stopped.id,1,spoken))
            repo.tx{it.execSQL("UPDATE messages SET created=? WHERE id=?",arrayOf<Any>(now,stopped.userMessageId))}
            assertEquals(now,core.relationship(role).state.lastContact)
            core.refreshRelationshipContacts()
            assertTrue(core.queued().none{it.role==role})
        }
    }
    @Test fun consecutiveStopsKeepOrderAndForgottenSourceCannotSupplyContact()=runBlocking {
        val ledger=ChatLedger(store);val repo=ProcessingRepository(store);var now=2_000_000_000_000L
        val core=CompanionRepository(store){now}
        suspend fun stopped(text:String):ChatTurn {
            val t=ledger.begin("a",text,"fake");assertTrue(ledger.interrupt(t.id,1,"前缀"))
            repo.tx{it.execSQL("UPDATE messages SET created=? WHERE id=?",arrayOf<Any>(now,t.userMessageId))};return t
        }
        val first=stopped("第一句");now+=1000;val last=stopped("补一句")
        assertEquals(listOf(first.userMessageId,last.userMessageId),repo.read{core.relationshipContacts(it,"a").map{r->r.first.turnId}})
        assertEquals(now,core.relationship("a").state.lastContact)
        core.forget("a",last.userMessageId)
        assertEquals(listOf(first.userMessageId),repo.read{core.relationshipContacts(it,"a").map{r->r.first.turnId}})
        assertEquals(now-1000,core.relationship("a").state.lastContact)
    }
}
