package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class LocalUpdatesTest {
    @Test fun readOnceMatchesOnlyTheInstalledKnownVersion(){
        val empty=LocalUpdateReceipt();assertEquals(17L,LocalReleaseNotes.pending(17,empty)!!.code)
        assertNull(LocalReleaseNotes.pending(17,empty.acknowledge(17)));assertNull(LocalReleaseNotes.pending(18,empty))
        assertEquals(17L,LocalReleaseNotes.pending(17,empty.acknowledge(16))!!.code)
    }
    @Test fun acknowledgementIsMonotonicAndDoesNotHideManualHistory(){
        val seen=LocalUpdateReceipt().acknowledge(17).acknowledge(16)
        assertEquals(17L,seen.lastAcknowledged);assertNull(LocalReleaseNotes.pending(16,seen))
        assertEquals(listOf(17L,16L),LocalReleaseNotes.history(17).map{it.code});assertEquals(listOf(16L),LocalReleaseNotes.history(16).map{it.code})
        assertTrue(LocalReleaseNotes.history(15).isEmpty())
    }
    @Test fun receiptRoundTripsAndRejectsCorruptOrFutureFormats(){
        assertEquals(LocalUpdateReceipt(17),LocalUpdateReceipt.decode(LocalUpdateReceipt(17).encode()))
        assertEquals(LocalUpdateReceipt(),LocalUpdateReceipt.decode(""))
        for(raw in listOf("{\"version\":1,\"lastAcknowledged\":-1}","{\"version\":2,\"lastAcknowledged\":17}","{\"version\":1,\"lastAcknowledged\":1.5}","{\"version\":1,\"lastAcknowledged\":17,\"url\":\"https://example.invalid\"}"))assertTrue(runCatching{LocalUpdateReceipt.decode(raw)}.isFailure)
    }
    @Test fun viewingOrCancellingDoesNotAcknowledgeAndUnknownBuildsAreNotTreatedAsUpdates(){
        val receipt=LocalUpdateReceipt();repeat(2){assertNotNull(LocalReleaseNotes.pending(17,receipt))}
        assertEquals(0L,receipt.lastAcknowledged);assertNull(LocalReleaseNotes.forVersion(99))
        assertTrue(runCatching{receipt.acknowledge(0)}.isFailure)
    }
}
