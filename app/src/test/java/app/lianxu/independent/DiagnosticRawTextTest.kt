package app.lianxu.independent

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** 详细诊断开关管住所有原文：关时只记字数。 */
class DiagnosticRawTextTest {
    @After fun reset(){Diagnostics.detailed=false}
    @Test fun offKeepsOnlyTheLength(){
        Diagnostics.detailed=false
        assertEquals("[详细诊断未开启，共5字]",Diagnostics.raw("今天好累啊"))
        assertNull(Diagnostics.raw(null))
        val counted=Diagnostics.rawJson(mapOf("memories" to listOf("她喜欢吃粥"))) as String
        assertTrue(counted,counted.startsWith("[详细诊断未开启，共")&&!counted.contains("粥"))
    }
    @Test fun onKeepsTheOriginal(){
        Diagnostics.detailed=true
        assertEquals("今天好累啊",Diagnostics.raw("今天好累啊"))
        val payload=mapOf("memories" to listOf("她喜欢吃粥"))
        assertSame(payload,Diagnostics.rawJson(payload))
    }
}
