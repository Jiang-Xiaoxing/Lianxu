package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class LocalUpdatesTest {
    @Test fun coreBuildHasItsOwnBundledNotice(){
        val note=LocalReleaseNotes.forVersion(28)!!
        assertEquals("0.22-companion-core",note.version)
        assertNotNull(LocalReleaseNotes.pending(28,LocalUpdateReceipt()))
        assertNull(LocalReleaseNotes.pending(28,LocalUpdateReceipt().acknowledge(28)))
    }
    @Test fun companionBuildHasBundledFactsAndCanAcknowledgeOnce(){
        val note=LocalReleaseNotes.forVersion(26)!!
        assertEquals("0.20-companion-stage1",note.version)
        assertTrue(note.limits.any{"真实模型" in it&&"尚未验收" in it})
        assertNull(LocalReleaseNotes.pending(26,LocalUpdateReceipt().acknowledge(26)))
    }
    @Test fun readOnceMatchesOnlyTheInstalledKnownVersion(){
        val empty=LocalUpdateReceipt();assertEquals(17L,LocalReleaseNotes.pending(17,empty)!!.code)
        assertNull(LocalReleaseNotes.pending(17,empty.acknowledge(17)));assertNull(LocalReleaseNotes.pending(20,empty))
        assertEquals(18L,LocalReleaseNotes.pending(18,empty.acknowledge(17))!!.code)
        assertNull(LocalReleaseNotes.pending(18,empty.acknowledge(18)))
        assertEquals(19L,LocalReleaseNotes.pending(19,empty.acknowledge(18))!!.code)
        assertNull(LocalReleaseNotes.pending(19,empty.acknowledge(19)))
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
    @Test fun installedBuildAlwaysCarriesItsOwnNotice(){
        val note=LocalReleaseNotes.forVersion(BuildConfig.VERSION_CODE.toLong())
        assertNotNull("每个安装包都必须有随包更新说明，否则设置里的更新记录和首次提示都会缺这一版",note)
        assertEquals(BuildConfig.VERSION_NAME,note!!.version)
        assertTrue(note.changes.isNotEmpty())
    }
    @Test fun bundledNoticeCodesAreUniqueAndDescending(){
        val codes=LocalReleaseNotes.history(Long.MAX_VALUE).map{it.code}
        assertEquals(codes.distinct(),codes)
        assertEquals(codes.sortedDescending(),codes)
    }
    @Test fun viewingOrCancellingDoesNotAcknowledgeAndUnknownBuildsAreNotTreatedAsUpdates(){
        val receipt=LocalUpdateReceipt();repeat(2){assertNotNull(LocalReleaseNotes.pending(17,receipt))}
        assertEquals(0L,receipt.lastAcknowledged);assertNull(LocalReleaseNotes.forVersion(99))
        assertTrue(runCatching{receipt.acknowledge(0)}.isFailure)
    }
}
