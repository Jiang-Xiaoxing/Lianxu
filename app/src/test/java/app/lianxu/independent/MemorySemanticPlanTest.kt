package app.lianxu.independent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MemorySemanticPlanTest {
    private fun fact(ref:String,text:String="不喝咖啡",fresh:Boolean=true)=SemanticFact(ref,StoredMemory(ref,"a",MemoryCategory.PREFERENCE_BOUNDARY,text,about=MemoryAbout.USER),if(fresh)ref else null,if(fresh)"ADD"else"OLD")
    private fun vector(degrees:Double)=doubleArrayOf(cos(degrees*PI/180),sin(degrees*PI/180))
    private fun answer(pairs:List<SemanticPair>,facts:List<SemanticFact>,verdict:SemanticVerdict=SemanticVerdict.DUPLICATE):JsonObject {
        val map=facts.associateBy{it.ref}
        return JsonObject().apply{
            addProperty("batch_id","test");addProperty("complete",true)
            for(kind in listOf("NEW_OLD","NEW_NEW"))add(kind.lowercase(),JsonArray().apply{pairs.filter{it.kind==kind}.forEach{p->add(JsonObject().apply{
                addProperty("id",p.id);addProperty("verdict",verdict.name);addProperty("left_quote",map.getValue(p.left).memory.text);addProperty("right_quote",map.getValue(p.right).memory.text);addProperty("reason","合成配对判断")
            })}})
        }
    }
    @Test fun sameVectorsSupplyBothComparisonKindsAndOldOldIsNotAsked(){
        val f=listOf(fact("N1"),fact("N2","不再喝咖啡"),fact("O1","不喝咖啡了",false),fact("O2","咖啡",false))
        val p=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)})
        assertEquals(4,p.count{it.kind=="NEW_OLD"});assertEquals(1,p.count{it.kind=="NEW_NEW"});assertEquals(5,p.size)
    }
    @Test fun aBridgeRequiresADirectQuestionForItsLowSimilarityEndpoints(){
        val f=listOf(fact("A"),fact("B"),fact("C"));val p=MemorySemanticPlan.pairs(f,mapOf("A" to vector(0.0),"B" to vector(20.0),"C" to vector(40.0)))
        assertEquals(3,p.size);assertTrue(p.any{setOf(it.left,it.right)==setOf("A","C")})
    }
    @Test fun lowSimilarityAndDifferentRolesOrSubjectsNeverAuthorizeAMerge(){
        val a=fact("A");val b=fact("B").let{it.copy(memory=it.memory.copy(friendId="b"))};val c=fact("C").let{it.copy(memory=it.memory.copy(about=MemoryAbout.CHARACTER))}
        assertTrue(MemorySemanticPlan.pairs(listOf(a,b,c),mapOf("A" to vector(0.0),"B" to vector(0.0),"C" to vector(0.0))).isEmpty())
        assertTrue(MemorySemanticPlan.pairs(listOf(a,fact("D")),mapOf("A" to vector(0.0),"D" to vector(90.0))).isEmpty())
    }
    @Test fun responseMustExplicitlyCoverBothArraysEveryPairAndTheExactBatch(){
        val f=listOf(fact("N1"),fact("N2"),fact("O1",fresh=false));val p=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)});val map=f.associateBy{it.ref}
        assertEquals(p.size,MemorySemanticPlan.parse("test",p,map,answer(p,f).toString()).size)
        val incomplete=answer(p,f).apply{getAsJsonArray("new_new").remove(0)}
        assertTrue(runCatching{MemorySemanticPlan.parse("test",p,map,incomplete.toString())}.isFailure)
        val missingArray=answer(p,f).apply{remove("new_new")};assertTrue(runCatching{MemorySemanticPlan.parse("test",p,map,missingArray.toString())}.isFailure)
        assertTrue(runCatching{MemorySemanticPlan.parse("other",p,map,answer(p,f).toString())}.isFailure)
    }
    @Test fun invalidIdsDuplicateFieldsAndFabricatedQuotesAreRejected(){
        val f=listOf(fact("N1"),fact("O1",fresh=false));val p=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)});val map=f.associateBy{it.ref}
        val bad=answer(p,f).apply{getAsJsonArray("new_old")[0].asJsonObject.addProperty("id","not-provided")};assertTrue(runCatching{MemorySemanticPlan.parse("test",p,map,bad.toString())}.isFailure)
        val quote=answer(p,f).apply{getAsJsonArray("new_old")[0].asJsonObject.addProperty("left_quote","编造的原文")};assertTrue(runCatching{MemorySemanticPlan.parse("test",p,map,quote.toString())}.isFailure)
        val duplicate=answer(p,f).toString().replace("\"complete\":true","\"complete\":true,\"complete\":true");assertTrue(runCatching{MemorySemanticPlan.parse("test",p,map,duplicate)}.isFailure)
    }
    @Test fun duplicateCannotChangeDatesOrLifecycleDespiteAValidLookingModelDecision(){
        val a=fact("N1");val b=fact("O1",fresh=false).let{it.copy(memory=it.memory.copy(eventDate="2026-10-27"))};val f=listOf(a,b)
        val p=listOf(SemanticPair("P1","N1","O1","NEW_OLD"));assertTrue(runCatching{MemorySemanticPlan.parse("test",p,f.associateBy{it.ref},answer(p,f).toString())}.isFailure)
    }
    @Test fun contradictoryTriangleDoesNotProduceAPartialMerge(){
        val f=listOf(fact("A"),fact("B"),fact("C"));val pairs=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)})
        val d=pairs.map{SemanticDecision(it,if(setOf(it.left,it.right)==setOf("A","C"))SemanticVerdict.DIFFERENT else SemanticVerdict.DUPLICATE,"合成判断")}
        assertTrue(runCatching{MemorySemanticPlan.actions(f,d)}.isFailure)
    }
    @Test fun newNewDuplicatesUseOneAnchorButDifferentFactsFromOneSentenceRemain(){
        val f=listOf(fact("A"),fact("B"));val p=listOf(SemanticPair("P1","B","A","NEW_NEW"))
        val actions=MemorySemanticPlan.actions(f,p.map{SemanticDecision(it,SemanticVerdict.DUPLICATE,"同一事实")});assertEquals("A",actions.getValue("B").duplicate);assertNull(actions["A"])
        assertTrue(MemorySemanticPlan.actions(f,p.map{SemanticDecision(it,SemanticVerdict.DIFFERENT,"同句不同事实")}).isEmpty())
    }
    @Test fun correctionSupplementAndUncertaintyBecomeCandidatesInsteadOfOverwriting(){
        val f=listOf(fact("N1"),fact("O1",fresh=false));val p=SemanticPair("P1","N1","O1","NEW_OLD")
        for(v in listOf(SemanticVerdict.CORRECTION,SemanticVerdict.SUPPLEMENT,SemanticVerdict.UNCERTAIN)){
            val a=MemorySemanticPlan.actions(f,listOf(SemanticDecision(p,v,"需要人工核对"))).getValue("N1")
            assertNotNull(a.candidate);assertNull(a.duplicate);assertEquals(if(v==SemanticVerdict.UNCERTAIN)null else "O1",a.target)
        }
    }
    @Test fun multipleOldMatchesDoNotArbitrarilyPickOrMergeOldRecords(){
        val f=listOf(fact("N1"),fact("O1",fresh=false),fact("O2",fresh=false));val p=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)})
        val a=MemorySemanticPlan.actions(f,p.map{SemanticDecision(it,SemanticVerdict.DUPLICATE,"同一事实")}).getValue("N1");assertNotNull(a.candidate);assertNull(a.duplicate)
    }
    @Test fun oldHistoricalEvidenceIsNotInjectedAsACurrentFact(){
        val old=fact("O1",fresh=false).let{it.copy(memory=it.memory.copy(evidence=listOf(MemoryEvidence(SourceRef("a",1,1),"user","old-sentinel","old-sentinel",1,"UTC"))))}
        val f=listOf(fact("N1"),old);val p=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)})
        assertFalse(MemorySemanticPlan.payload("test",p,f.associateBy{it.ref}).toString().contains("old-sentinel"))
    }
    @Test fun syntheticThresholdExperimentReportsMissesAndSuspiciousNonDuplicates(){
        // Hand-made vectors/labels only, not embeddings or judgments from a real model.
        val samples=listOf(.99 to true,.97 to true,.94 to true,.91 to true,.86 to true,.98 to false,.93 to false,.80 to false,.40 to false,.10 to false)
        val selected=samples.filter{it.first>=MemorySemanticPlan.THRESHOLD}
        val missed=samples.count{it.second&&it.first<MemorySemanticPlan.THRESHOLD};val suspiciousNonDuplicates=selected.count{!it.second}
        assertEquals(6,selected.size);assertEquals(1,missed);assertEquals(2,suspiciousNonDuplicates)
        println("合成阈值实验：10对，6对送判断；5对重复中漏筛1对；5对不同中2对被送复核。相似度不直接触发合并；非真实模型测评。")
    }
    @Test fun oneJudgmentRequestCoversBothKindsAndKeepsTheConfiguredReasoningBudget(){
        val f=listOf(fact("N1"),fact("N2"),fact("O1",fresh=false));val pairs=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)});val map=f.associateBy{it.ref}
        val work=MemoryWork("test","a",emptyList(),0,"","synthetic")
        val config=ModelConfig("synthetic","合成","custom","https://example.invalid/v1","mock",64000,2048,32768)
        val groups=MemorySemanticDeduplicator.split(work,pairs,map,config);assertEquals(1,groups.size)
        val request=MemorySemanticDeduplicator.request("test",groups.single(),map,config)
        assertEquals(32768,request.outputLimit)
        val data=RoleArchive.parse(request.messages.last().content.toByteArray());assertEquals(2,data["new_old"].asJsonArray.size());assertEquals(1,data["new_new"].asJsonArray.size())
        println("合成请求规模：新旧2对+新新1对共1次判断；估算输入 ${request.messages.sumOf{ConservativeTokens.tokens(it.content)}} Token，生成上限 ${request.outputLimit}；未调用真实模型。")
    }
    @Test fun judgmentSplittingPreservesEveryPairAndEmptyInputNeedsNoCalls(){
        val f=(1..5).map{fact("N$it")};val pairs=MemorySemanticPlan.pairs(f,f.associate{it.ref to vector(0.0)});val map=f.associateBy{it.ref};val w=MemoryWork("test","a",emptyList(),0,"","")
        val c=ModelConfig("test","合成","custom","https://example.invalid/v1","mock",64000,1024)
        val groups=MemorySemanticDeduplicator.split(w,pairs,map,c);assertTrue(groups.size>1);assertEquals(pairs,groups.flatten());assertTrue(groups.all{128+it.size*256<=1024})
        assertTrue(MemorySemanticDeduplicator.split(w,emptyList(),map,c).isEmpty())
    }
    @Test fun conflictingJudgmentsOfAliasesAlreadyProvedEqualCannotSplitTheFact(){
        val a=fact("N1");val b=fact("N2").copy(memory=a.memory);val old=fact("O1",fresh=false);val f=listOf(a,b,old)
        val decisions=listOf(SemanticDecision(SemanticPair("P1","N1","O1","NEW_OLD"),SemanticVerdict.DUPLICATE,"同一事实"),SemanticDecision(SemanticPair("P2","N2","O1","NEW_OLD"),SemanticVerdict.DIFFERENT,"不同事实"))
        assertTrue(runCatching{MemorySemanticPlan.actions(f,decisions)}.isFailure)
    }
}
