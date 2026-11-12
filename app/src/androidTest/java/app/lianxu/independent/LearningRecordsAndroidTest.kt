package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class LearningRecordsAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var fixture:LearningRecordsFixture
    private lateinit var database:String

    @Before fun setup():Unit=runBlocking {
        database="learning-records-${System.nanoTime()}.db"
        store=Store(context,database)
        store.add(listOf(Friend("a","合成甲","测试"),Friend("b","合成乙","测试")),false)
        fixture=LearningRecordsFixture(store)
    }
    @After fun close(){store.close();context.deleteDatabase(database)}

    @Test fun readOnlyRecordsKeepRolesAndNativeReasoningSeparate():Unit=runBlocking {
        val first=fixture.proposeHabit("a","甲的原话：今天先听我说")
        fixture.proposeHabit("b","乙的私有原话：不应出现在甲的记录")
        val before=storedRows()
        val record=fixture.core.learningRecords("a").single()
        assertEquals("candidate",record.state.state)
        assertEquals(first.userMessageId,record.changes.single().sourceId)
        assertEquals("propose",record.changes.single().phase)
        assertEquals("甲的原话：今天先听我说",record.evidence.single().text)
        assertTrue(record.evidence.single().mine)
        assertEquals("fact",record.evidence.single().kind)
        assertEquals(record.evidence.single().created,requireNotNull(record.updatedAt))
        assertFalse(record.toString().contains("乙的私有原话"))
        assertFalse(record.toString().contains("NATIVE_REASONING_NOT_LEARNING_EVIDENCE"))
        assertTrue(fixture.core.learningRecords("missing-role").isEmpty())
        assertEquals(before,storedRows())
    }

    @Test fun revisionsAndFeedbackPauseShowTheirRealSources():Unit=runBlocking {
        val first=fixture.proposeHabit()
        val revised=fixture.reviseHabit(first)
        val active=fixture.core.learningRecords("a").single()
        assertEquals("active",active.state.state)
        assertEquals(listOf("propose","revise"),active.changes.map{it.phase})
        assertEquals("先倾听；说完后询问是否需要建议",active.changes.last().content)
        assertTrue(active.changes.last().evidence.any{it.kind=="action"&&it.messageId==first.assistantMessageId&&!it.mine})
        val interpretation=active.changes.last().evidence.single{it.kind=="interpretation"}
        assertFalse(interpretation.mine)
        assertEquals(first.userMessageId,interpretation.messageId)
        assertTrue(interpretation.text.contains("重视当前交流而不冒充用户情绪"))
        val used=fixture.event(input="我又遇到烦心事了"){wire,_->
            wire.put("usedLearningIds",JSONArray().put(active.state.id))
                .put("consideredLearningIds",JSONArray().put(active.state.id))
        }
        val feedback=fixture.event(input="刚才这招不合适，这次我想直接听建议"){wire,_->
            wire.put("feedback",JSONArray().put(JSONObject().put("actionMessage",used.assistantMessageId)
                .put("verdict","unhelpful").put("quote","刚才这招不合适")))
        }
        val paused=fixture.core.learningRecords("a").single()
        assertEquals("paused",paused.state.state)
        assertEquals(listOf(first.userMessageId,revised.userMessageId,feedback.userMessageId),paused.changes.map{it.sourceId})
        assertEquals(listOf("propose","revise","pause"),paused.changes.map{it.phase})
        assertTrue(paused.changes.last().reason.contains("刚才这招不合适"))
        assertEquals(setOf(feedback.userMessageId,used.assistantMessageId),paused.changes.last().evidence.map{it.messageId}.toSet())
        assertEquals(paused.state.content,paused.changes.last().content)
    }

    @Test fun explicitPausePreservesContentAndWithdrawalRemovesTheRecord():Unit=runBlocking {
        val first=fixture.proposeHabit()
        val original=fixture.core.learningRecords("a").single()
        val stopped=fixture.event(input="先暂停这种相处方式"){wire,c->
            wire.put("learning",fixture.learning(original.state.id,"pause",JSONArray().put(fixture.evidence(c.current.turnId)),"暂停说明不能覆盖原内容"))
        }
        val paused=fixture.core.learningRecords("a").single()
        assertEquals("paused",paused.state.state)
        assertEquals(original.state.content,paused.changes.last().content)
        assertEquals(stopped.userMessageId,paused.changes.last().sourceId)
        fixture.proposeHabit("b","乙仍然有效的独立经历")
        fixture.core.forget("a",first.userMessageId)
        assertTrue(fixture.core.learningRecords("a").isEmpty())
        assertEquals(1,fixture.core.learningRecords("b").size)
        store.writableDatabase.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE friend_id='b'")
        assertTrue(fixture.core.learningRecords("b").isEmpty())
    }

    @Test fun failedReplyAndDiscardedCandidateDoNotBecomeActionOrHistory():Unit=runBlocking {
        val first=fixture.event(input="这次有真实输入但回复没有完成",commit=false){wire,c->
            wire.put("learning",fixture.learning("","propose",JSONArray().put(fixture.evidence(c.current.turnId))))
        }
        val proposal=fixture.core.cached(first)!!
        val flow=requireNotNull(proposal.understanding)
        val rejected=flow.copy(learning=requireNotNull(flow.learning).copy(content="不应展示的旧失败草稿"),revision=99)
        store.writableDatabase.execSQL("UPDATE companion_sources SET proposal=? WHERE role=? AND source_id=?",
            arrayOf<Any>(Gson().toJson(proposal.copy(understandingHistory=listOf(rejected))),"a",first.userMessageId))
        val records=fixture.core.learningRecords("a")
        assertEquals(1,records.single().changes.size)
        assertEquals("candidate",records.single().state.state)
        assertTrue(records.single().evidence.all{it.kind=="fact"&&it.mine})
        assertFalse(records.toString().contains("不应展示的旧失败草稿"))
        assertFalse(records.toString().contains("合成回复："))
        assertNull(first.assistantMessageId)
    }

    private fun storedRows()=listOf("messages","companion_sources","companion_state","companion_uses","companion_receipts").associateWith { table ->
        store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use { c -> buildList {
            while(c.moveToNext())add((0 until c.columnCount).map{if(c.isNull(it))null else c.getString(it)})
        }}
    }
}
