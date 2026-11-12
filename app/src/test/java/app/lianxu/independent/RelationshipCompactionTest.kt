package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RelationshipCompactionTest {
    private val day=RelationshipProjector.DAY
    private val p=RelationTemplate.STEADY.parameters
    private fun row(id:Long,at:Long)=RelationInput(SourceRef("r",id,1),at,at/day,"关于关系")
    private fun values(c:RelationCursor)=c.state().let{listOf(it.trust,it.closeness,it.tension,it.disappointment,it.happy,it.unhappy,it.hurt,it.low,it.missing)}
    @Test fun compactAndReferenceRemainNumericallyEquivalent(){
        val a=RelationCursor(RelationStart.FRIENDS,p);val b=RelationCursor(RelationStart.FRIENDS,p,compact=false);val random=Random(20261104)
        var error=0.0
        repeat(2500){i->val r=row(i+1L,i*day/30).let{when(random.nextInt(4)){0->it.copy(p=.6);1->it.copy(v=.6);2->it.copy(b=.3);else->it.copy(disappointment=.2)}}
            a.contact(r.at);b.contact(r.at);a.apply(r);b.apply(r);a.compact();b.compact()
            values(a).zip(values(b)).forEach{(x,y)->error=maxOf(error,abs(x-y))}
        }
        println("compact_reference_max_error=$error");assertTrue(error<1e-9)
    }
    @Test fun oldBigDebtRefundSurvivesMergedImpact(){
        val c=RelationCursor(RelationStart.FRIENDS,p);val event=row(1,0).copy(v=.9)
        c.apply(event);c.advance(1000*day);c.compact()
        // 新做法：心结不淡也不并，旧事还能按笔解释；信任损失早已淡掉，只退回剩下的那点。
        assertTrue(event.source in c.pending);assertTrue(event.source in c.impacts)
        val old=c.impacts.getValue(event.source)
        val before=c.trust;c.apply(row(2,1000*day).copy(explains=event.source,explainedAt=0,excuse="工作忙"))
        assertEquals(before-old.trust*.3,c.trust,1e-9);assertEquals(old.tension*.7,c.impacts.getValue(event.source).tension,1e-12);assertEquals(1,c.excuses["工作忙"])
        val after=c.trust;c.apply(row(3,1000*day).copy(explains=event.source,explainedAt=0));assertEquals(after,c.trust,0.0)
    }
    @Test fun storageExceptionsFallBackAndCancellationPropagates(){
        val rows=listOf(row(1,0).copy(v=.6));val expected=RelationProjectionCache().project(p,rows,day,RelationStart.FRIENDS,emptyList())
        for(stage in listOf("headers","payload","append","complete","truncate")){
            val memory=MemoryRelationSnapshots();RelationProjectionCache(memory).project(p,rows,0,RelationStart.FRIENDS,emptyList())
            val store=object:RelationSnapshotStore by memory {
                override fun headers()=if(stage=="headers")error("test")else memory.headers()
                override fun complete(headers:List<RelationSnapshotHeader>)=if(stage=="complete")error("test")else if(stage in listOf("append","truncate"))false else memory.complete(headers)
                override fun payload(sequence:Long)=if(stage=="payload")error("test")else memory.payload(sequence)
                override fun append(header:RelationSnapshotHeader,payload:String){if(stage=="append")error("test");memory.append(header,payload)}
                override fun truncate(position:Int){if(stage=="truncate")error("test");memory.truncate(position)}
            }
            assertEquals(expected,RelationProjectionCache(store).project(p,rows,day,RelationStart.FRIENDS,emptyList()))
        }
        val cancellation=object:RelationSnapshotStore by MemoryRelationSnapshots(){override fun headers():List<RelationSnapshotHeader> = throw java.util.concurrent.CancellationException()}
        try{RelationProjectionCache(cancellation).project(p,rows,day,RelationStart.FRIENDS,emptyList());fail()}catch(_:java.util.concurrent.CancellationException){}
    }
    @Test fun tenThousandInputSimulation(){
        val fulfillmentQueue=java.util.ArrayDeque<SourceRef>();var promises=0
        val rows=(1L..10020).map{id->row(id,id*day/30).let{r->when((id%200).toInt()){
            in 0..118->r.copy(category="普通信息");in 119..168->if(fulfillmentQueue.isEmpty())r.copy(p=.6)else r.copy(c=.6,fulfills=fulfillmentQueue.removeFirst())
            in 169..184->if(id%2==0L)r.copy(v=.3)else r.copy(b=.6)
            in 185..190->r.copy(disappointment=.2);in 191..194->{if(promises++%10<7)fulfillmentQueue.add(r.source);r.copy(promise=true)}
            195,196->r.copy(explains=SourceRef("r",id-17,1),excuse="工作忙")
            197,198->r.copy(previous=SourceRef("r",id-18,1),correction="道歉和好")
            else->r.copy(previous=SourceRef("r",id-19,1),correction="误会")
        }}}+(1L..10).map{row(10020+it,it*30*day).copy(ignored=.1)}
        val changes=listOf(RelationChange(100*day,p,RelationTemplate.DIRECT.parameters,"DIRECT"),RelationChange(200*day,RelationTemplate.DIRECT.parameters,p,"STEADY"),RelationChange(300*day,p,RelationTemplate.SUNNY.parameters,"SUNNY"))
        val store=MemoryRelationSnapshots();var peakCount=0;var peakBytes=0L
        val observed=object:RelationSnapshotStore by store {
            override fun append(header:RelationSnapshotHeader,payload:String){store.append(header,payload);peakCount=maxOf(peakCount,store.rows.size);peakBytes=maxOf(peakBytes,store.rows.sumOf{it.second.toByteArray().size.toLong()})}
        }
        val cache=RelationProjectionCache(observed);val now=rows.maxOf{it.at}
        val start=System.nanoTime();val result=cache.project(p,rows,now,RelationStart.FRIENDS,emptyList(),changes);val cold=(System.nanoTime()-start)/1e6
        val latest=store.rows.last().second;val max=store.rows.maxOf{it.second.toByteArray().size};val total=store.rows.sumOf{it.second.toByteArray().size.toLong()}
        val open=System.nanoTime();assertEquals(result,RelationProjectionCache(store).project(p,rows,now,RelationStart.FRIENDS,emptyList(),changes));val millis=(System.nanoTime()-open)/1e6
        val pending=com.google.gson.JsonParser.parseString(latest).asJsonObject["pending"].toString().toByteArray().size
        var denseCount=0;var denseBytes=0L
        val counter=object:RelationSnapshotStore {
            override fun headers()=emptyList<RelationSnapshotHeader>()
            override fun complete(headers:List<RelationSnapshotHeader>)=true
            override fun payload(sequence:Long):String?=null
            override fun truncate(position:Int){}
            override fun append(header:RelationSnapshotHeader,payload:String){denseCount++;denseBytes+=payload.toByteArray().size}
        }
        assertEquals(result,RelationProjectionCache(counter,sparse=false).project(p,rows,now,RelationStart.FRIENDS,emptyList(),changes))
        println("before_sparsity snapshots=$denseCount total_bytes=$denseBytes after_sparsity snapshots=${store.rows.size} total_bytes=$total")
        println("simulation inputs=${rows.size} snapshot_max_bytes=$max snapshot_last_bytes=${latest.toByteArray().size} snapshots=${store.rows.size} total_bytes=$total cold_ms=$cold open_ms=$millis pending_count=${result.ledger.size} pending_bytes=$pending peak_count=$peakCount peak_bytes=$peakBytes")
        val recent=RelationshipRecall.recent(rows,result,now)
        val relationBytes=(com.google.gson.Gson().toJson(recent)+com.google.gson.Gson().toJson(RelationshipRecall.definition)+RelationshipSummary.render(result,now){"合成原因"}).toByteArray().size
        val oldBytes=com.google.gson.Gson().toJson(rows.map{mapOf("消息" to it.source.turnId,"可澄清" to ((it.v?:0.0)>0||(it.b?:0.0)>0||(it.disappointment?:0.0)>0))}).toByteArray().size
        println("relation_prompt_bytes=$relationBytes previous_relationship_sources_bytes=$oldBytes")
        assertTrue(store.rows.size<=141);assertTrue(peakCount<=142)
        for(days in listOf(30,40,60,100)){
            val target=rows.last{it.at<=now-days*day&&(it.v?:0.0)>0}
            val changed=rows.map{if(it.source==target.source)it.copy(status="disputed")else it}
            val replay=RelationProjectionCache(store);val started=System.nanoTime()
            val corrected=replay.project(p,changed,now,RelationStart.FRIENDS,emptyList(),changes)
            val elapsed=(System.nanoTime()-started)/1e6
            val full=RelationProjectionCache().project(p,changed,now,RelationStart.FRIENDS,emptyList(),changes,readOnly=true)
            fun nums(s:RelationState)=listOf(s.trust,s.closeness,s.tension,s.disappointment,s.happy,s.unhappy,s.hurt,s.low,s.missing)
            val error=nums(corrected).zip(nums(full)).maxOf{(a,b)->abs(a-b)}
            println("correction_days=$days max_error=$error restored_position=${replay.restoredPosition} elapsed_ms=$elapsed")
            assertEquals(full,corrected);if(days==100)assertEquals(0,replay.restoredPosition)else assertTrue(replay.restoredPosition>0)
            RelationProjectionCache(store).project(p,rows,now,RelationStart.FRIENDS,emptyList(),changes)
        }
        val cursor=cache.forecast()!!
        println("pending_big=${cursor.pending.values.count{it.big}} pending_zero=${cursor.pending.values.count{it.loss==0.0}} pending_small=${cursor.pending.values.count{!it.big}}")
        assertTrue(cursor.pending.values.all{it.loss>0})
        val voids=rows.filter{it.correction=="误会"}.mapNotNull{it.previous}.toSet()
        val audit=RelationCursor(RelationStart.FRIENDS,p);var bigAdded=0;var smallAdded=0;var refunded=0
        rows.filter{it.source !in voids}.sortedWith(compareBy<RelationInput>{it.at}.thenBy{it.source.turnId}).forEach{r->
            audit.parameters=changes.lastOrNull{it.at<=r.at}?.new?:p
            audit.apply(r);audit.compact()
            audit.pending[r.source]?.let{if(it.big)bigAdded++ else smallAdded++}
            if(audit.explanations[r.explains]?.source==r.source)refunded++
        }
        println("ledger_audit vb_inputs=${rows.count{(it.v?:0.0)>0||(it.b?:0.0)>0}} legacy_voids=${voids.size} big_added=$bigAdded small_added=$smallAdded refunded=$refunded remaining=${audit.pending.size}")
        assertEquals(result.ledger,audit.pending)
        assertTrue(relationBytes<=2048)
        // Unrelated fulfillments retain event-specific grudges; bound their larger snapshots without changing runtime limits.
        assertTrue("snapshot bytes $max",max<=128*1024);assertTrue(total<=32L*1024*1024);assertTrue("open ms $millis",millis<=200)
    }
}
