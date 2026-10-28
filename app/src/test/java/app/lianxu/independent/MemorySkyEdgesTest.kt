package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*

class MemorySkyEdgesTest {
    private val a=StoredMemory("a","role",MemoryCategory.STATE_FACT,"合成事实甲")
    private val b=StoredMemory("b","role",MemoryCategory.STATE_FACT,"合成事实乙")
    private val edge=MemoryRecall.Edge("role","a",1,"b",1,"RELATED","合成理由","甲","乙","batch")
    @Test fun currentPersistedPairRemainsVisible(){assertEquals(listOf(edge),visibleSkyEdges(listOf(a,b),listOf(edge)))}
    @Test fun lateEdgeCannotConnectCorrectedVersion(){assertTrue(visibleSkyEdges(listOf(a.copy(version=2),b),listOf(edge)).isEmpty())}
    @Test fun otherRoleInvalidOrRetractedNeverConnects(){
        listOf(b.copy(friendId="other"),b.copy(valid=false),b.copy(life=MemoryLife.RETRACTED)).forEach{assertTrue(visibleSkyEdges(listOf(a,it),listOf(edge)).isEmpty())}
        assertTrue(visibleSkyEdges(listOf(a),listOf(edge)).isEmpty())
    }
}
