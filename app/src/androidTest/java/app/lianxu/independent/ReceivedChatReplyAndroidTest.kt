package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Exercises two process-session identities and the real SQLite transaction. No provider is constructed. */
class ReceivedChatReplyAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="received-chat-"+System.nanoTime()+".db"
    private lateinit var store:Store
    @Before fun setup()=runBlocking{
        store=Store(context,name)
        store.add(listOf(Friend("a","甲","合成角色"),Friend("b","乙","合成角色")),false)
    }
    @After fun close(){store.close();context.deleteDatabase(name)}
    private suspend fun receipt(l:ChatLedger,t:ChatTurn)=ReceivedChatReply(listOf("一","二"),"",l.context(t.id).stamp,"ref","model",
        CompanionSnapshot(t.friendId,0,0,"",emptyList(),"","disabled"),ReceivedImageEffects(false,"",emptyMap(),emptyList(),emptyList(),null),
        ReceivedMemoryEffects(emptyMap(),0,""),false)
    private fun count()=store.readableDatabase.rawQuery("SELECT count(*) FROM received_chat_replies",null).use{it.moveToFirst();it.getInt(0)}
    @Test fun coldClaimOnceAndStopKeepsOnlyShownPrefix()=runBlocking{
        val old=ChatLedger(store,"old")
        val a=old.begin("a","本地甲","cfg");val b=old.begin("b","本地乙","cfg")
        val result=receipt(old,a)
        assertTrue(old.recordSpoken(a.id,1,"一",AppMutationGate.epoch()))
        assertTrue(old.saveReceived(a,result,ChatAttemptLease()){true})
        assertEquals(TurnState.PENDING,old.all().first{it.id==a.id}.state)
        store.close();store=Store(context,name)
        val fresh=ChatLedger(store,"fresh")
        assertEquals(1,fresh.recoverInterrupted())
        assertEquals(TurnState.INTERRUPTED,fresh.all().first{it.id==b.id}.state)
        assertEquals(result,fresh.claimReceived().single().second)
        assertTrue(fresh.claimReceived().isEmpty())
        assertFalse(old.run { ChatLedger(store,"old").interrupt(a.id,1,"二") })
        assertTrue(fresh.interrupt(a.id,1,"一"));assertEquals("一",fresh.spoken(a.id));assertEquals(0,count())
    }
    @Test fun migrationPreservesPendingTurnAndSourceChangeInvalidatesReceipt()=runBlocking{
        val original=ChatLedger(store).begin("a","升级前","cfg")
        val db=store.writableDatabase
        db.execSQL("DROP TRIGGER discard_received_reply")
        db.execSQL("DROP TABLE received_chat_replies")
        db.version=18
        store.close();store=Store(context,name)
        val l=ChatLedger(store)
        assertEquals(original,l.all().single());assertEquals(0,count())
        assertTrue(l.saveReceived(original,receipt(l,original),ChatAttemptLease()){true})
        store.writableDatabase.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE id=?",arrayOf(original.id))
        assertEquals(0,count());assertTrue(ChatLedger(store,"new").claimReceived().isEmpty())
    }
    @Test fun staleAttemptAndFailedWriteNeverDeclareSuccess()=runBlocking{
        val l=ChatLedger(store);val a=l.begin("a","本地","cfg")
        val r=receipt(l,a)
        assertFalse(l.saveReceived(a,r,ChatAttemptLease()){false})
        assertTrue(l.fail(a.id,1,ChatFailure.NETWORK))
        val next=requireNotNull(l.retry(a.id,"cfg"))
        assertFalse(l.saveReceived(a,r,ChatAttemptLease()){true})
        val payload=receipt(l,next)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON received_chat_replies BEGIN SELECT RAISE(ABORT,'write failed'); END")
        assertTrue(runCatching{l.saveReceived(next,payload,ChatAttemptLease()){true}}.isFailure)
        assertEquals(0,count());assertEquals(TurnState.PENDING,l.all().single().state)
        store.writableDatabase.execSQL("DROP TRIGGER fail_receipt")
        assertTrue(l.saveReceived(next,payload,ChatAttemptLease()){true})
        assertTrue(runCatching{l.finishWithMemory(next.id,next.attempt,"一\n\n二",onCompleted={error("settlement failure")})}.isFailure)
        assertEquals(1,count());assertEquals(TurnState.PENDING,l.all().single().state)
        assertTrue(l.finish(next.id,next.attempt,"一\n\n二"));assertEquals(0,count());assertEquals(1,store.messages().count{!it.mine})
    }
}
