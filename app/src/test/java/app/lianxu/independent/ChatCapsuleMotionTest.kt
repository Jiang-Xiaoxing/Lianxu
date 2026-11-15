package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ChatCapsuleMotionTest {
    private fun ChatCapsuleMotionTracker.advance(frames:Map<String,String>,now:Long,scale:Float=1f)=prepare(frames,now,scale).also{commit(it)}.events
    private fun row(id:String="r",state:String="running",tool:String="agent_memory_read",attempt:Int=1)=
        ChatToolActivity(id,attempt,"让我想一想",state,kind=ChatToolActivity.READ,tool=tool,recallGroup="response")
    private fun frames(vararg rows:ChatToolActivity)=chatCapsuleFrames(rows.toList(),emptyList())
    @Test fun loadedHistoryIsBaselineIncludingRunningAndFinalResults(){
        val old=frames(row(),row("done","success"),row("miss","failed"))
        assertTrue(ChatCapsuleMotionTracker(old).advance(old,0).isEmpty())
    }
    @Test fun newRunningAndFastAlreadyFinishedCapsulesBothAppearSoftly(){
        val tracker=ChatCapsuleMotionTracker(emptyMap())
        val running=row();val runningKey=toolCapsuleKey(running)
        val first=tracker.advance(frames(running),10).getValue(runningKey)
        val result=tracker.advance(frames(running.copy(state="success")),20).getValue(runningKey)
        assertTrue(result.serial>first.serial);assertEquals(20L,result.startedAt)
        val fast=row("fast","failed",tool="other_read")
        assertEquals(30L,tracker.advance(frames(running.copy(state="success"),fast),30).getValue(toolCapsuleKey(fast)).startedAt)
    }
    @Test fun identicalRecompositionAndCommittedMemoryLinkDoNotRestart(){
        val tracker=ChatCapsuleMotionTracker(emptyMap());val saved=row(state="success",tool="companion_record")
        val event=tracker.advance(frames(saved),0)
        assertEquals(event,tracker.advance(frames(saved.copy(memoryId="committed-row",detail="")),500))
        assertEquals(event,tracker.advance(frames(saved),1000))
    }
    @Test fun leavingLazyRowsOrLiveToSavedGapDoesNotReplay(){
        val tracker=ChatCapsuleMotionTracker(emptyMap());val saved=row(state="success")
        tracker.advance(frames(saved),0)
        assertTrue(tracker.advance(emptyMap(),100).isEmpty())
        assertTrue(tracker.advance(frames(saved),200).isEmpty())
    }
    @Test fun retryAttemptAndDifferentRoleHaveIndependentBaselines(){
        val old=row();val tracker=ChatCapsuleMotionTracker(frames(old))
        val retried=old.copy(attempt=2)
        val events=tracker.advance(frames(retried),10)
        assertFalse(events.containsKey(toolCapsuleKey(old)));assertTrue(events.containsKey(toolCapsuleKey(retried)))
        assertTrue(ChatCapsuleMotionTracker(frames(retried)).advance(frames(retried),20).isEmpty())
    }
    @Test fun durationIs1500AndLateMountUsesOnlyTimeRemaining(){
        val event=ChatCapsuleMotionEvent(1,100)
        assertEquals(1500,event.remaining(100));assertEquals(.5f,event.progress(850),.0001f)
        assertEquals(250,event.remaining(1350));assertEquals(0,event.remaining(1600));assertEquals(1f,event.progress(99999),0f)
        assertEquals(1500,event.remaining(0))
        val tracker=ChatCapsuleMotionTracker(emptyMap());val now=frames(row())
        tracker.advance(now,100);assertFalse(tracker.advance(now,1599).isEmpty());assertTrue(tracker.advance(now,1600).isEmpty())
    }
    @Test fun mixedMissAndFailureRemainRealFromFirstFrame(){
        val hit=row("hit","success");val failed=row("fail","failed").copy(detail="合成失败")
        val raw=listOf(hit,failed);val copy=raw.toList();val state=chatCapsuleFrames(raw,emptyList())
        val group=recallReceiptGroups(raw).getValue(hit.id)
        assertEquals("想起来了 · 部分失败",group.label());assertTrue(group.hasFailure);assertFalse(group.succeeded)
        assertTrue(state.getValue(toolCapsuleKey(hit)).contains("ERROR"));assertEquals(copy,raw)
        assertEquals(RecallDisplayOutcome.NOT_FOUND,failed.copy(detail="").recallOutcome())
    }
    @Test fun everyGenericToolUsesSameArrivalAndStateTransitionPolicy(){
        listOf("companion_record","agent_memory_update","agent_history_read","relationship_recall","companion_control","immediate_contact").forEach{tool->
            val r=row(tool=tool);val tracker=ChatCapsuleMotionTracker(emptyMap())
            listOf("running","staged","success","failed","unknown").forEachIndexed{index,state->
                val actual=r.copy(state=state)
                assertEquals(index.toLong(),tracker.advance(frames(actual),index.toLong()).getValue(toolCapsuleKey(actual)).startedAt)
            }
        }
    }
    @Test fun committedBatchAppearsOnceIncludingZeroSavedCount(){
        val batch=MemoryPill("batch","role",1,0,0);val f=chatCapsuleFrames(emptyList(),listOf(batch))
        assertEquals("记忆已整理",f.getValue(memoryCapsuleKey(batch)))
        val tracker=ChatCapsuleMotionTracker(emptyMap());assertEquals(1,tracker.advance(f,0).size)
        assertEquals(tracker.advance(f,0),tracker.advance(f,100))
        assertTrue(ChatCapsuleMotionTracker(f).advance(f,0).isEmpty())
    }
    @Test fun previewLatestStateWinsAndSupersededRecordsStayHidden(){
        val r=row(tool="companion_record").copy(kind=ChatToolActivity.WRITE)
        val old=r.copy(id="failed",state="failed")
        val f=chatCapsuleFrames(listOf(old,r,r.copy(state="success")),emptyList())
        assertEquals(1,f.size);assertTrue(f.getValue(toolCapsuleKey(r)).startsWith("success:"))
        assertFalse(f.containsKey(toolCapsuleKey(old)))
    }
    @Test fun sameToolIdInDifferentTurnsNeverSharesMotion(){
        val r=row();val first=chatCapsuleFrames(listOf(r),emptyList(),"first-turn")
        val tracker=ChatCapsuleMotionTracker(first)
        val second=chatCapsuleFrames(listOf(r),emptyList(),"second-turn")
        val events=tracker.advance(first+second,10)
        assertEquals(setOf(toolCapsuleKey(r,"second-turn")),events.keys)
    }
    @Test fun cancelledCompositionDoesNotConsumeOrInventAnArrival(){
        val r=row();val tracker=ChatCapsuleMotionTracker(frames(r))
        val abandoned=tracker.prepare(frames(r.copy(state="success")),10)
        assertEquals(1,abandoned.events.size)
        assertTrue(tracker.prepare(frames(r),20).events.isEmpty())
        val actual=tracker.prepare(frames(r.copy(state="failed")),30)
        assertEquals(30L,actual.events.values.single().startedAt)
        tracker.commit(actual)
        assertEquals(actual.events,tracker.prepare(frames(r.copy(state="failed")),100).events)
        val playing=tracker.prepare(frames(r.copy(state="failed")),110)
        tracker.commit(playing)
        tracker.prepare(frames(r.copy(state="success")),120,0f) // Abandoned, including its scale and sequence changes.
        assertEquals(playing,tracker.prepare(frames(r.copy(state="failed")),130))
    }
    @Test fun scaledLifetimeMatchesRemountAndDisabledMotionNeverStarts(){
        val r=frames(row());val tracker=ChatCapsuleMotionTracker(emptyMap())
        val fast=tracker.advance(r,0,.5f).values.single()
        assertEquals(750,fast.remaining(0));assertEquals(.5f,fast.progress(375),.0001f)
        assertTrue(tracker.advance(r,900,.5f).isEmpty())
        assertTrue(ChatCapsuleMotionTracker(emptyMap()).advance(r,0,0f).isEmpty())
        assertEquals(1f,ChatCapsuleMotionEvent(1,0,0f).progress(0),0f)
        val live=ChatCapsuleMotionTracker(emptyMap());live.advance(r,0)
        assertTrue(live.advance(r,100,0f).isEmpty())
        val slow=ChatCapsuleMotionTracker(emptyMap()).advance(r,0,2f).values.single()
        assertEquals(3000,slow.remaining(0));assertEquals(.5f,slow.progress(1500),.0001f)
    }
}

