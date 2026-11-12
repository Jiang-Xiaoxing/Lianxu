package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class RelationshipSnapshotTest {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.DIRECT.parameters
    private fun row(id:Long,at:Long=id*day)=RelationInput(SourceRef("r",id,1),at,at/day,"关于关系")
    private fun project(cache:RelationProjectionCache,rows:List<RelationInput>,now:Long=rows.maxOfOrNull{it.at}?:0,
        changes:List<RelationChange> = emptyList(),corrections:List<RelationCorrection> = emptyList())=
        cache.project(p,rows,now,RelationStart.FRIENDS,rows.map{it.at},changes,emptyList(),corrections,"DIRECT")
    private fun replay(rows:List<RelationInput>,now:Long=rows.maxOfOrNull{it.at}?:0,changes:List<RelationChange> = emptyList())=
        RelationshipProjector.project(p,rows,now,RelationStart.FRIENDS,contactTimes=rows.map{it.at},changes=changes)

    @Test fun coldStartRestoresAllStateAndDoesNoReplayOrQueryWrites(){
        val store=MemoryRelationSnapshots();val rows=listOf(row(1).copy(p=.6,promise=true),row(2).copy(v=.9),
            row(3).copy(v=.9),row(4).copy(explains=row(2).source,excuse="工作忙"),row(5).copy(c=.6,fulfills=row(1).source),
            row(6).copy(previous=row(3).source,correction="道歉和好"),row(7).copy(p=.9,b=.9))
        val changes=listOf(RelationChange(4*day,p,RelationTemplate.RESERVED.parameters,"嘴硬心软",4))
        val expected=project(RelationProjectionCache(store),rows,7*day,changes)
        assertEquals(replay(rows,7*day,changes),expected)
        val before=store.rows.toList();val cold=RelationProjectionCache(store)
        assertEquals(expected,project(cold,rows,7*day,changes));assertEquals(0,cold.processed);assertEquals(0,cold.rebuilds)
        assertEquals(replay(rows,400*day,changes),project(cold,rows,400*day,changes))
        assertEquals(before,store.rows);assertEquals(0,cold.processed)
        val next=rows+row(8,401*day).copy(v=.9)
        assertEquals(replay(next,401*day,changes),project(cold,next,401*day,changes));assertEquals(2,cold.processed)
    }
    @Test fun latestWithdrawalRestoresPreviousSnapshotWithoutApplyingAnyStep(){
        val store=MemoryRelationSnapshots();val rows=(1L..4).map{row(it).copy(v=.6)}
        project(RelationProjectionCache(store),rows)
        val previous=store.rows[store.rows.size-3];val cold=RelationProjectionCache(store)
        assertEquals(replay(rows.dropLast(1),4*day),project(cold,rows.dropLast(1),4*day))
        assertEquals(0,cold.processed);assertEquals(0,cold.applied);assertEquals(0,cold.rebuilds)
        assertEquals(previous,store.rows.last())
        val max=store.rows.last().first.sequence
        project(cold,rows);assertTrue(store.rows.last().first.sequence>max+2)
    }
    @Test fun earlierWithdrawalReplaysOnlyTheSuffix(){
        val store=MemoryRelationSnapshots();val rows=(1L..8).map{row(it).copy(v=.9,p=.3)}
        project(RelationProjectionCache(store),rows);val prefix=store.rows.take(6)
        val cache=RelationProjectionCache(store);val remaining=rows.filter{it.source.turnId!=4L}
        assertEquals(replay(remaining),project(cache,remaining));assertEquals(6,cache.restoredPosition)
        assertEquals(4,cache.applied);assertEquals(8,cache.processed);assertEquals(0,cache.rebuilds)
        assertEquals(prefix,store.rows.take(6))
    }
    @Test fun disputeUndoRestoreAndMisunderstandingDoNotLoseRepeatOrCareState(){
        val rows=(1L..7).map{row(it).copy(v=.9,p=.6)};val store=MemoryRelationSnapshots()
        project(RelationProjectionCache(store),rows)
        val corrections=mutableListOf<RelationCorrection>()
        for(voided in listOf(true,false,true,false)){
            corrections.add(RelationCorrection(rows[3].source,8*day,voided))
            val changed=rows.map{if(it==rows[3]&&voided)it.copy(status="disputed")else it}
            val cache=RelationProjectionCache(store)
            assertEquals(replay(changed,8*day),project(cache,changed,8*day,corrections=corrections))
            assertEquals(0,cache.rebuilds);assertTrue(cache.restoredPosition>=7);assertTrue(cache.applied<=4)
            assertTrue(store.rows.last().first.event.startsWith("correction:"))
        }
        val misunderstood=rows+row(8).copy(previous=rows[3].source,correction="误会")
        val cache=RelationProjectionCache(store)
        assertEquals(replay(misunderstood),project(cache,misunderstood));assertEquals(0,cache.rebuilds)
    }
    @Test fun corruptionMissingPositionVersionAndInitialFingerprintRebuildAll(){
        val rows=(1L..5).map{row(it).copy(v=.6)}
        for(mode in 0..8){
            val store=MemoryRelationSnapshots();project(RelationProjectionCache(store),rows)
            val last=store.rows.last()
            when(mode){
                0->store.rows[store.rows.lastIndex]=last.first to "broken"
                1->store.rows.removeAt(3)
                2->store.rows[0]=store.rows[0].let{it.first.copy(engine=999) to it.second}
                3->store.rows[0]=store.rows[0].let{it.first.copy(base="wrong") to it.second}
                4->store.rows.clear()
                5->{val raw=last.second.replace("\"values\":", "\"unknown\":");store.rows[store.rows.lastIndex]=last.first.copy(checksum=RelationSnapshotCodec.checksum(last.first,raw)) to raw}
                6->store.rows[store.rows.lastIndex]=last.first.copy(checksum="wrong") to last.second
                7->store.rows.removeAt(store.rows.lastIndex)
                8->store.rows[3]=store.rows[3].let{it.first.copy(prefix="wrong") to it.second}
            }
            val cache=RelationProjectionCache(store);assertEquals(replay(rows),project(cache,rows))
            // Only the selected payload is validated; unrelated old payloads are checked if restored.
            assertEquals(if(mode==8)0 else 1,cache.rebuilds);assertEquals(if(mode==8)0 else 5,cache.applied);assertEquals(10,store.rows.size)
        }
    }
    @Test fun codecRetainsHiddenFieldsAndDoesNotStoreFreeText(){
        val c=RelationCursor(RelationStart.FRIENDS,p.copy(tone="不应进入快照的自由文本"));c.contact(0)
        c.apply(row(1,0).copy(p=.6,promise=true));c.apply(row(2,day).copy(v=.9));c.apply(row(3,2*day).copy(v=.9))
        c.apply(row(4,3*day).copy(explains=row(2).source,excuse="工作忙"))
        c.apply(row(5,4*day).copy(previous=row(3).source,correction="道歉和好"))
        val encoded=RelationSnapshotCodec.encode(c);assertFalse(encoded.contains("自由文本"));assertFalse(encoded.contains("parameters"))
        val restored=RelationSnapshotCodec.decode(encoded,c.start,c.parameters)!!
        assertEquals(encoded,RelationSnapshotCodec.encode(restored))
        val fulfill=row(6,40*day).copy(c=.9,fulfills=row(1).source)
        c.apply(fulfill);restored.apply(fulfill);assertEquals(RelationSnapshotCodec.encode(c),RelationSnapshotCodec.encode(restored))
    }
    @Test fun randomAppendColdRestartAndEarlierEditEqualReplayExactly(){
        val random=Random(413);val rows=mutableListOf<RelationInput>();val store=MemoryRelationSnapshots()
        repeat(40){i->
            rows.add(row(i+1L,(i/2)*day).copy(p=listOf(0.0,.3,.6,.9).random(random),v=listOf(0.0,.6,.9).random(random),b=if(i%7==0).9 else null))
            val cache=RelationProjectionCache(store)
            assertEquals(replay(rows),project(cache,rows))
            if(i>5&&i%5==0){val changed=rows.toMutableList();val index=random.nextInt(1,i);changed[index]=changed[index].copy(status="disputed")
                assertEquals(replay(changed),project(cache,changed));assertEquals(replay(rows),project(cache,rows))}
        }
    }
}
