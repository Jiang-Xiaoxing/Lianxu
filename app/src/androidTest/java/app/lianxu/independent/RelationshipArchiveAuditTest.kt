package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RelationshipArchiveAuditTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var core:CompanionRepository
    private lateinit var transfer:RoleArchiveRepository
    private lateinit var name:String
    @Before fun setup():Unit=runBlocking {
        name="relationship-archive-audit-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);core=CompanionRepository(store);transfer=RoleArchiveRepository(store)
        store.addConfigured(Friend("r","合成角色","测试"),RolePrompts(characterDefinition="成年合成测试角色",relationTemplate="DIRECT",relationStart="FRIENDS"))
        store.addConfigured(Friend("other","包内角色","测试"),RolePrompts(characterDefinition="成年合成测试角色",relationTemplate="QUIET",relationStart="NEW"))
        val ledger=ChatLedger(store);val turn=ledger.begin("r","原有合成原话","unused");val ref=core.sourceFor(turn)
        val snapshot=core.snapshot("r");val at=AppServices.companionClock()
        val proposal=ExperienceProposal(relationInput=RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",v=.9),timingReferenceAt=at)
        check(core.accept(turn,proposal,snapshot){true});check(ledger.finish(turn.id,1,"合成回复"));repo.tx{core.finish(it,turn,snapshot,proposal)}
        core.changeRelationshipParameters("r",RelationTemplate.MATURE.parameters,"成熟理性")
        repo.tx{db->val h=RelationStorage.read(db,"r");RelationStorage.write(db,"r",h.copy(corrections=listOf(RelationCorrection(ref,at,false)),acceptedProposals=listOf(ref)))
            repo.saveMemory(db,StoredMemory("old-memory","r",MemoryCategory.USER_PROFILE,"旧合成记忆",manual=true))
            db.execSQL("INSERT OR REPLACE INTO preferences(key,value) VALUES('memory-core-set:r','[\"old-memory\"]')")
            db.execSQL("INSERT OR REPLACE INTO companion_blocks(role,source_id) VALUES('r',999999)")
        }
        core.relationship("r")
    }
    @After fun close(){store.close();context.deleteDatabase(name)}
    private suspend fun plan(records:Boolean)=transfer.preview(RoleArchive.validate(transfer.export("other",records).toString().toByteArray()),"r")
    @Test fun recordsClearAllOldProcessButPreserveEffectivePersonality():Unit=runBlocking {
        val parameters=core.relationship("r").parameters
        transfer.commit(plan(true),true)
        repo.read{db->
            val h=RelationStorage.read(db,"r");assertTrue(h.corrections.isEmpty());assertTrue(h.programInputs.isEmpty());assertTrue(h.acceptedProposals.isEmpty())
            assertEquals(1,h.changes.size);assertEquals(parameters,h.changes.single().old);assertEquals(parameters,h.changes.single().new);assertEquals(0L,h.changes.single().afterMessage)
            for(table in listOf("relationship_snapshots","companion_sources","companion_blocks","companion_state"))assertEquals(0,db.rawQuery("SELECT COUNT(*) FROM $table WHERE role='r'",null).use{it.moveToFirst();it.getInt(0)})
            assertEquals(0,db.rawQuery("SELECT COUNT(*) FROM memory_recall_state WHERE friend_id='r'",null).use{it.moveToFirst();it.getInt(0)})
            val prompts=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key("r"))).use{it.moveToFirst();RolePromptStore.decode(it.getString(0))}
            assertEquals("DIRECT",prompts.relationTemplate);assertEquals("FRIENDS",prompts.relationStart)
        }
        assertEquals(parameters,core.relationship("r").parameters);assertTrue(core.relationship("r").state.ledger.isEmpty())
    }
    @Test fun roleOnlyPreservesAndFailureRollsBack():Unit=runBlocking {
        val history=repo.read{RelationStorage.read(it,"r")};val memories=repo.memories("r")
        val snapshot=repo.read{DatabaseRelationSnapshots(it,"r").headers()}
        transfer.commit(plan(false),true)
        assertEquals(history,repo.read{RelationStorage.read(it,"r")});assertEquals(snapshot,repo.read{DatabaseRelationSnapshots(it,"r").headers()})
        try{transfer.commit(plan(true),true,checkpoint={error("synthetic rollback")});fail()}catch(_:IllegalStateException){}
        assertEquals(history,repo.read{RelationStorage.read(it,"r")});assertEquals(memories,repo.memories("r"));assertEquals(snapshot,repo.read{DatabaseRelationSnapshots(it,"r").headers()})
    }
}
