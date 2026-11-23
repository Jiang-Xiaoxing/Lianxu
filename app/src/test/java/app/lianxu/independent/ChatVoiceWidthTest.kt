package app.lianxu.independent

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class ChatVoiceWidthTest {
    @Test fun approvedWidthsUseEachClipsTotalMilliseconds() {
        val times = listOf(2000L, 8000L, 20000L, 2000L)
        assertEquals(listOf(84.dp, 126.dp, 200.dp, 84.dp), times.map { voiceBubbleWidth(it, 320.dp) })
        assertEquals(87.5.dp, voiceBubbleWidth(2500, 320.dp))
    }

    @Test fun unknownShortAndVeryLongAreBounded() {
        for (duration in listOf(null, -1L, 0L, 1L, 750L, 1999L)) {
            assertEquals(84.dp, voiceBubbleWidth(duration, 320.dp))
        }
        assertEquals(224.dp, voiceBubbleWidth(Long.MAX_VALUE, 320.dp))
        val samples = (0L..30000L step 100).map { voiceBubbleWidth(it, 320.dp).value }
        assertTrue(samples.zipWithNext().all { (left, right) -> right >= left && right - left <= 0.71f })
    }

    @Test fun narrowParentsRetainRoomAndLargeLabelsFitWherePossible() {
        assertEquals(138.dp, voiceBubbleWidth(20000, 150.dp))
        assertEquals(84.dp, voiceBubbleWidth(2000, 90.dp))
        assertEquals(70.dp, voiceBubbleWidth(null, 70.dp))
        assertEquals(0.dp, voiceBubbleWidth(20000, 0.dp))
        assertEquals(98.dp, voiceBubbleWidth(2000, 320.dp, 40.dp))
    }
}
