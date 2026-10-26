package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Same-source sequential choices, with synthetic local data and no model client. */
class SourceChangesSequenceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var changes:SourceChanges
    private lateinit var name:String
    @Before fun setup()=runBlocking {
        name="source-sequence-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);changes=SourceChanges(repo)
        store.add(listOf(Friend("a","Synthetic A","Synthetic source tests"),Friend("b","Synthetic B","Unrelated role")),false)
    }
    @After fun cleanup(){store.close();context.deleteDatabase(name)}
    private suspend fun seed():Pair<SourceDialogue,StoredMemory>{
        val ledger=ChatLedger(store);val turn=ledger.begin("a","Synthetic original preference","unused")
        assertTrue(ledger.finish(turn.id,1,"Synthetic original reply"))
        val source=repo.sources("a").last()
        val memory=StoredMemory("memory-${source.source.turnId}","a",MemoryCategory.STATE_FACT,"Synthetic retained text",about=MemoryAbout.USER,evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{repo.saveMemory(it,memory)}
        return source to memory
    }
    private suspend fun change(source:SourceDialogue,text:String,choice:SourceMemoryChoice,retract:Boolean=false):SourceImpact {
        val impact=changes.preview("a",source.source.turnId,"user",text,retract).copy(choice=choice)
        changes.confirm(impact);return impact
    }
    private suspend fun saved(memory:StoredMemory)=repo.memories("a").single{it.id==memory.id}
    private suspend fun assertExcluded(memory:StoredMemory){assertFalse(repo.read{db->repo.usableMemories(db,"a").any{it.id==memory.id}})}
    private suspend fun assertStaleRetryDoesNotWrite(impact:SourceImpact,memory:StoredMemory){
        val history=repo.history("a",memory.id);val sources=changes.history("a",impact.dialogue.user.id);val candidates=repo.candidates("a")
        val error=runCatching{changes.confirm(impact)}.exceptionOrNull()
        assertTrue(error is ProcessingRejected);assertEquals("SOURCE_CHANGED",(error as ProcessingRejected).code)
        assertEquals(history,repo.history("a",memory.id));assertEquals(sources,changes.history("a",impact.dialogue.user.id));assertEquals(candidates,repo.candidates("a"))
    }
    private suspend fun keepThenInvalidate(retract:Boolean){
        val (source,memory)=seed()
        change(source,"Synthetic first edit",SourceMemoryChoice.KEEP)
        assertEquals(memory.copy(version=2,valid=false),saved(memory));assertExcluded(memory)
        val second=change(source,"Synthetic second edit",SourceMemoryChoice.INVALIDATE,retract)
        assertEquals(memory.copy(version=3,valid=false,life=MemoryLife.RETRACTED),saved(memory))
        assertEquals(listOf(3,2,1),repo.history("a",memory.id).map{it.version})
        assertEquals(listOf(MemoryLife.RETRACTED,MemoryLife.ACTIVE,MemoryLife.ACTIVE),repo.history("a",memory.id).map{it.life})
        assertEquals(listOf(false,false,true),repo.history("a",memory.id).map{it.valid})
        assertTrue(repo.history("a",memory.id).all{it.text==memory.text&&it.evidence==memory.evidence})
        val sourceHistory=changes.history("a",source.user.id)
        assertEquals(listOf(3L,2L,1L),sourceHistory.map{it.revision});assertEquals(retract,sourceHistory.first().deleted)
        assertExcluded(memory);assertStaleRetryDoesNotWrite(second,memory)
        if(!retract){
            change(source,"Synthetic third edit",SourceMemoryChoice.INVALIDATE)
            assertEquals(3,saved(memory).version);assertEquals(3,repo.history("a",memory.id).size)
            change(source,"Synthetic fourth edit",SourceMemoryChoice.KEEP)
            assertEquals(memory.copy(version=3,valid=false,life=MemoryLife.RETRACTED),saved(memory));assertExcluded(memory)
        }
    }
    @Test fun keepThenEditInvalidateRecordsRetractionOnce()=runBlocking{keepThenInvalidate(false)}
    @Test fun keepThenRetractInvalidateRecordsRetractionOnce()=runBlocking{keepThenInvalidate(true)}
    @Test fun repeatedKeepChangesSourceButNotUnchangedMemoryVersion()=runBlocking{
        val (source,memory)=seed();change(source,"First edit",SourceMemoryChoice.KEEP)
        val repeated=change(source,"Second edit",SourceMemoryChoice.KEEP)
        assertEquals(memory.copy(version=2,valid=false),saved(memory));assertEquals(2,repo.history("a",memory.id).size)
        assertEquals(3,changes.history("a",source.user.id).size);assertExcluded(memory);assertStaleRetryDoesNotWrite(repeated,memory)
    }
    @Test fun manualAndLockedRecordsRemainProtectedAcrossEditAndRetraction()=runBlocking{
        for(retract in listOf(false,true)){
            val (source,ordinary)=seed()
            val protected=listOf(ordinary.copy(id="manual-${ordinary.id}",manual=true),ordinary.copy(id="locked-${ordinary.id}",locked=true),ordinary.copy(id="both-${ordinary.id}",manual=true,locked=true))
            repo.tx{db->protected.forEach{repo.saveMemory(db,it)}}
            change(source,"First protected edit",SourceMemoryChoice.KEEP)
            val second=change(source,"Second protected edit",SourceMemoryChoice.INVALIDATE,retract)
            for(memory in protected){
                assertEquals(memory.copy(version=2,valid=false),saved(memory))
                assertEquals(listOf(2,1),repo.history("a",memory.id).map{it.version});assertExcluded(memory)
                val reviews=repo.candidates("a").filter{it.targetId==memory.id}
                assertEquals(2,reviews.size);assertTrue(reviews.all{!it.valid&&it.targetVersion==2&&it.proposed.life==MemoryLife.ACTIVE})
                assertStaleRetryDoesNotWrite(second,memory)
            }
            assertTrue(repo.memories("b").isEmpty());assertTrue(repo.candidates("b").isEmpty())
        }
    }
}
