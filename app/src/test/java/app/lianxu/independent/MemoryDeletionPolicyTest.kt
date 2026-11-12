package app.lianxu.independent

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MemoryDeletionPolicyTest {
    private fun original()=StoredMemory("m","f",MemoryCategory.EXPERIENCE,"喜欢茶。",core=true,manual=true,createdAt=1)
    private fun proof(turn:Long,at:Long)=MemoryEvidence(SourceRef("f",turn,1),"user","喜欢茶","喜欢茶",at,"Asia/Shanghai")
    /** 删除时间 100；旧证据 turn=1（删前就有），新证据 turn=9 在删除之后。 */
    private fun recorded()=deletionVersion(original().copy(manual=false,evidence=listOf(proof(1,10))),100)
    @Test fun oldSourceWithSameTextIsBlocked(){val m=recorded();assertTrue(deletionBlocks(m,"f","new-id","喜欢茶。",listOf(proof(1,10))));assertTrue(deletionBlocks(m,"f","new-id","喜欢茶。",listOf(proof(5,60))));assertTrue(deletionBlocks(m,"f","new-id","喜欢茶。",listOf(proof(1,10),proof(9,200))))}
    @Test fun newTurnAfterDeletionMayRecordAgain(){val m=recorded();assertFalse(deletionBlocks(m,"f","new-id"," 喜欢茶． ",listOf(proof(9,200))))}
    @Test fun updatingDeletedIdIsStillBlockedEvenWithNewEvidence(){val m=recorded();assertTrue(deletionBlocks(m,"f","m","喜欢茶。",listOf(proof(9,200))))}
    @Test fun tombstoneWithoutDeletedAtOnlyChecksSharedSources(){val m=recorded().copy(deletedAt=null);assertFalse(deletionBlocks(m,"f","new-id","喜欢茶。",listOf(proof(9,200))));assertTrue(deletionBlocks(m,"f","new-id","喜欢茶。",listOf(proof(1,10))))}
    @Test fun deletedAtRoundTripsAndOldJsonHasNone(){val m=recorded();assertEquals(100L,StoredMemory.from(m.json()).deletedAt);val j=m.json();j.remove("deletedAt");assertNull(StoredMemory.from(j).deletedAt)}
    @Test fun terminalVersionPreservesOriginalAndHistoryIdentity(){val old=original();val m=deletionVersion(old,5);assertEquals(5L,m.deletedAt);assertTrue(old.valid);assertEquals(2,m.version);assertEquals(old.text,m.text);assertEquals(old.id,m.id);assertFalse(m.valid);assertTrue(m.userDeleted);assertEquals(MemoryLife.RETRACTED,m.life)}
    @Test fun repeatedAddAndTargetedUpdateAreBlockedOnlyForSameRole(){val m=deletionVersion(original(),100);assertTrue(deletionBlocks(m,"f","new-id","　喜欢茶．  ",emptyList()));assertTrue(deletionBlocks(m,"f","m","完全不同",emptyList()));assertFalse(deletionBlocks(m,"other","m",m.text,emptyList()));assertFalse(deletionBlocks(m,"f","new-id","喜欢咖啡",emptyList()));assertFalse(deletionBlocks(original(),"f","m",m.text,emptyList()))}
    @Test fun vectorCleanupPreservesOtherEntries(){val j=JSONObject(deletionVectors("""{"m":{"vector":[1]},"other":{"vector":[2]}}""","m"));assertFalse(j.has("m"));assertEquals(2,j.getJSONObject("other").getJSONArray("vector").getInt(0))}
    @Test fun graphCleanupRemovesBothDirectionsOnly(){val a=JSONArray(deletionEdges("""[{"source":"m","target":"a"},{"source":"b","target":"m"},{"source":"a","target":"b"}]""","m"));assertEquals(1,a.length());assertEquals("a",a.getJSONObject(0).getString("source"))}
    @Test fun recalledTombstoneIsExcludedEvenWithPerfectVectorScore(){val m=deletionVersion(original(),100);assertTrue(MemoryRecall.select("f","喜欢茶",listOf(m),emptyList(),15,mapOf("m@2" to 1.0)).isEmpty())}
    @Test fun summaryGuardRejectsBeforeDeletionAndAllowsAfterFinish(){val guard=MemorySummaryGuard();val ticket=guard.begin("f",ChatAttemptLease(),10000){}!!;assertEquals("MEMORY_SUMMARIZING",(runCatching{guard.requireEditable("f")}.exceptionOrNull() as ProcessingRejected).code);guard.finish(ticket);guard.requireEditable("f")}
    @Test fun backupContainerAndRemappingPreserveTombstoneAndBlock(){
        val m=deletionVersion(original(),100)
        val root=JsonParser.parseString("""{"scope":"all","roles":[],"preferences":{},"models":[],"selections":{},"includesKeys":false}""").asJsonObject
        root.getAsJsonArray("roles").add(JsonParser.parseString(m.json().toString()).asJsonObject.apply{add("tables",com.google.gson.JsonObject());add("preferences",com.google.gson.JsonObject())})
        val out=ByteArrayOutputStream();BackupArchive.write(root,emptyMap(),out)
        val read=BackupArchive.read(out.toByteArray().inputStream()).roles[0]
        val remapped=BackupRemapper("f","restored",mapOf("m" to "restored-m"),emptyMap()).json(read)
        val saved=StoredMemory.from(JSONObject(remapped.toString()))
        assertTrue(saved.userDeleted);assertFalse(saved.valid);assertTrue(deletionBlocks(saved,"restored","new"," 喜欢茶． ",emptyList()));assertEquals(m.deletedAt,saved.deletedAt);assertFalse(deletionBlocks(saved,"restored","new"," 喜欢茶． ",listOf(proof(99,500))));assertTrue(deletionBlocks(saved,"restored","new"," 喜欢茶． ",listOf(proof(98,50))));assertEquals(ChatMemoryPeek.Deleted,memoryPeekOf(saved))
    }
    @Test fun oldBackupContainerWithoutMarkerStillReads(){
        val j=original().json();j.remove("userDeleted")
        val root=JsonParser.parseString("""{"scope":"all","roles":[],"preferences":{},"models":[],"selections":{},"includesKeys":false}""").asJsonObject
        root.getAsJsonArray("roles").add(JsonParser.parseString(j.toString()).asJsonObject.apply{add("tables",com.google.gson.JsonObject());add("preferences",com.google.gson.JsonObject())})
        val out=ByteArrayOutputStream();BackupArchive.write(root,emptyMap(),out)
        assertFalse(StoredMemory.from(JSONObject(BackupArchive.read(out.toByteArray().inputStream()).roles[0].toString())).userDeleted)
    }
    @Test fun archiveCopyRemapsIdentityWithoutLosingDeletion(){val m=StoredMemory.from(deletionVersion(original(),100).json());val saved=StoredMemory.from(m.copy(id="new",friendId="restored").json());assertTrue(deletionBlocks(saved,"restored","new","changed",emptyList()));assertFalse(saved.valid)}
}
