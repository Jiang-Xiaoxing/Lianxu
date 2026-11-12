package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class MemoryAuditAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var name:String
    @Before fun setup()=runBlocking {
        name="memory-audit-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store)
        store.addConfigured(Friend("r","合成角色","测试"),RolePrompts(characterDefinition="成年合成测试角色"))
    }
    @After fun close(){store.close();context.deleteDatabase(name)}
    @Test fun migrationPersistsIdentityAndIsIdempotent():Unit=runBlocking {
        val m=StoredMemory("old","r",MemoryCategory.USER_PROFILE,"手动原文",manual=true,createdAt=0)
        repo.tx{db->repo.saveMemory(db,m);val raw=m.json().apply{remove("createdAt")}.toString()
            db.execSQL("UPDATE memory_current SET json=? WHERE id='old'",arrayOf(raw))
            db.execSQL("UPDATE memory_history SET json=? WHERE memory_id='old'",arrayOf(raw))
            db.delete("memory_recall_state",null,null)
            MemoryForgetting.migrate(db,123456L)
        }
        val migrated=repo.memories("r").single();assertEquals(123456L,migrated.createdAt)
        assertEquals(m.text,migrated.text);assertEquals(1,migrated.version)
        repo.tx{MemoryForgetting.recalled(it,"r","old",123456L,RecallSource.TOOL_HIT)}
        val before=repo.read{MemoryForgetting.state(it,migrated,123456L)};assertEquals(1,before.count)
        repo.tx{MemoryForgetting.migrate(it,999999L)}
        assertEquals(migrated,repo.memories("r").single());assertEquals(before,repo.read{MemoryForgetting.state(it,migrated,123456L)})
        repo.manualSave("r","old","手动原文修正",MemoryCategory.USER_PROFILE,false,false)
        assertEquals(before,repo.read{MemoryForgetting.state(it,repo.memory(it,"r","old")!!,123456L)})
        assertFalse(repo.read{db->db.rawQuery("SELECT json FROM memory_history WHERE memory_id='old' AND version=1",null).use{it.moveToFirst();JSONObject(it.getString(0)).has("createdAt")}})
        repo.restore("r","old",1)
        assertEquals(before,repo.read{MemoryForgetting.state(it,repo.memory(it,"r","old")!!,123456L)})
    }
    @Test fun evidenceCreationAndReadOnlyConcurrentInitialization():Unit=runBlocking {
        val ledger=ChatLedger(store);val turn=ledger.begin("r","合成原话","unused");check(ledger.finish(turn.id,1,"合成回复"))
        val source=repo.sources("r").single()
        val m=StoredMemory("evidence","r",MemoryCategory.EXPERIENCE_MEMORY,"合成经历",about=MemoryAbout.BOTH,
            evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{db->repo.saveMemory(db,m);val raw=m.json().apply{remove("createdAt")}.toString()
            db.execSQL("UPDATE memory_current SET json=? WHERE id=?",arrayOf(raw,m.id));db.delete("memory_recall_state",null,null)
            MemoryForgetting.migrate(db,source.user.created+10000)
        }
        val current=repo.memories("r").single();assertEquals(source.user.created,current.createdAt)
        repo.tx{it.delete("memory_recall_state",null,null)}
        val now=current.createdAt+86400000
        val read=repo.read{MemoryForgetting.state(it,current,now)}
        assertEquals(0,repo.read{db->db.rawQuery("SELECT COUNT(*) FROM memory_recall_state",null).use{it.moveToFirst();it.getInt(0)}})
        coroutineScope{(1..8).map{async(Dispatchers.IO){repo.tx{db->
            assertEquals(read.clarity(now),MemoryForgetting.ensure(db,current,now).clarity(now),0.0)
        }}}.awaitAll()}
        assertEquals(1,repo.read{db->db.rawQuery("SELECT COUNT(*) FROM memory_recall_state",null).use{it.moveToFirst();it.getInt(0)}})
        coroutineScope{(1..8).map{async(Dispatchers.IO){repo.tx{MemoryForgetting.recalled(it,"r",m.id,now,RecallSource.TOOL_HIT)}}}.awaitAll()}
        assertEquals(1,repo.read{MemoryForgetting.state(it,current,now).count})
    }
    @Test fun centralizedImportanceAndOrdinaryEdit():Unit=runBlocking {
        val now=AppServices.companionClock()
        val m=StoredMemory("m","r",MemoryCategory.USER_PROFILE,"合成事实",createdAt=now-864000000,importance=MemoryImportance.TRIVIAL)
        val old=MemoryRecallState(m.createdAt,2,84.0,100.0,"same-day")
        repo.tx{repo.saveMemory(it,m);MemoryForgetting.put(it,m,old)}
        repo.tx{db->repo.saveMemory(db,m.copy(version=2,importance=MemoryImportance.HIGH))}
        val high=repo.read{MemoryForgetting.state(it,m,now)}
        assertEquals(14600.0,high.halfLife,0.0);assertEquals(old.clarity(high.at),high.anchor,0.0);assertEquals(2,high.count)
        repo.tx{db->repo.saveMemory(db,repo.memory(db,"r","m")!!.copy(version=3,text="改错字"))}
        assertEquals(high,repo.read{MemoryForgetting.state(it,m,now)})
        repo.tx{db->repo.saveMemory(db,repo.memory(db,"r","m")!!.copy(version=4,importance=MemoryImportance.TRIVIAL))}
        val low=repo.read{MemoryForgetting.state(it,m,now)};assertEquals(84.0,low.halfLife,0.0);assertEquals(high.clarity(low.at),low.anchor,0.0)
    }
    @Test fun finalPromptMeasuresActuallySelectedReferences():Unit=runBlocking {
        val ledger=ChatLedger(store);val turn=ledger.begin("r","电影经历的合成原话","unused");check(ledger.finish(turn.id,1,"合成回复"))
        val source=repo.sources("r").single();val now=AppServices.companionClock()
        repo.tx{db->(1..500).forEach{i->
            val m=StoredMemory("m$i","r",MemoryCategory.EXPERIENCE_MEMORY,"电影经历合成原话".repeat(20),about=MemoryAbout.BOTH,
                createdAt=now,importance=MemoryImportance.entries[i%3],gist="一起看过电影",trace="电影",
                evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
            repo.saveMemory(db,m);MemoryForgetting.put(db,m,MemoryRecallState(now,0,MemoryForgetting.importance(m).halfLife,(i%101).toDouble()))
        }}
        val refs=repo.references("r","电影",source.user.id)
        val ready=CompanionPrompt.build(Friend("r","合成角色","测试"),RolePrompts(characterDefinition="成年合成测试角色"),emptyList(),"电影",references=refs.valid,persona=UserPersona(address="阿星"),capacity=128000) as PromptBuild.Ready
        val core=ready.selectedReferences.filter{it.mandatory}.joinToString("\n\n"){"${it.label}:\n${it.text}"}
        val delivered=ready.request.messages.filter{it.memoryIds.isNotEmpty()}.joinToString("\n"){it.content}
        val guidance="[记忆使用]\n"+CompanionPrompt.REMEMBERING_GUIDANCE.replace("{{user}}","阿星")+"\n"
        println("final_prompt_fixture memories=500 selected=${ready.selectedReferences.size} core_bytes=${core.toByteArray().size} memory_reference_bytes=${delivered.toByteArray().size} memory_section_with_guidance_bytes=${delivered.toByteArray().size+guidance.toByteArray().size}")
        assertTrue(core.toByteArray().size<=MemoryForgetting.CORE_BYTES)
        assertTrue(ready.selectedReferences.isNotEmpty());assertTrue(ready.selectedReferences.all{it.text.isNotBlank()})
    }
}
