package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** The pill's text and its place in the timeline; no database or Android here. */
class ChatMemoryPillTest {
    private fun message(id:Long,mine:Boolean)=Message(id,"a",if(mine)"用户 $id" else "她 $id",mine,60_000L+id)
    private fun pill(batch:String,after:Long,count:Int)=MemoryPill(batch,"a",after,count,0L)

    @Test fun zeroSavedMemoriesSayOnlyThatTheBatchFinished(){
        assertEquals("记忆已整理",pill("b0",1,0).text)
    }

    @Test fun savedCountsAreNamedAsCounts(){
        assertEquals("已整理 1 条记忆",pill("b1",1,1).text)
        assertEquals("已整理 5 条记忆",pill("b5",1,5).text)
    }

    @Test fun blockingSummaryPillSitsBetweenTheUserMessageAndTheReply(){
        val rows=chatTimeline(listOf(message(1,true),message(2,false)),listOf(pill("b1",1,2)))
        val kinds=rows.map{when(it){is ChatRow.Date->"date";is ChatRow.Group->"group-${it.value.id}";is ChatRow.Pill->"pill-${it.value.batchId}"}}
        assertEquals(listOf("date","group-1","pill-b1","group-2"),kinds)
    }

    @Test fun backgroundSummaryPillSitsAfterTheLastReply(){
        val rows=chatTimeline(listOf(message(1,true),message(2,false)),listOf(pill("b1",2,1)))
        val kinds=rows.map{when(it){is ChatRow.Date->"date";is ChatRow.Group->"group-${it.value.id}";is ChatRow.Pill->"pill-${it.value.batchId}"}}
        assertEquals(listOf("date","group-1","group-2","pill-b1"),kinds)
    }

    @Test fun aPillWhoseAnchorIsGoneIsKeptAtTheEndInsteadOfHidden(){
        val rows=chatTimeline(listOf(message(1,true)),listOf(pill("b1",99,3)))
        assertEquals("b1",(rows.last() as ChatRow.Pill).value.batchId)
    }

    @Test fun twoBatchesOnTheSameMessageKeepTheirOwnOrder(){
        val rows=chatTimeline(listOf(message(1,true),message(2,false)),listOf(pill("b1",1,1),pill("b2",1,2)))
        assertEquals(listOf("b1","b2"),rows.filterIsInstance<ChatRow.Pill>().map{it.value.batchId})
    }

    @Test fun aPillSplitsAMergedGroupAtItsOwnBoundary(){
        // Same side, same minute: these merge into one cluster without the pill.
        val rows=chatTimeline(listOf(message(1,true),message(2,true)),listOf(pill("b1",1,1)))
        val shape=rows.map{when(it){is ChatRow.Date->"date";is ChatRow.Group->"group-${it.value.id}";is ChatRow.Pill->"pill-${it.value.batchId}"}}
        assertEquals(listOf("date","group-1","pill-b1","group-2"),shape)
        assertEquals(listOf(1L),rows.first{it is ChatRow.Group&&it.value.id==1L}.let{(it as ChatRow.Group).value.messages.map{m->m.id}})
        assertEquals(listOf(2L),rows.last{it is ChatRow.Group}.let{(it as ChatRow.Group).value.messages.map{m->m.id}})
    }

    @Test fun oneBatchNeverProducesTwoRows(){
        val rows=chatTimeline(listOf(message(1,true)),listOf(pill("b1",1,1),pill("b1",1,1)))
        assertEquals(1,rows.filterIsInstance<ChatRow.Pill>().size)
        assertEquals(rows.map{it.key}.distinct().size,rows.size)
    }
}
