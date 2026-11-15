package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class RecallContentBackupTest {
    private fun ref(kind:String="memory",id:String="m")=RecallContentRef(kind,id,1,"full","a".repeat(64),0,8)
    private fun raw(refs:List<RecallContentRef>)=Gson().toJson(listOf(ChatToolActivity("hit",1,"想起来了","success",tool="agent_memory_read",recallRefs=refs)))
    @Test fun backupPreservesMetadataAndMapsBothMemoryAndChatIds(){
        val saved=ChatToolJournal.forBackup(raw(listOf(ref(),ref("message","12"))),setOf("m"),setOf(12L))
        ChatToolJournal.validateBackup(saved,setOf("m"),setOf(12L))
        val mapped=ChatToolJournal.remapBackup(saved,mapOf("m" to "new-memory"),mapOf(12L to 42L))
        ChatToolJournal.validateBackup(mapped,setOf("new-memory"),setOf(42L))
        assertTrue(mapped.contains("new-memory"));assertTrue(mapped.contains("42"));assertFalse(mapped.contains("\"12\""))
    }
    @Test fun missingExportOrImportReferencesAreDroppedRatherThanMispointed(){
        assertFalse(ChatToolJournal.forBackup(raw(listOf(ref())),emptySet()).contains("recallRefs"))
        assertFalse(ChatToolJournal.remapBackup(raw(listOf(ref("message","12"))),emptyMap()).contains("recallRefs"))
    }
    @Test fun extraTextFieldsAndInvalidMetadataAreRejected(){
        val good=raw(listOf(ref()))
        for(bad in listOf(good.replace("\"kind\":\"memory\"","\"body\":\"不得另存正文\",\"kind\":\"memory\""),good.replace("\"length\":8","\"length\":9000"),good.replace("\"version\":1","\"version\":1.5"))){
            assertTrue(runCatching{ChatToolJournal.validateBackup(bad,setOf("m"))}.isFailure)
        }
        ChatToolJournal.validateBackup("""[{"id":"old","attempt":1,"title":"想起来了","state":"success"}]""",emptySet())
    }
    @Test fun partialMissingReferencesAndExplicitNullKeepTheirSafeBoundary(){
        val saved=ChatToolJournal.forBackup(raw(listOf(ref(),ref(id="missing"))),setOf("m"))
        ChatToolJournal.validateBackup(saved,setOf("m"));assertTrue(saved.contains("null"));assertTrue(saved.contains("\"id\":\"m\""))
        val nullValue="""[{"id":"old","attempt":1,"title":"想起来了","state":"success","recallRefs":null}]"""
        ChatToolJournal.validateBackup(nullValue,emptySet());ChatToolJournal.remapBackup(nullValue,emptyMap())
        val noMessage=ChatToolJournal.forBackup(raw(listOf(ref(),ref("message","12"))),setOf("m"),emptySet())
        ChatToolJournal.validateBackup(noMessage,setOf("m"),emptySet());assertFalse(noMessage.contains("\"12\""));assertTrue(noMessage.contains("\"m\""))
    }
}
