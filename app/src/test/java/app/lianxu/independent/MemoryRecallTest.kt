package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryRecallTest {
    private fun m(id:String,text:String,friend:String="a",core:Boolean=false)=StoredMemory(id,friend,MemoryCategory.STATE_FACT,text,core=core,manual=true,about=MemoryAbout.BOTH)
    private fun edge(s:String,t:String,reason:String="garden outing",kind:String="explicit_reference",friend:String="a",sv:Int=1)=
        MemoryRecall.Edge(friend,s,sv,t,1,kind,reason,"exact source quote","exact target quote","synthetic-batch")
    private fun select(q:String,rows:List<StoredMemory>,edges:List<MemoryRecall.Edge> = emptyList(),limit:Int=15)=MemoryRecall.select("a",q,rows,edges,limit)

    @Test fun scoresAllCandidatesIncludingBestAfterNinetySix(){
        val rows=(1..150).map{m("early-$it","garden "+"unrelated ".repeat(80))}+m("zz-best","garden tea")
        assertEquals("zz-best",select("garden tea",rows,limit=1).single().memory.id)
        assertEquals("zz-best",MemoryRecall.relationCandidates("garden tea",rows).first().id)
        assertTrue(MemoryRecall.summaryContext("garden tea",rows).any{it.id=="zz-best"})
    }
    @Test fun sharedNeighbourIsUniqueAndDegreeDoesNotAccumulate(){
        val a=m("a","garden");val b=m("b","garden");val n=m("n","Saturday afternoon")
        val one=select("garden",listOf(a,b,n),listOf(edge("a","n"))).first{it.memory.id=="n"}
        val many=select("garden",listOf(a,b,n),List(100){edge(if(it%2==0)"a" else "b","n")})
        assertEquals(1,many.count{it.memory.id=="n"});assertEquals(one.score,many.first{it.memory.id=="n"}.score,0.00001)
        assertTrue(one.score<=MemoryRecall.MAX_ASSOCIATION_BONUS)
    }
    @Test fun strongDirectCannotBeDisplacedAndUnrelatedOrWeakLinksDoNotPad(){
        val rows=listOf(m("direct","garden"),m("neighbour","Saturday afternoon"))
        assertEquals("direct",select("garden",rows,List(100){edge("direct","neighbour")},1).single().memory.id)
        assertEquals(1,select("garden",rows,listOf(edge("direct","neighbour","unrelated astronomy"))).size)
        assertEquals(1,select("garden",rows,listOf(edge("direct","neighbour",kind="guessed_cause"))).size)
    }
    @Test fun noDirectMatchMeansNoExpansion(){
        assertTrue(select("garden",listOf(m("a","astronomy"),m("b","Saturday")),listOf(edge("a","b"))).isEmpty())
    }
    @Test fun twoHopsIncludeCButNotThirdHopDAndCyclesTerminate(){
        val rows=listOf(m("a","garden"),m("b","Saturday"),m("c","Sunday"),m("d","Monday"))
        val hits=select("garden",rows,listOf(edge("a","b"),edge("b","c"),edge("c","d"),edge("b","a")))
        assertEquals(setOf("a","b","c"),hits.map{it.memory.id}.toSet())
        val c=hits.first{it.memory.id=="c"};assertEquals(listOf("a","b","c"),c.path)
        val reason=MemoryRecall.relevance("garden","garden outing")
        assertEquals(0.5*(0.10+0.10*0.85*0.85*reason),c.score,0.00001)
        assertTrue(c.score<hits.first{it.memory.id=="b"}.score)
    }
    @Test fun bestPathCanBeLongerOrShorterWithoutGlobalVisitedOrDegreeInflation(){
        for(fillers in listOf(3,100)){
            val weak=m("weak","garden "+"filler ".repeat(fillers))
            val rows=listOf(m("strong","garden"),weak,m("middle","Saturday"),m("target","Sunday"))
            val links=listOf(edge("weak","target"),edge("strong","middle"),edge("middle","target"))
            val long=0.5*(0.10+0.10*0.85*0.85*MemoryRecall.relevance("garden","garden outing"))
            val short=0.10*MemoryRecall.direct("garden",weak)+0.10*0.85*MemoryRecall.relevance("garden","garden outing")
            val expected=if(long>short)listOf("strong","middle","target") else listOf("weak","target")
            for(order in listOf(links,links.reversed(),links+links)){
                val target=select("garden",rows,order).first{it.memory.id=="target"}
                assertEquals(maxOf(long,short),target.score,0.00001);assertEquals(expected,target.path)
            }
        }
    }
    @Test fun invalidForeignOrStaleIntermediateCannotBridgeSecondHop(){
        val a=m("a","garden");val b=m("b","Saturday");val c=m("c","Sunday")
        for(bad in listOf(b.copy(friendId="b"),b.copy(valid=false),b.copy(life=MemoryLife.RETRACTED),b.copy(version=2))){
            assertEquals(listOf("a"),select("garden",listOf(a,bad,c),listOf(edge("a","b"),edge("b","c"))).map{it.memory.id})
        }
        val hits=select("garden",listOf(a,b,c),listOf(edge("a","b"),edge("b","c",friend="b")))
        assertFalse(hits.any{it.memory.id=="c"})
    }
    @Test fun weakSecondHopDoesNotPadOrDisplaceStrongDirect(){
        val rows=listOf(m("a","garden"),m("b","Saturday"),m("c","Sunday"))
        val weakReason="garden "+"irrelevant ".repeat(100)
        val hits=select("garden",rows,listOf(edge("a","b"),edge("b","c",weakReason)))
        assertEquals(setOf("a","b"),hits.map{it.memory.id}.toSet())
        assertEquals("a",select("garden",rows,listOf(edge("a","b"),edge("b","c")),1).single().memory.id)
    }
    @Test fun onlyEightScoredDirectRootsMayStartWalks(){
        val rows=(0 until 9).map{m("root-$it","garden")}+m("neighbour","Saturday")
        assertFalse(select("garden",rows,listOf(edge("root-8","neighbour"))).any{it.memory.id=="neighbour"})
    }
    @Test fun twoHopHitsStillShareAdjustableFifteenAndKeepCoreIndependent(){
        val rows=(0 until 10).map{m("root-$it","garden")}+m("bridge","Saturday")+
            (0 until 20).map{m("deep-$it","Sunday $it")}+m("core","garden",core=true)
        val links=listOf(edge("root-0","bridge"))+(0 until 20).map{edge("bridge","deep-$it")}
        val hits=select("garden",rows,links)
        assertEquals(15,hits.size);assertEquals(15,hits.map{it.memory.id}.toSet().size)
        assertEquals(10,hits.count{it.via==null});assertTrue(hits.any{it.path.size==3})
        assertTrue(hits.none{it.memory.core});assertEquals(3,select("garden",rows,links,3).size)
    }
    @Test fun scopeCurrentVersionAndLifeAreMandatory(){
        val rows=listOf(m("a","garden"),m("b","Saturday"),m("foreign","garden","b"),m("cancelled","garden").copy(life=MemoryLife.CANCELLED),m("invalid","garden").copy(valid=false))
        assertEquals(listOf("a"),select("garden",rows,listOf(edge("a","b",sv=2),edge("a","b",friend="b"))).map{it.memory.id})
    }
    @Test fun totalCountIsConfigurableAndCoreNeverConsumesIt(){
        val rows=(1..25).map{m("$it","garden")}+m("core","garden",core=true)
        assertEquals(15,select("garden",rows).size);assertEquals(3,select("garden",rows,limit=3).size)
        assertTrue(select("garden",rows).none{it.memory.core});assertEquals(25,select("garden",rows,limit=30).size)
        assertTrue(select("ocean",rows).isEmpty())
    }
    @Test fun mixedDirectAndAssociatedHitsShareFifteen(){
        val direct=(0 until 10).map{m("entry-$it","garden")}
        val neighbours=(0 until 12).map{m("neighbour-$it","Saturday afternoon $it")}
        val rows=direct+neighbours+m("core","garden",core=true)
        val links=neighbours.map{edge("entry-0",it.id)}
        val hits=select("garden",rows,links)
        assertEquals(15,hits.size);assertEquals(15,hits.map{it.memory.id}.toSet().size)
        assertEquals(10,hits.count{it.via==null});assertEquals(5,hits.count{it.via!=null})
        assertTrue(hits.none{it.memory.core});assertEquals(3,select("garden",rows,links,3).size)
    }
    @Test fun percentageBoundsAndContributionsUseOneTheoreticalScale(){
        assertEquals(0.19,MemoryRecall.MAX_FIRST_HOP_BONUS,0.0000001)
        assertEquals(0.0905,MemoryRecall.MAX_SECOND_HOP_BONUS,0.0000001)
        assertEquals(2.19,MemoryRecall.MAX_RAW_SCORE,0.0000001)
        assertEquals(0.0,MemoryRecall.points(0.0),0.0);assertEquals(100.0,MemoryRecall.points(MemoryRecall.MAX_RAW_SCORE),0.0)
        val hit=MemoryRecall.Hit(m("synthetic","garden"),0.8,0.65,"root")
        assertEquals(hit.points.total,hit.points.direct+hit.points.association,0.0000001)
        assertEquals(MemoryRecall.points(0.55),MemoryRecall.strongDirectPoints,0.0)
        assertEquals(MemoryRecall.points(0.10),MemoryRecall.secondHopRelevancePoints,0.0)
    }
    @Test fun percentageScaleIsBoundedStrictlyMonotoneAndDoesNotClip(){
        val values=(0..2190).map{MemoryRecall.points(it/1000.0)}
        assertTrue(values.all{it in 0.0..100.0});assertTrue(values.zipWithNext().all{it.second>it.first})
        assertThrows(IllegalArgumentException::class.java){MemoryRecall.points(-0.01)}
        assertThrows(IllegalArgumentException::class.java){MemoryRecall.points(2.20)}
    }
    @Test fun percentageDiagnosticsKeepRankAndEqualScoreTieRules(){
        val rows=listOf(m("b","garden"),m("a","garden"),m("c","garden tea"),m("n","Saturday"))
        val hits=select("garden",rows,listOf(edge("a","n")))
        assertEquals(listOf("a","b"),hits.take(2).map{it.memory.id})
        assertEquals(hits[0].points.total,hits[1].points.total,0.0)
        assertEquals(hits.sortedByDescending{it.score}.map{it.memory.id},hits.sortedByDescending{it.points.total}.map{it.memory.id})
        val diagnostics=MemoryRecall.diagnostics(hits)
        assertEquals(hits.map{it.memory.id},diagnostics.map{it["memoryId"]})
        for(row in diagnostics)for(key in listOf("totalPoints","directPoints","associationPoints"))assertTrue((row.getValue(key) as Double) in 0.0..100.0)
        assertTrue(diagnostics.all{it.keys==setOf("memoryId","totalPoints","directPoints","associationPoints","path")})
    }
    @Test fun percentagePresentationDoesNotAlterStrongDirectProtection(){
        val root=m("root","garden");val strong=m("strong","garden garden filler filler filler");val weak=m("weak","garden filler filler")
        assertTrue(MemoryRecall.direct("garden",strong)>=MemoryRecall.STRONG_DIRECT)
        assertTrue(MemoryRecall.direct("garden",weak)<MemoryRecall.STRONG_DIRECT)
        assertEquals(listOf("root","strong"),select("garden",listOf(root,strong,weak),listOf(edge("root","weak")),2).map{it.memory.id})
    }

    @Test fun independentCosineAndDateBonusHaveExplicitBehaviour(){
        assertEquals(1.0,MemoryRecall.relevance("garden tea","garden tea"),0.00001)
        assertEquals(0.0,MemoryRecall.relevance("garden","ocean"),0.00001)
        assertTrue(MemoryRecall.relevance("2026年10月25日","2026-10-25")>=1.0)
        assertTrue(MemoryRecall.relevance("花园散步","花园散步约定")>0)
    }
}

