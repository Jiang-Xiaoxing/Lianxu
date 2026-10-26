package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*

class RelationshipPolicyTest {
    @Test fun updateClampHighScoreHalvingAndMissingPair(){
        val old=mapOf(RelationshipDimension.TRUST to RelationshipValue(50,"old-trust"),RelationshipDimension.FAMILIARITY to RelationshipValue(80,"old-familiar"),RelationshipDimension.SAFETY to RelationshipValue(55,"keep-whole-pair"))
        val r=RelationshipPolicy.update(old,mapOf(RelationshipDimension.TRUST to ProposedRelationshipValue(99,"new-trust"),RelationshipDimension.FAMILIARITY to ProposedRelationshipValue(100,"new-familiar"),RelationshipDimension.SAFETY to ProposedRelationshipValue(null,"must-not-replace")))
        assertEquals(53,r[RelationshipDimension.TRUST]!!.score);assertEquals(82,r[RelationshipDimension.FAMILIARITY]!!.score);assertEquals(old[RelationshipDimension.SAFETY],r[RelationshipDimension.SAFETY])
        assertEquals(0,r[RelationshipDimension.JEALOUSY]!!.score)
    }
    @Test fun firstInitializationAndNegativeNotHalved(){
        assertEquals(99,RelationshipPolicy.update(emptyMap(),mapOf(RelationshipDimension.CLOSENESS to ProposedRelationshipValue(99,"start")))[RelationshipDimension.CLOSENESS]!!.score)
        val old=mapOf(RelationshipDimension.CLOSENESS to RelationshipValue(90,"old"),RelationshipDimension.TRUST to RelationshipValue(90,"old"))
        val r=RelationshipPolicy.update(old,old.keys.associateWith{ProposedRelationshipValue(0,"changed")})
        assertEquals(82,r[RelationshipDimension.CLOSENESS]!!.score);assertEquals(87,r[RelationshipDimension.TRUST]!!.score)
    }
    @Test fun defaultFiveAndExplicitHistoryConsent(){
        assertFalse(RelationshipPolicy.shouldSchedule(true,true,true,4));assertTrue(RelationshipPolicy.shouldSchedule(true,true,true,5))
        assertFalse(RelationshipPolicy.shouldSchedule(true,true,false,50));assertFalse(RelationshipPolicy.shouldSchedule(false,true,true,50));assertFalse(RelationshipPolicy.shouldSchedule(true,false,true,50))
    }
    @Test(expected=IllegalArgumentException::class) fun missingStatementIsNotSuccessfulEvaluation(){RelationshipPolicy.update(emptyMap(),mapOf(RelationshipDimension.TRUST to ProposedRelationshipValue(60,"")))}
}
