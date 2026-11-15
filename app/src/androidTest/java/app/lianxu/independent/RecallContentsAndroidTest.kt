package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RecallContentsAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var name:String
    private val repo get()=ProcessingRepository(store)
    private val db get()=store.writableDatabase
    @Before fun setup()=runBlocking{name="recall-content-${System.nanoTime()}.db";store=Store(context,name);store.add(listOf(Friend("a","甲","合成"),Friend("b","乙","合成")),false);Unit}
    @After fun close(){store.close();context.deleteDatabase(name)}
    private suspend fun memory(text:String="你喜欢雨天的安静。")=repo.manualSave("a",null,text,MemoryCategory.STATE_FACT,false,false,MemoryAbout.USER)
    private fun origin(m:StoredMemory,clarity:Double=100.0)=RecallContentOrigin("memory",m.id,m.version,if(clarity>60)"full"else if(clarity>=20)"gist"else"trace")
    private fun activity(refs:List<RecallContentRef?>?,tool:String="agent_memory_read")=ChatToolActivity("hit",1,"让我想一想","success",kind=ChatToolActivity.READ,tool=tool,recallGroup="g",recallRefs=refs)
    private suspend fun memoryRefs(m:StoredMemory,clarity:Double=100.0):List<RecallContentRef?>?{
        val capture=RecallContentCapture(repo,"a")
        val body=MemoryForgetting.text(m,clarity){repo.describe(m)}
        val rows=listOf(mapOf<String,Any>("id" to m.id,"source_type" to "companion_memory","content" to body.take(600)))
        return capture.capture(rows,mapOf("status" to "found","results" to rows),listOf(origin(m,clarity)))
    }
    private suspend fun parts(refs:List<RecallContentRef?>?,role:String="a")=repo.recallContents(role,listOf(activity(refs)))
    private suspend fun dialogue(text:String):ChatTurn {val ledger=ChatLedger(store);val turn=ledger.begin("a",text,"synthetic");check(ledger.finish(turn.id,turn.attempt,"合成回复"));return turn}
    private fun historyRow(turn:ChatTurn,text:String,start:Int=0,length:Int=text.length)=mapOf<String,Any>("message_id" to turn.userMessageId.toString(),"source_revision" to stableDigest(text),"snippet" to text.substring(start,start+length),"text_offset" to start,"next_text_offset" to start+length)
    @Test fun actualMemoryTextUsesOnlyTheDeliveredFragmentAndStoresNoExtraBody()=runBlocking{
        val text="只显示实际回想片段。".repeat(100);val m=memory(text);val refs=memoryRefs(m);assertNotNull(refs)
        val result=parts(refs).filterIsInstance<RecallContentPart.Text>().single().value
        assertTrue(text.startsWith(result));assertTrue(result.length<text.length);assertTrue(result.length<=600)
        assertFalse(Gson().toJson(refs).contains("只显示实际回想片段"))
    }
    @Test fun laterMemoryEditsKeepTheExactEarlierVersion()=runBlocking{
        val m=memory();val refs=memoryRefs(m)
        repo.manualSave("a",m.id,"后来改成喜欢晴天。",m.category,false,false,m.about)
        assertEquals(listOf(RecallContentPart.Text(m.text)),parts(refs))
    }
    @Test fun deletingMemoryHidesItWithoutReadingCorruptOldSnapshots()=runBlocking{
        val m=memory();val refs=memoryRefs(m);repo.deleteMemory("a",m.id)
        db.update("memory_history",ContentValues().apply{put("json","bad json")},"memory_id=? AND version=1",arrayOf(m.id))
        assertEquals(listOf(RecallContentPart.Deleted),parts(refs))
    }
    @Test fun gistAndTraceDoNotExpandIntoUnrecalledFullMemory()=runBlocking{
        val first=memory("不能额外展开的完整正文")
        val m=first.copy(version=2,gist="当时只回想起摘要",trace="雨天")
        repo.tx{repo.saveMemory(it,m)}
        assertEquals(listOf(RecallContentPart.Text(m.gist!!)),parts(memoryRefs(m,40.0)))
        assertEquals(listOf(RecallContentPart.Text(m.trace!!)),parts(memoryRefs(m,10.0)))
    }
    @Test fun eightAlreadyVisibleChatHitsRetainSafeSourceReferences()=runBlocking{
        val texts=(1..8).map{"合成旧聊天第${it}条，记得一起听雨。"}
        val capture=RecallContentCapture(repo,"a")
        val origins=mutableListOf<RecallContentOrigin>()
        val rows=texts.map{val turn=dialogue(it);origins+=RecallContentOrigin("message",turn.userMessageId.toString(),1,"full");historyRow(turn,it)}
        val output=mapOf<String,Any>("success" to true,"status" to "found","visible_duplicates" to 8,"results" to emptyList<Any>())
        val before=Gson().toJson(output)
        val refs=capture.capture(rows,output,origins);assertEquals(8,refs?.size)
        assertEquals(texts,parts(refs).filterIsInstance<RecallContentPart.Text>().map{it.value})
        assertEquals(before,Gson().toJson(output))
    }
    @Test fun normalHistorySnippetUsesItsOldVersionAfterAnEditAndHidesAfterRetraction()=runBlocking{
        val old="那次我们一起在窗边听雨。";val turn=dialogue(old);val row=historyRow(turn,old,2,8)
        val capture=RecallContentCapture(repo,"a")
        val refs=capture.capture(listOf(row),mapOf("status" to "found","results" to listOf(row)),listOf(RecallContentOrigin("message",turn.userMessageId.toString(),1,"full")))
        assertNotNull(refs)
        val changes=SourceChanges(repo);changes.confirm(changes.preview("a",turn.userMessageId,"user","改写后的聊天内容",false))
        assertEquals(listOf(RecallContentPart.Text(old.substring(2,10))),parts(refs))
        changes.confirm(changes.preview("a",turn.userMessageId,"user","",true))
        assertEquals(listOf(RecallContentPart.Deleted),parts(refs))
    }
    @Test fun crossRoleMissingRoleMissingVersionAndBadDigestNeverFallbackToLatest()=runBlocking{
        val m=memory();val ref=memoryRefs(m)!!.single()!!
        assertEquals(listOf(RecallContentPart.Unavailable),parts(listOf(ref),"b"))
        assertEquals(listOf(RecallContentPart.Unavailable),parts(listOf(ref),"missing"))
        assertEquals(listOf(RecallContentPart.Unavailable),parts(listOf(ref.copy(version=99))))
        assertEquals(listOf(RecallContentPart.Unavailable),parts(listOf(ref.copy(digest="0".repeat(64)))))
    }
    @Test fun legacyMissingDataAndMixedFailuresStayHonestAndReadsDoNotWrite()=runBlocking{
        val m=memory();val hit=activity(memoryRefs(m));val before=db.rawQuery("SELECT total_changes()",null).use{it.moveToFirst();it.getLong(0)}
        val rows=listOf(hit,hit.copy(id="old",recallRefs=null),hit.copy(id="miss",state="failed",recallRefs=null),hit.copy(id="error",state="failed",detail="操作未完成",recallRefs=null))
        val result=repo.recallContents("a",rows)
        assertTrue(result.contains(RecallContentPart.Text(m.text)));assertTrue(result.contains(RecallContentPart.Unavailable))
        assertTrue(result.contains(RecallContentPart.Status("没找到")));assertTrue(result.contains(RecallContentPart.Status("回想出错","操作未完成")))
        val unavailable=repo.recallContents("missing",rows)
        assertTrue(unavailable.contains(RecallContentPart.Status("没找到")));assertTrue(unavailable.contains(RecallContentPart.Status("回想出错","操作未完成")))
        assertEquals(before,db.rawQuery("SELECT total_changes()",null).use{it.moveToFirst();it.getLong(0)})
    }
    @Test fun changingOnlyTheRecordedRangeCannotRevealAdditionalText()=runBlocking{
        val m=memory("一段不会全部被展示的合成记忆。".repeat(50));val ref=memoryRefs(m)!!.single()!!
        assertEquals(listOf(RecallContentPart.Unavailable),parts(listOf(ref.copy(length=ref.length-1))))
        assertEquals(listOf(RecallContentPart.Unavailable),parts(listOf(ref.copy(start=1))))
    }
    @Test fun aChatEditedAwayAndBackBeforeCaptureKeepsTheCandidateVersion()=runBlocking{
        val old="最初实际命中的原话";val turn=dialogue(old)
        val capture=RecallContentCapture(repo,"a")
        val origin=RecallContentOrigin("message",turn.userMessageId.toString(),1,"full")
        val changes=SourceChanges(repo);changes.confirm(changes.preview("a",turn.userMessageId,"user","中间被改写",false));changes.confirm(changes.preview("a",turn.userMessageId,"user",old,false))
        val row=historyRow(turn,old);val refs=capture.capture(listOf(row),mapOf("status" to "found","results" to listOf(row)),listOf(origin))
        assertEquals(1,refs!!.single()!!.version);assertEquals(listOf(RecallContentPart.Text(old)),parts(refs))
    }
    @Test fun oneDeletedHitDoesNotDiscardTheOtherVerifiedHit()=runBlocking{
        val a=memory("还可读取的内容");val b=memory("随后删除的内容");val capture=RecallContentCapture(repo,"a")
        val rows=listOf(a,b).map{mapOf<String,Any>("id" to it.id,"source_type" to "companion_memory","content" to repo.describe(it).take(600))}
        repo.deleteMemory("a",b.id)
        val refs=capture.capture(rows,mapOf("status" to "found","results" to rows),listOf(origin(a),origin(b)));val result=parts(refs)
        assertTrue(result.contains(RecallContentPart.Text(a.text)));assertTrue(result.contains(RecallContentPart.Unavailable))
    }
    @Test fun duplicateSignalsCannotOverrideTheFinalCapacityClippedBody()=runBlocking{
        val m=memory("按实际片段展示，不能多给内容。".repeat(10));val capture=RecallContentCapture(repo,"a")
        val raw=listOf(mapOf<String,Any>("id" to m.id,"source_type" to "companion_memory","content" to repo.describe(m).take(600)))
        val clipped=raw.map{it+("content" to (it.getValue("content") as String).take(20))}
        for(flag in listOf("context_limited","budget_limited")){
            val refs=capture.capture(raw,mapOf("status" to "found","visible_duplicates" to 1,"results" to clipped,flag to true),listOf(origin(m)))
            val shown=parts(refs).filterIsInstance<RecallContentPart.Text>().single().value
            assertTrue((clipped.single().getValue("content") as String).endsWith(shown));assertTrue(shown.length<20)
        }
    }
    @Test fun mappedBackupReferencesReadOnlyTheirMappedRoleHistory()=runBlocking{
        val m=memory();val hit=activity(memoryRefs(m));val clone=m.copy(id="mapped-memory",friendId="b")
        repo.tx{repo.saveMemory(it,clone)}
        val raw=ChatToolJournal.forBackup(Gson().toJson(listOf(hit)),setOf(m.id))
        val mapped=ChatToolJournal.remapBackup(raw,mapOf(m.id to clone.id))
        val restored=Gson().fromJson(mapped,Array<ChatToolActivity>::class.java).toList()
        assertEquals(listOf(RecallContentPart.Text(m.text)),repo.recallContents("b",restored))
        assertEquals(listOf(RecallContentPart.Unavailable),repo.recallContents("a",restored))
    }
    @Test fun aDeduplicatedThenClippedRepeatedFragmentKeepsItsActualOffset()=runBlocking{
        val m=memory("春天春天见");val view=repo.describe(m);val start=MemoryFacets.categoryLabel(m).length+1+2
        val raw=listOf(mapOf<String,Any>("id" to m.id,"source_type" to "companion_memory","content" to view))
        val clipped=listOf(raw.single()+("content" to "春天")+("text_offset" to start))
        val refs=RecallContentCapture(repo,"a").capture(raw,mapOf("status" to "truncated","visible_duplicates" to 1,"context_limited" to true,"results" to clipped),listOf(origin(m)))
        val ref=refs!!.single()!!;assertEquals(2,ref.start);assertEquals(listOf(RecallContentPart.Text("春天")),parts(refs))
        assertEquals(listOf(RecallContentPart.Unavailable),parts(listOf(ref.copy(start=0))))
    }
    @Test fun alreadyInContextMemoryOnlyDisplaysTheActualReturnedSummary()=runBlocking{
        val m=memory("实际摘要之后的文字不能多显示。".repeat(30));val view=repo.describe(m).take(600)
        val raw=listOf(mapOf<String,Any>("id" to m.id,"source_type" to "companion_memory","content" to view))
        val summary=view.substringAfter("：",view).take(120);val short=m.id.take(9)+"…"
        val output=mapOf<String,Any>("status" to "already_in_context","visible_duplicates" to 1,"results" to listOf(mapOf("in_reference" to true,"content" to "$short：$summary（已在上方参考资料中）")))
        val refs=RecallContentCapture(repo,"a").capture(raw,output,listOf(origin(m)))
        assertEquals(listOf(RecallContentPart.Text(summary)),parts(refs))
    }
    @Test fun nineVerifiedHistoryFragmentsSurviveSplitAndBackupBoundaries()=runBlocking{
        val text=(1..9).joinToString(""){"第${it}段落。"};val turn=dialogue(text)
        val rows=(0..8).map{historyRow(turn,text,it*5,5)}
        val refs=RecallContentCapture(repo,"a").capture(rows,mapOf("status" to "truncated","context_limited" to true,"results" to rows),listOf(RecallContentOrigin("message",turn.userMessageId.toString(),1,"full")))
        assertEquals(9,refs?.size);assertEquals(9,parts(refs).filterIsInstance<RecallContentPart.Text>().size)
        val backup=ChatToolJournal.forBackup(Gson().toJson(listOf(activity(refs))),emptySet(),setOf(turn.userMessageId))
        ChatToolJournal.validateBackup(backup,emptySet(),setOf(turn.userMessageId))
    }
    @Test fun completePointersSurviveClippingWhileUnmatchedPointersNeverRecoverRawBodies()=runBlocking{
        val a=memory("第一段摘要。".repeat(50));val b=memory("第二段摘要。".repeat(50))
        val raw=listOf(a,b).map{mapOf<String,Any>("id" to it.id,"source_type" to "companion_memory","content" to repo.describe(it).take(600))}
        val pointers=raw.map{row->val body=row["content"] as String;val id=row["id"] as String;mapOf<String,Any>("in_reference" to true,"content" to (id.take(9)+"…："+body.substringAfter("：",body).take(120)+"（已在上方参考资料中）"))}
        for(second in listOf(pointers[1]+("content" to (pointers[1]["content"] as String).take(20)),pointers[1]+("truncated" to true))){
            val clipped=listOf(pointers[0],second)
            val refs=RecallContentCapture(repo,"a").capture(raw,mapOf("status" to "truncated","context_limited" to true,"visible_duplicates" to 2,"results" to clipped),listOf(origin(a),origin(b)))
            val shown=parts(refs);assertTrue(shown.contains(RecallContentPart.Text(a.text.take(120))));assertTrue(shown.contains(RecallContentPart.Unavailable))
            assertFalse(shown.filterIsInstance<RecallContentPart.Text>().any{it.value.startsWith("第二段")})
        }
    }
}
