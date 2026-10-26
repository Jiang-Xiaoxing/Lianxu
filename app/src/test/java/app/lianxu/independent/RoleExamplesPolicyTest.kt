package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RoleExamplesPolicyTest {
    private fun c(id:String="a",u:String="今天一起散步",a:String="我们慢慢走，听听路边的风声。",next:String="")=ExampleCandidate(id,1,DialogueExample(u,a),next)
    @Test fun boundedCompleteOriginalTextOnly(){
        assertTrue(RoleExamplesPolicy.eligible(c()))
        listOf(c(u="短"),c(a="短回复"),c(u="长".repeat(301)),c(a="长".repeat(801))).forEach{assertFalse(RoleExamplesPolicy.eligible(it))}
    }
    @Test fun failuresAndFollowingCorrectionsAreExcluded(){
        listOf(c(a="请求失败，稍后再试一下。"),c(a="HTTP 500 server failed"),c(next="不是这个意思，请重新回答"),c(next="that's wrong")).forEach{assertFalse(RoleExamplesPolicy.eligible(it))}
    }
    @Test fun balancedScenesDoNotLetSmallTalkCrowdOutOthers(){
        val values=(1..20).map{c("$it",u="今天晴天啦")}+c("sad",u="今天有点难过")+c("love",u="我真的很想你")
        assertEquals(listOf("1","sad","love"),RoleExamplesPolicy.balanced(values,3).map{it.id})
        assertEquals(15,RoleExamplesPolicy.balanced(values).size)
    }
    @Test fun selectionPreservesExactOriginalObjectsAndOrder(){
        val candidates=listOf(c("a"),c("b"),c("c"));val result=RoleExamplesPolicy.selected("```json\n{\"ids\":[\"c\",\"a\"]}\n```",candidates)
        assertSame(candidates[2],result[0]);assertSame(candidates[0],result[1])
    }
    @Test fun rejectsDuplicatesUnknownAndInvalidCounts(){
        for(raw in listOf("{\"ids\":[\"a\",\"a\"]}","{\"ids\":[\"a\",\"unknown\"]}","{\"ids\":[\"a\"]}","{\"ids\":[1,2]}","{\"ids\":[\"a\",\"b\",\"c\",\"d\"]}"))assertTrue(runCatching{RoleExamplesPolicy.selected(raw,listOf(c("a"),c("b"),c("c"),c("d")))}.isFailure)
    }
    @Test fun modelRewritingDoesNotReplaceOriginals(){
        val candidates=listOf(c("a"),c("b"));val result=RoleExamplesPolicy.selected("{\"ids\":[\"a\",\"b\"],\"assistant\":\"fabricated\"}",candidates)
        assertEquals(candidates.map{it.pair},result.map{it.pair})
    }
}
