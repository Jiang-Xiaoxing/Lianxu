package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test
class MemoryDeletionTest {
    @Test fun punctuationAndWhitespaceNormalize(){assertEquals(deletedText("喜欢茶。"),deletedText("  喜欢茶．  "))}
    @Test fun deletedCapsuleNeverShowsBody(){assertEquals(ChatMemoryPeek.Deleted,memoryPeekOf(StoredMemory("m","f",MemoryCategory.EXPERIENCE,"秘密",createdAt=1,userDeleted=true)))}
    @Test fun oldJsonDefaultsToNotDeleted(){val m=StoredMemory("m","f",MemoryCategory.EXPERIENCE,"茶",createdAt=1);val j=m.json();j.remove("userDeleted");assertFalse(StoredMemory.from(j).userDeleted)}
    @Test fun deletedJsonRoundTrips(){val m=StoredMemory("m","f",MemoryCategory.EXPERIENCE,"茶",createdAt=1,userDeleted=true,valid=false,life=MemoryLife.RETRACTED);assertEquals(m,StoredMemory.from(m.json()))}
    @Test fun deletingMemoryClearsTheSameSourceExperienceAndRelationEntry(){
        val input=RelationInput(SourceRef("f",3,1),0,0,"关于关系",p=.6)
        val raw=com.google.gson.Gson().toJson(ExperienceProposal(quote="我爱吃香菜",memory="他爱吃香菜",emotion="happy",relationship="appreciation",relationInput=input,relationAttempted=true))
        val cleared=com.google.gson.Gson().fromJson(deletionExperience(raw),ExperienceProposal::class.java)
        assertEquals("",cleared.memory);assertEquals("",cleared.quote);assertNull(cleared.relationInput);assertEquals("none",cleared.relationship)
        assertTrue(cleared.relationAttempted);assertTrue(cleared.relationIssues.isEmpty())
        assertNull(deletionExperience(deletionExperience(raw)!!))
    }
    @Test fun deletedPeekHasNoSavedHeader(){assertNull(memoryPeekHeader(ChatMemoryPeek.Deleted));assertEquals("已保存",memoryPeekHeader(ChatMemoryPeek.Saved("茶")))}
}
