package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** 相处页「心结」标签列真正的心结，不列「等你解释的小事」；解释过还没弥补的心结仍在。 */
class RelationshipKnotTabTest {
    private val p=RelationTemplate.DIRECT.parameters
    private fun row(id:Long)=RelationInput(SourceRef("r",id,1),0,0,"关于关系")
    private val big=row(1).copy(v=.9)
    private val small=row(2).copy(disappointment=.27,explanationPolicy=1)
    private val quote:(SourceRef)->String?={"原话${it.turnId}"}

    @Test fun aSmallLetdownWaitingForAnExplanationIsNotAKnot(){
        val c=RelationCursor(RelationStart.FRIENDS,p).also{it.apply(big);it.apply(small)}
        val s=c.state()
        assertTrue("the small one is only waiting for an explanation",small.source in s.ledger)
        assertEquals(listOf(big.source),RelationshipQuery.knots(s,quote))
    }
    @Test fun anExplainedButUnrepairedKnotStaysListed(){
        val c=RelationCursor(RelationStart.FRIENDS,p).also{it.apply(big)}
        c.apply(row(3).copy(explains=big.source,releaseKind="有原因",excuse="工作忙",formatVersion=2,explanationPolicy=1))
        val s=c.state()
        assertTrue("explained, so it no longer waits for one",s.explanations.any{it.target==big.source})
        assertEquals(listOf(big.source),RelationshipQuery.knots(s,quote))
    }
    @Test fun aKnotWhoseWordsWereDeletedIsNotListed(){
        val s=RelationCursor(RelationStart.FRIENDS,p).also{it.apply(big)}.state()
        assertTrue(RelationshipQuery.knots(s){null}.isEmpty())
    }
}
