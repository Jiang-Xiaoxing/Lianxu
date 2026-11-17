package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*

class VideoPolicyTest {
    @Test fun decimalSeventyMegabytesIncludesTheBoundaryAndRejectsOneMoreByte(){
        VideoPolicy.requireSize(69_999_999);VideoPolicy.requireSize(70_000_000)
        try{VideoPolicy.requireSize(70_000_001);fail("oversized video accepted")}catch(e:IllegalArgumentException){assertEquals("暂不支持超过70 MB的视频",e.message)}
    }
    @Test fun everySecondIsKeptBeyondTwentyFrames(){
        val times=VideoPolicy.frameTimes(23_000).toList()
        assertEquals(23,times.size);assertEquals(0L,times.first());assertEquals(22_000L,times.last())
        assertTrue(times.zipWithNext().all{(a,b)->b-a==1000L})
    }
    @Test fun shortAndPartialLastSecondsAreNotSilentlyDropped(){
        assertEquals(listOf(0L),VideoPolicy.frameTimes(1).toList())
        assertEquals(listOf(0L),VideoPolicy.frameTimes(1000).toList())
        assertEquals(listOf(0L,1000L),VideoPolicy.frameTimes(1001).toList())
        assertEquals(24L,VideoPolicy.frameCount(23_001))
    }
}
