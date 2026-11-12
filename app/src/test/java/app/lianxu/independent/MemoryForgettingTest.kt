package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MemoryForgettingTest {
    private val day=86400000L
    @Test fun halfLivesAndClockRollback(){
        for(tier in MemoryImportance.entries){val state=MemoryRecallState(0,0,tier.halfLife,100.0)
            assertEquals(100.0,state.clarity(-day),0.0);assertEquals(100.0,state.clarity(0),0.0)
            assertEquals(50.0,state.clarity((tier.halfLife*day).toLong()),1e-9);assertEquals(25.0,state.clarity((tier.halfLife*2*day).toLong()),1e-9)}
    }
    @Test fun reinforcementIsDailyAndCapped(){
        val old=MemoryRecallState(0,0,120.0,100.0);val next=old.reinforce(120*day)
        assertEquals(60.0,next.anchor,1e-9);assertEquals(240.0,next.halfLife,0.0);assertEquals(1,next.count)
        assertEquals(next,next.reinforce(120*day+1));assertEquals(2,next.reinforce(121*day).count)
        assertEquals(36500.0,MemoryRecallState(0,0,36500.0,100.0).reinforce(day).halfLife,0.0)
    }
    @Test fun deliveryHasExactBoundariesAndNoOriginalFallback(){
        val m=StoredMemory("m","r",MemoryCategory.EXPERIENCE_MEMORY,"2026年在杭州买了123朵花",about=MemoryAbout.BOTH,gist="收到过礼物",trace="送过花",createdAt=0)
        assertEquals(m.text,MemoryForgetting.text(m,61.0){m.text})
        assertEquals("收到过礼物",MemoryForgetting.text(m,60.0){error("原文不应读取")})
        assertEquals("收到过礼物",MemoryForgetting.text(m,20.0){error("原文不应读取")})
        assertEquals("送过花",MemoryForgetting.text(m,19.0){error("原文不应读取")})
        assertFalse(MemoryForgetting.text(m.copy(gist=null,trace=null,tags=listOf("礼物")),30.0){error("原文不应读取")}.contains("杭州"))
    }
    @Test fun generalFactsAreNotPromotedButManualMemoryIs(){
        val m=StoredMemory("m","r",MemoryCategory.USER_PROFILE,"事实",createdAt=0)
        assertEquals(MemoryImportance.NORMAL,MemoryForgetting.importance(m))
        assertEquals(MemoryImportance.HIGH,MemoryForgetting.importance(m.copy(manual=true)))
    }
    @Test fun thresholdDays(){
        for((half,mid,low) in listOf(Triple(21.0,15,49),Triple(120.0,88,279))){val s=MemoryRecallState(0,0,half,100.0)
            assertTrue(s.clarity((mid-1)*day)>60);assertTrue(s.clarity((mid+1)*day)<60)
            assertTrue(s.clarity((low-1)*day)>20);assertTrue(s.clarity((low+1)*day)<20)}
    }
    @Test fun coreHysteresisAndPromptByteBudget(){
        val values=(1..1000).map{StoredMemory("m$it","r",MemoryCategory.EXPERIENCE_MEMORY,"合成经历原话".repeat(20),about=MemoryAbout.BOTH,createdAt=0,importance=MemoryImportance.entries[it%3],gist="一起做过一件事",trace="旧事")}
        val first=values.first()
        assertEquals(listOf(first),MemoryForgetting.selectCore(listOf(first),mapOf(first.id to 55.0),setOf(first.id),10000){it.text})
        assertTrue(MemoryForgetting.selectCore(listOf(first),mapOf(first.id to 55.0),emptySet(),10000){it.text}.isEmpty())
        assertTrue(MemoryForgetting.selectCore(listOf(first),mapOf(first.id to 49.0),setOf(first.id),10000){it.text}.isEmpty())
        for(n in listOf(100,500,1000)){
            val rows=values.take(n);val clarity=rows.associate{it.id to (it.id.drop(1).toInt()%101).toDouble()}
            val selected=MemoryForgetting.selectCore(rows,clarity,emptySet(),1000){it.text}
            fun render(list:List<StoredMemory>,label:String,old:Boolean=false)=list.joinToString("\n\n"){"$label ${it.id}@${it.version}:\n"+if(old)it.text else MemoryForgetting.text(it,clarity.getValue(it.id)){it.text}}
            val core=render(selected,"核心记忆");val hits=rows.filter{it !in selected&&clarity.getValue(it.id)>=20}.take(15)
            println("memory_fixture count=$n core_bytes=${core.toByteArray().size} hit_bytes=${render(hits,"关键词记忆").toByteArray().size} old_core_all_bytes=${render(rows,"核心记忆",true).toByteArray().size} old_same_hits_bytes=${render(hits,"关键词记忆",true).toByteArray().size}")
            assertTrue(core.toByteArray().size<=6144)
            assertTrue(selected.sumOf{ConservativeTokens.tokens(MemoryForgetting.text(it,clarity.getValue(it.id)){it.text})+ConservativeTokens.tokens("核心记忆 ${it.id}@${it.version}")+16}<=1000)
            assertTrue(rows.all{it.version==1})
        }
    }
}
