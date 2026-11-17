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
        assertTrue(result.serial>first.serial);assertEquals(10L,result.startedAt);assertTrue(result.loading(1000))
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
    @Test fun neutralHoldIs1500ThenRealResultFadesFor350AndRemountDoesNotRestart(){
        val event=ChatCapsuleMotionEvent(1,100)
        assertEquals(1850,event.remaining(100));assertTrue(event.loading(1599));assertEquals(0f,event.progress(1599),0f)
        assertFalse(event.loading(1600));assertEquals(.5f,event.progress(1775),.0001f)
        assertEquals(175,event.remaining(1775));assertEquals(0,event.remaining(1950));assertEquals(1f,event.progress(99999),0f)
        val tracker=ChatCapsuleMotionTracker(emptyMap());val now=frames(row(state="success"))
        tracker.advance(now,100);assertFalse(tracker.advance(now,1949).isEmpty());assertTrue(tracker.advance(now,1950).isEmpty())
    }
    @Test fun mixedMissAndFailureRemainRealFromFirstFrame(){
        val hit=row("hit","success");val failed=row("fail","failed").copy(detail="合成失败")
        val raw=listOf(hit,failed);val copy=raw.toList();val state=chatCapsuleFrames(raw,emptyList())
        val group=recallReceiptGroups(raw).getValue(hit.id)
        assertEquals("想起来了 · 部分失败",group.label());assertTrue(group.hasFailure);assertFalse(group.succeeded)
        assertTrue(state.getValue(toolCapsuleKey(hit)).contains("想起来了"));assertTrue(state.getValue(toolCapsuleKey(failed)).contains("回想出错"));assertEquals(2,state.size);assertEquals(copy,raw)
        assertEquals(RecallDisplayOutcome.NOT_FOUND,failed.copy(detail="").recallOutcome())
    }
    @Test fun everyGenericToolUsesSameArrivalAndStateTransitionPolicy(){
        listOf("companion_record","agent_memory_update","agent_history_read","relationship_recall","companion_control","immediate_contact").forEach{tool->
            val r=row(tool=tool);val tracker=ChatCapsuleMotionTracker(emptyMap())
            listOf("running","staged","success","failed","unknown").forEachIndexed{index,state->
                val actual=r.copy(state=state)
                val event=tracker.advance(frames(actual),index.toLong()).getValue(toolCapsuleKey(actual))
                assertEquals(0L,event.startedAt);assertEquals(index.toLong(),event.resultAt);assertTrue(event.loading(100))
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
    @Test fun previewLatestStateWinsAndEachActualFailedRecordStaysVisible(){
        val r=row(tool="companion_record").copy(kind=ChatToolActivity.WRITE)
        val old=r.copy(id="failed",state="failed")
        val f=chatCapsuleFrames(listOf(old,r,r.copy(state="success")),emptyList())
        assertEquals(2,f.size);assertTrue(f.getValue(toolCapsuleKey(r)).startsWith("result|success:"))
        assertTrue(f.getValue(toolCapsuleKey(old)).startsWith("result|failed:"))
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
        val r=frames(row(state="success"));val tracker=ChatCapsuleMotionTracker(emptyMap())
        val fast=tracker.advance(r,0,.5f).values.single()
        assertEquals(925,fast.remaining(0));assertTrue(fast.loading(749));assertEquals(.4f,fast.progress(820),.0001f)
        assertTrue(tracker.advance(r,925,.5f).isEmpty())
        assertTrue(ChatCapsuleMotionTracker(emptyMap()).advance(r,0,0f).isEmpty())
        assertEquals(1f,ChatCapsuleMotionEvent(1,0,0f).progress(0),0f)
        val live=ChatCapsuleMotionTracker(emptyMap());live.advance(r,0)
        assertTrue(live.advance(r,100,0f).isEmpty())
        val slow=ChatCapsuleMotionTracker(emptyMap()).advance(r,0,2f).values.single()
        assertEquals(3700,slow.remaining(0));assertTrue(slow.loading(2999));assertEquals(.5f,slow.progress(3350),.0001f)
    }
    @Test fun slowActualRequestRemainsLoadingUntilResultThenFadesWithoutAnotherHold(){
        val tracker=ChatCapsuleMotionTracker(emptyMap());val r=row()
        tracker.advance(frames(r),0)
        val waiting=tracker.advance(frames(r),5000).values.single()
        assertFalse(waiting.ready);assertTrue(waiting.loading(5000));assertEquals(0f,waiting.progress(5000),0f)
        val actual=tracker.advance(frames(r.copy(state="failed",detail="合成错误")),5000).values.single()
        assertEquals(0L,actual.startedAt);assertTrue(actual.ready);assertFalse(actual.loading(5000))
        assertEquals(.5f,actual.progress(5175),.0001f);assertEquals(1f,actual.progress(5350),0f)
    }
    @Test fun separateCallsKeepIndependentNeutralTransitionsAndOutcomes(){
        val tracker=ChatCapsuleMotionTracker(emptyMap());val r=row()
        tracker.advance(frames(r),0)
        val success=tracker.advance(frames(r.copy(state="success")),10).values.single()
        assertEquals(0f,success.progress(10),0f)
        val hit=r.copy(state="success");val failure=row("failed","failed").copy(detail="失败")
        val mixed=tracker.advance(frames(hit,failure),20)
        val hitEvent=mixed.getValue(toolCapsuleKey(hit));val failureEvent=mixed.getValue(toolCapsuleKey(failure))
        assertEquals(0L,hitEvent.startedAt);assertEquals(20L,failureEvent.startedAt)
        assertEquals(0f,hitEvent.progress(1499),0f);assertEquals(0f,failureEvent.progress(1519),0f)
        assertEquals(1f,hitEvent.progress(1850),0f);assertEquals(1f,failureEvent.progress(1870),0f)
    }
    @Test fun aNewResultAfterPriorRevealGetsNeutralLoadingRatherThanOldSuccess(){
        val tracker=ChatCapsuleMotionTracker(emptyMap());val r=row(state="success")
        tracker.advance(frames(r),0);tracker.advance(frames(r),2000)
        val changed=tracker.advance(frames(r.copy(state="failed")),2100).values.single()
        assertEquals(2100L,changed.startedAt);assertTrue(changed.loading(3599));assertEquals(0f,changed.progress(3599),0f)
    }
    @Test fun historicalPendingAndStagedLabelsAreNotReplacedByEndlessAnimationPlaceholders(){
        assertEquals(1f,capsuleInitialReveal(null,false,0),0f)
        assertEquals(1f,capsuleInitialReveal(null,true,0),0f)
        assertEquals(0f,capsuleInitialReveal(ChatCapsuleMotionEvent(1,0,ready=false),false,9000),0f)
    }
}
