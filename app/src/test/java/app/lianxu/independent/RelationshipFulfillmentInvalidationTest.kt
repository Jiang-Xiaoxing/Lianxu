package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RelationshipFulfillmentInvalidationTest {
    private val parameters=RelationTemplate.DIRECT.parameters
    private fun row(id:Long)=RelationInput(SourceRef("r",id,1),id*1000,id/100,"关于关系")
    private fun feel(saved:RelationFeelingSaved?,rows:List<RelationInput>,now:Long=5000)=
        RelationFeeling.update("r",saved,parameters,parameters,RelationStart.FRIENDS,rows,emptyList(),emptyList(),emptyList(),now)

    @Test fun invalidPromiseRetractsItsFulfillmentWithoutReplayingOtherEntries(){
        val promise=row(1).copy(promise=true)
        val fulfilled=row(2).copy(c=.6,fulfills=promise.source)
        val other=row(3).copy(p=.3)
        val before=feel(null,listOf(promise,fulfilled,other))
        assertTrue(before.cursor.trust>.5)
        for(replacement in listOf(emptyList(),listOf(promise.copy(status="disputed")),listOf(promise.copy(source=promise.source.copy(version=2))))){
            val updated=feel(before.saved,replacement+fulfilled+other)
            assertEquals(before.cursor.impacts.getValue(other.source),updated.cursor.impacts.getValue(other.source))
            assertEquals(.5+before.cursor.impacts.getValue(other.source).trust,updated.cursor.trust,1e-12)
            assertFalse(updated.rebuilt)
            assertFalse(feel(updated.saved,replacement+fulfilled+other).changed)
            assertEquals(.6,fulfilled.c!!,0.0) // Input history was not rewritten.
        }
    }
    @Test fun warmAndColdBothStopCountingAWithdrawnPromise(){
        val promise=row(1).copy(promise=true)
        val fulfilled=row(2).copy(c=.6,fulfills=promise.source)
        val before=feel(null,listOf(promise,fulfilled))
        val warm=feel(before.saved,listOf(fulfilled));val cold=feel(null,listOf(fulfilled))
        assertEquals(.5,warm.cursor.trust,0.0);assertEquals(cold.cursor.trust,warm.cursor.trust,0.0)
        assertTrue(warm.cursor.state().promises.isEmpty())
    }
    @Test fun invalidFulfillmentNeverBecomesAnUnpromisedRepair(){
        val hurt=row(1).copy(v=.9)
        val completed=row(3).copy(c=.6,fulfills=row(2).source,repairs=hurt.source,repairCompleted=true)
        val normalized=relationEffectiveInputs(listOf(hurt,completed),5000).last()
        assertNull(normalized.c);assertNull(normalized.fulfills);assertNull(normalized.repairs);assertFalse(normalized.repairCompleted)
        val cursor=RelationCursor(RelationStart.FRIENDS,parameters)
        cursor.apply(hurt);val tension=cursor.state().tension
        cursor.apply(normalized);assertEquals(tension,cursor.state().tension,0.0)
    }
    @Test fun withdrawingPromiseAfterCompactionStillRemovesOnlyItsDependentContribution(){
        val promise=row(1).copy(promise=true);val fulfilled=row(2).copy(c=.6,fulfills=promise.source)
        val initial=feel(null,listOf(promise,fulfilled))
        val at=40*RelationshipProjector.DAY
        val other=row(3).copy(at=at,day=40,p=.3)
        val before=feel(initial.saved,listOf(promise,fulfilled,other),at)
        val otherImpact=before.cursor.impacts.getValue(other.source)
        val after=feel(before.saved,listOf(fulfilled,other),at)
        assertEquals(otherImpact,after.cursor.impacts.getValue(other.source))
        assertEquals(.5+otherImpact.trust,after.cursor.trust,1e-12)
    }
    @Test fun withdrawingOldFulfillmentAlsoRemovesItsCount(){
        val promise=row(1).copy(promise=true);val fulfilled=row(2).copy(c=.6,fulfills=promise.source)
        val cursor=feel(null,listOf(promise,fulfilled)).cursor
        cursor.advance(100*RelationshipProjector.DAY);cursor.compact()
        cursor.retract(fulfilled.source)
        assertEquals(0,cursor.fulfillments)
        assertFalse(promise.source in cursor.fulfilledAt)
    }
    @Test fun attributionSurvivesSerializationAndDoesNotChangeExistingAnonymousScores(){
        val promise=row(1).copy(promise=true);val fulfilled=row(2).copy(c=.6,fulfills=promise.source)
        val at=40*RelationshipProjector.DAY;val other=row(3).copy(at=at,day=40,p=.3)
        val original=feel(null,listOf(promise,fulfilled,other),at)
        assertTrue(fulfilled.source in original.cursor.mergedImpacts)
        val gson=com.google.gson.Gson()
        val restored=gson.fromJson(gson.toJson(original.saved),RelationFeelingSaved::class.java)
        val withdrawn=feel(restored,listOf(fulfilled,other),at)
        assertFalse(withdrawn.saved.attributionIncomplete)
        assertEquals(.5+original.cursor.impacts.getValue(other.source).trust,withdrawn.cursor.trust,1e-12)
        val legacy=restored.copy(merged=null,fulfillmentSources=null,unattributedMerged=null,archivedUndo=null,unverifiableSources=null)
        val blocked=feel(legacy,listOf(fulfilled,other),at)
        assertTrue(blocked.saved.attributionIncomplete)
        assertEquals(original.cursor.state(),blocked.cursor.state())
        assertEquals(legacy.ledger,blocked.saved.ledger)
        assertEquals(legacy.cursor,blocked.saved.cursor)
        assertTrue(feel(blocked.saved,listOf(fulfilled,other),at).saved.attributionIncomplete)
    }
    @Test fun overflowAndOpposingMoodPreserveAttributedShares(){
        val rows=(1L..900).map{id->row(id).copy(p=.3)}
        val initial=feel(null,rows,900000)
        val later=row(901).copy(at=3*RelationshipProjector.HOUR,day=0,disappointment=.001)
        val before=feel(initial.saved,rows+later,later.at)
        assertTrue(before.cursor.mergedImpacts.isNotEmpty())
        val merged=before.cursor.mergedImpacts.keys.first()
        val remaining=before.cursor.mergedImpacts.getValue(merged)
        assertTrue(remaining.happy>0)
        val after=feel(before.saved,rows.filter{it.source!=merged}+later,later.at)
        assertEquals(before.cursor.trust-remaining.trust,after.cursor.trust,1e-12)
        assertEquals(before.cursor.state().happy-remaining.happy,after.cursor.state().happy,1e-12)
        assertEquals(before.cursor.impacts.getValue(later.source),after.cursor.impacts.getValue(later.source))
        assertFalse(feel(after.saved,rows.filter{it.source!=merged}+later,later.at).changed)
        val bytes=com.google.gson.Gson().toJson(after.saved.merged).toByteArray().size
        assertTrue(bytes<=1024*after.saved.ledger.size)
    }
    @Test fun tenThousandInputsKeepTheProjectionBoundedAndAttributionLinear(){
        val tracked=RelationCursor(RelationStart.FRIENDS,parameters).apply{trackMerged=true}
        val reference=RelationCursor(RelationStart.FRIENDS,parameters)
        for(id in 1L..10000){
            val event=row(id).copy(at=id*RelationshipProjector.DAY/30).let{r->when(id%10){
                0L->r.copy(promise=true)
                1L->if(id>1)r.copy(c=.6,fulfills=SourceRef("r",id-1,1))else r.copy(category="普通信息")
                else->r.copy(category="普通信息")}}
            tracked.apply(event);reference.apply(event)
        }
        assertEquals(reference.state(),tracked.state())
        assertTrue(RelationSnapshotCodec.encode(tracked).toByteArray().size<=RelationSnapshotCodec.MAX_BYTES)
        val bytes=com.google.gson.Gson().toJson(tracked.mergedImpacts.mapKeys{"${it.key.turnId}:${it.key.version}"}).toByteArray().size
        println("attribution_entries=${tracked.mergedImpacts.size} attribution_bytes=$bytes source_count=10000 projection_bytes=${RelationSnapshotCodec.encode(tracked).toByteArray().size}")
        assertTrue(tracked.mergedImpacts.size<=10000);assertTrue(bytes<=1024*tracked.mergedImpacts.size+2)
        assertEquals(999,tracked.fulfillmentSources.size)
    }
    @Test fun retractingAdjustmentsRestoresMergedTargetsWithoutReplayingOthers(){
        for(mode in listOf("explanation","apology","repair")){
            val target=row(1).copy(v=if(mode=="repair").9 else .3)
            val adjustment=row(2).let{r->when(mode){
                "explanation"->r.copy(explains=target.source,explainedAt=target.at,excuse="工作忙")
                "apology"->r.copy(previous=target.source,correction="道歉和好")
                else->r.copy(repairs=target.source,repairCompleted=true)}}
            for(days in listOf(40L,100L)){
                val now=days*RelationshipProjector.DAY
                val tail=(3L..800).map{row(it).copy(at=now,day=days)}
                val all=listOf(target,adjustment)+tail
                val before=feel(null,all,now)
                assertTrue(mode,target.source in before.cursor.mergedImpacts)
                val wire=com.google.gson.Gson().toJson(before.saved)
                val saved=com.google.gson.Gson().fromJson(wire,RelationFeelingSaved::class.java)
                val after=feel(saved,all.filter{it.source!=adjustment.source},now)
                val reference=RelationCursor(RelationStart.FRIENDS,parameters,compact=false).apply{trackMerged=true}
                all.forEach{reference.apply(it)};reference.retract(adjustment.source)
                assertEquals(mode,reference.trust,after.cursor.trust,1e-12)
                assertEquals(mode,reference.state().tension,after.cursor.state().tension,1e-12)
                assertEquals(mode,before.cursor.impacts.getValue(tail.last().source),after.cursor.impacts.getValue(tail.last().source))
                assertFalse(feel(after.saved,all.filter{it.source!=adjustment.source},now).changed)
            }
        }
    }
    @Test fun restoredMergedTargetsCanBeExplainedApologizedToOrRepairedAgain(){
        for(mode in listOf("explanation","apology","repair")){
            val target=row(1).copy(v=.9)
            fun action(id:Long,at:Long)=row(id).copy(at=at,day=40).let{r->when(mode){
                "explanation"->r.copy(explains=target.source,explainedAt=target.at,excuse="工作忙")
                "apology"->r.copy(previous=target.source,correction="道歉和好")
                else->r.copy(repairs=target.source,repairCompleted=true)}}
            val first=action(2,2000);val now=40*RelationshipProjector.DAY
            val tail=(3L..800).map{row(it).copy(at=now,day=40)}
            val before=feel(null,listOf(target,first)+tail,now)
            assertTrue(target.source in before.cursor.mergedImpacts)
            if(mode=="apology")assertFalse(target.source in before.cursor.state().apologizable)
            val withdrawn=feel(before.saved,listOf(target)+tail,now)
            val second=action(801,now)
            val checked=RelationshipRecall.validate(second,withdrawn.cursor.state(),setOf(1L),setOf(target.source)){field,code->fail("$mode $field $code")}
            val after=feel(withdrawn.saved,listOf(target)+tail+checked,now)
            val reference=RelationCursor(RelationStart.FRIENDS,parameters,compact=false).apply{trackMerged=true}
            (listOf(target,first)+tail).forEach{reference.apply(it)};reference.retract(first.source);reference.apply(checked)
            assertEquals(mode,reference.trust,after.cursor.trust,1e-12)
            assertEquals(mode,reference.state().tension,after.cursor.state().tension,1e-12)
            if(mode=="explanation"){assertFalse(target.source in after.cursor.pending);assertTrue(after.cursor.explanations.values.any{it.source==second.source})}
            assertFalse(feel(after.saved,listOf(target)+tail+checked,now).changed)
        }
    }
}
