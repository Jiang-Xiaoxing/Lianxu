package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryForgettingAndroidTest {
    private fun store()=Store(InstrumentationRegistry.getInstrumentation().targetContext,"forgetting-${System.nanoTime()}.db")
    @Test fun recallDoesNotVersionAndRollbackDoesNotReinforce():Unit=runBlocking {
        store().use{s->s.addConfigured(Friend("r","合成角色","成年虚构"),RolePrompts(characterDefinition="成年合成测试角色"));val repo=ProcessingRepository(s)
            val m=StoredMemory("m","r",MemoryCategory.EXPERIENCE_MEMORY,"合成原话",manual=true,about=MemoryAbout.BOTH,createdAt=0)
            repo.tx{repo.saveMemory(it,m)}
            val before=repo.history("r","m").size
            repo.tx{MemoryForgetting.recalled(it,"r","m",86400000,RecallSource.PRIVATE_LIFE)}
            repo.tx{MemoryForgetting.recalled(it,"r","m",86400001,RecallSource.HISTORY_HIT)}
            assertEquals(1,repo.read{MemoryForgetting.state(it,m,86400001).count});assertEquals(before,repo.history("r","m").size)
            try{repo.tx{MemoryForgetting.recalled(it,"r","m",2*86400000,RecallSource.TOOL_HIT);error("rollback")}}catch(_:IllegalStateException){}
            assertEquals(1,repo.read{MemoryForgetting.state(it,m,2*86400000).count})
        }
    }
    @Test fun backgroundFillPreservesProtectedOriginals():Unit=runBlocking {
        store().use{s->
            s.addConfigured(Friend("r","合成角色","成年虚构"),RolePrompts(characterDefinition="成年合成测试角色"))
            val repo=ProcessingRepository(s);val ledger=ChatLedger(s)
            val turn=ledger.begin("r","合成补写触发","unused");check(ledger.finish(turn.id,turn.attempt,"合成回复"))
            val originals=listOf(
                StoredMemory("manual","r",MemoryCategory.USER_PROFILE,"晚星对芒果过敏",manual=true,core=true),
                StoredMemory("locked","r",MemoryCategory.USER_PROFILE,"晚星对芒果过敏",locked=true))
            repo.tx{db->originals.forEach{repo.saveMemory(db,it)}}
            val batches=MemoryBatchRepository(repo)
            val work=batches.prepare("r",repo.sources("r"),"synthetic")
            batches.beginAttempt(work,"test")
            val filled=work.copy(gistFill=originals.map{JSONObject().put("target",it.id).put("gist","她有样水果不能吃").put("trace","芒果").put("importance","TRIVIAL")})
            assertTrue(batches.complete(filled,emptySet(),0,work.id))
            originals.forEach{old->
                val current=repo.memories("r").single{it.id==old.id}
                assertEquals(old,current.copy(gist=null,trace=null,version=old.version))
                assertEquals(old.version+1,current.version)
                assertEquals(listOf(current,old),repo.history("r",old.id))
                assertEquals("她有样水果不能吃",MemoryForgetting.text(current,40.0){current.text})
                assertEquals("芒果",MemoryForgetting.text(current,10.0){current.text})
                assertEquals(3650.0,repo.read{MemoryForgetting.state(it,current,current.createdAt).halfLife},0.0)
            }
        }
    }
    @Test fun ordinaryFillVersionsHistoryAndReclassifies():Unit=runBlocking {
        store().use{s->
            s.addConfigured(Friend("r","合成角色","成年虚构"),RolePrompts(characterDefinition="成年合成测试角色"))
            val repo=ProcessingRepository(s);val ledger=ChatLedger(s)
            val turn=ledger.begin("r","触发整理","unused");check(ledger.finish(turn.id,turn.attempt,"回复"))
            val old=StoredMemory("ordinary","r",MemoryCategory.EXPERIENCE_MEMORY,"原文",about=MemoryAbout.BOTH,gist="已有大意",importance=MemoryImportance.NORMAL)
            repo.tx{repo.saveMemory(it,old)}
            val batches=MemoryBatchRepository(repo);val work=batches.prepare("r",repo.sources("r"),"synthetic")
            batches.beginAttempt(work,"test")
            assertTrue(batches.complete(work.copy(gistFill=listOf(JSONObject().put("target",old.id).put("gist","新大意").put("trace","影").put("importance","TRIVIAL"))),emptySet(),0,work.id))
            val current=repo.memories("r").single()
            assertEquals(old.version+1,current.version);assertEquals(MemoryImportance.TRIVIAL,current.importance)
            assertEquals("新大意",current.gist);assertEquals("影",current.trace);assertEquals(old.text,current.text)
            assertEquals(listOf(current,old),repo.history("r",old.id))
            assertEquals(21.0,repo.read{MemoryForgetting.state(it,current,current.createdAt).halfLife},0.0)
        }
    }
    @Test fun automaticCoreFillUsesOrdinaryVersionedPath():Unit=runBlocking {
        store().use{s->
            s.addConfigured(Friend("r","合成角色","成年虚构"),RolePrompts(characterDefinition="成年合成测试角色"))
            val repo=ProcessingRepository(s);val ledger=ChatLedger(s)
            val turn=ledger.begin("r","触发整理","unused");check(ledger.finish(turn.id,turn.attempt,"回复"))
            val old=StoredMemory("ordinary","r",MemoryCategory.EXPERIENCE_MEMORY,"原文",about=MemoryAbout.BOTH,core=true,manual=false,locked=false,gist="已有大意",importance=MemoryImportance.HIGH)
            repo.tx{repo.saveMemory(it,old)}
            val batches=MemoryBatchRepository(repo);val work=batches.prepare("r",repo.sources("r"),"synthetic")
            batches.beginAttempt(work,"test")
            assertTrue(batches.complete(work.copy(gistFill=listOf(JSONObject().put("target",old.id).put("gist","新大意").put("trace","影").put("importance","TRIVIAL"))),emptySet(),0,work.id))
            val current=repo.memories("r").single()
            assertEquals(old.version+1,current.version);assertEquals(MemoryImportance.TRIVIAL,current.importance)
            assertEquals("新大意",current.gist);assertEquals("影",current.trace);assertEquals(old.text,current.text)
            assertEquals(listOf(current,old),repo.history("r",old.id))
            assertTrue(current.core);assertFalse(current.manual);assertFalse(current.locked)
            assertEquals(120.0,repo.read{MemoryForgetting.state(it,current,current.createdAt).halfLife},0.0)
        }
    }
    @Test fun optionalMetadataAndIdentityCollision():Unit=runBlocking {
        store().use{s->s.addConfigured(Friend("r","合成角色","成年虚构"),RolePrompts(characterDefinition="成年合成测试角色"));val repo=ProcessingRepository(s)
            val m=StoredMemory("m","r",MemoryCategory.USER_PROFILE,"原文保留",createdAt=0)
            val updated=MemoryForgetting.metadata(m,JSONObject().put("importance","BAD").put("gist",42).put("trace","字".repeat(20)),null)
            assertNull(updated.importance);assertNull(updated.gist);assertEquals(8,updated.trace!!.length);assertEquals(updated,StoredMemory.from(updated.json()))
            repo.tx{MemoryForgetting.put(it,m,MemoryRecallState(0,4,500.0,30.0))}
            val replacement=m.copy(createdAt=123)
            val state=repo.read{MemoryForgetting.state(it,replacement,999)}
            assertEquals(123L,state.at);assertEquals(0,state.count);assertEquals(100.0,state.anchor,0.0)
        }
    }
    @Test fun selectingCorePreservesAllContentVersions():Unit=runBlocking {
        store().use{s->s.addConfigured(Friend("r","合成角色","成年虚构"),RolePrompts(characterDefinition="成年合成测试角色"));val repo=ProcessingRepository(s)
            val values=(1..500).map{StoredMemory("m$it","r",MemoryCategory.EXPERIENCE_MEMORY,"合成经历原话".repeat(20),about=MemoryAbout.BOTH,createdAt=0,importance=MemoryImportance.entries[it%3],gist="一起做过一件事",trace="旧事")}
            repo.tx{db->values.forEach{repo.saveMemory(db,it)}}
            val versions=repo.memories("r").associate{it.id to it.version}
            repo.tx{db->val clarity=values.associate{it.id to (it.id.drop(1).toInt()%101).toDouble()};val selected=MemoryForgetting.core(repo,db,"r",values,clarity)
                val body=selected.joinToString("\n\n"){"核心记忆 ${it.id}@${it.version}:\n"+MemoryForgetting.text(it,clarity.getValue(it.id)){repo.describe(it)}}
                println("memory_core_bytes=${body.toByteArray().size} memory_before_bytes=${values.joinToString("\n\n"){repo.describe(it)}.toByteArray().size}")
                assertTrue(body.toByteArray().size<=6144)
            }
            assertEquals(versions,repo.memories("r").associate{it.id to it.version})
        }
    }
}
