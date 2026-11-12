package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** The stream guard must name what went wrong: a byte/line cap, a body that never framed SSE, or content checks. */
class SseStreamGuardTest {
    private fun SseStreamGuard.feed(text:String):String?=text.toByteArray(Charsets.UTF_8).let{read(it,0,it.size)}
    private fun SseStreamGuard.feed(byte:Int):String?=byteArrayOf(byte.toByte()).let{read(it,0,it.size)}

    @Test fun wireCountStaysSilentUntilTheCapIsCrossed(){
        val guard=SseStreamGuard(maxWireBytes=10)
        assertNull(guard.feed("data: "));assertEquals("wire_limit",guard.feed("abcde"))
        assertEquals(11L,guard.bytes)
    }

    @Test fun emptyReadsNeverMoveTheCounter(){
        val guard=SseStreamGuard(maxWireBytes=5)
        assertNull(guard.read(ByteArray(0),0,0));assertEquals(0L,guard.bytes)
    }

    @Test fun onlySseFramingKeepsTheHeadAcceptable(){
        for(text in listOf("data: {\"a\":1}\n","event: message\ndata: {}\n","id: 7\ndata: {}\n","retry: 1000\ndata: {}\n",
            ": keep-alive\n\ndata: {}\n","\n\ndata: {}\n","\uFEFFdata: {}\n"))assertNull(text,SseStreamGuard().feed(text))
    }

    @Test fun aBodyThatIsNotSseIsRejectedFromItsFirstBytes(){
        for(body in listOf("<!DOCTYPE html><html><body>gateway</body></html>\n","{\"error\":{\"message\":\"blocked\"}}\n","[1,2,3]\n")){
            assertEquals(body,"protocol_probe",SseStreamGuard().feed(body))
        }
        val gzip=byteArrayOf(0x1f,0x8b.toByte(),0x08,0x00,0x00)
        assertEquals("protocol_probe",SseStreamGuard().read(gzip,0,gzip.size))
    }

    @Test fun oneCompletedLineIsClassifiedAndSampled(){
        val guard=SseStreamGuard()
        guard.line("");guard.line(": keep-alive");guard.line("event: message");guard.line("id: 3")
        guard.line("data: {\"a\":1}");guard.line("data: [DONE]")
        assertEquals(1,guard.blankLines);assertEquals(1,guard.commentLines);assertEquals(2,guard.fieldLines)
        assertEquals(2,guard.dataLines);assertEquals(0,guard.otherLines)
        assertEquals(0,guard.snapshot()["other_lines"])
        assertTrue(guard.longestLine>=12)
    }

    @Test fun firstUnknownLineIsSampledAndTrimmed(){
        Diagnostics.detailed=true
        try{
        val guard=SseStreamGuard()
        val html="<!DOCTYPE html><html><body>blocked by gateway</body></html>"
        guard.line(html);guard.line("second line is not sampled")
        assertEquals(2,guard.otherLines)
        assertEquals(html.take(SseStreamGuard.SAMPLE_CHARS),guard.snapshot()["sample"])
        assertFalse(guard.snapshot()["sample"].toString().contains("second"))
        }finally{Diagnostics.detailed=false}
    }
    @Test fun withDetailedDiagnosticsOffTheSampleIsOnlyCounted(){
        Diagnostics.detailed=false
        val guard=SseStreamGuard();val html="<html>你好，这是聊天原文</html>"
        guard.line(html)
        assertEquals("[详细诊断未开启，共${html.length}字]",guard.snapshot()["sample"])
        assertEquals("nothing sampled stays empty","",SseStreamGuard().snapshot()["sample"])
    }

    @Test fun unknownFramingOnlyBecomesALineProtocolErrorAfterTheProbeBudget(){
        val guard=SseStreamGuard()
        // These lines pass the head check (they start with 'd') but are not SSE fields.
        guard.line("document without framing");guard.feed("data: {}")
        assertNull("还没读够，先不下结论",guard.line("document without framing"))
        guard.feed("x".repeat(SseStreamGuard.PROBE_BYTES))
        assertEquals("protocol_probe",guard.line("document without framing"))
    }

    @Test fun oneDataLineIsEnoughToProveFraming(){
        val guard=SseStreamGuard()
        guard.line("data: {\"choices\":[]}");guard.feed("x".repeat(SseStreamGuard.PROBE_BYTES*2))
        assertNull(guard.line("odd but framed"))
    }

    @Test fun aLineThatNeverEndsIsCutByTheLineCap(){
        val guard=SseStreamGuard()
        val chunk=ByteArray(SseStreamGuard.LINE_LIMIT_BYTES){'d'.code.toByte()}
        assertNull(guard.read(chunk,0,chunk.size))
        assertEquals("line_limit",guard.read(chunk,0,1))
        assertEquals(SseStreamGuard.LINE_LIMIT_BYTES+1,guard.snapshot()["line_bytes"])
    }

    @Test fun aNewlineResetsTheLineBudget(){
        val guard=SseStreamGuard()
        val chunk=ByteArray(SseStreamGuard.LINE_LIMIT_BYTES){'\n'.code.toByte()}
        assertNull(guard.read(chunk,0,chunk.size))
    }

    @Test fun carriageReturnOnlyStreamsAlsoResetTheLineBudget(){
        val guard=SseStreamGuard()
        val head="data: {}\r".toByteArray(Charsets.UTF_8);assertNull(guard.read(head,0,head.size))
        val crs=ByteArray(SseStreamGuard.LINE_LIMIT_BYTES*2){'\r'.code.toByte()}
        assertNull("回车换行也是换行，不能算一条 2 MiB 的长行",guard.read(crs,0,crs.size))
        assertEquals(0,guard.snapshot()["line_bytes"])
    }

    @Test fun leadingWhitespaceAcrossManyReadsStillFindsTheRealOpening(){
        val guard=SseStreamGuard()
        val spaces=ByteArray(10_000){' '.code.toByte()}
        assertNull(guard.read(spaces,0,spaces.size))
        assertEquals("protocol_probe",guard.feed("<html>"))
    }

    @Test fun aByteOrderMarkSplitAcrossReadsIsStillAccepted(){
        val guard=SseStreamGuard()
        for(byte in byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())){
            val chunk=byteArrayOf(byte);assertNull(guard.read(chunk,0,1))
        }
        assertNull(guard.feed("data: {}\n"))
    }

    @Test fun progressFiresOncePerBudgetSlice(){
        val guard=SseStreamGuard()
        assertFalse(guard.progressDue())
        guard.feed("d".repeat(SseStreamGuard.PROGRESS_BYTES));assertTrue(guard.progressDue())
        guard.progressed();assertFalse(guard.progressDue())
        guard.feed("d".repeat(SseStreamGuard.PROGRESS_BYTES));assertTrue(guard.progressDue())
    }

    @Test fun snapshotCarriesCountersUsedInTheFailureRow(){
        val guard=SseStreamGuard(maxWireBytes=1024)
        guard.feed("data: {}");guard.line("data: {}");guard.line("");guard.parsed(3,5)
        val snapshot=guard.snapshot()
        assertEquals(8L,snapshot["bytes"]);assertEquals(1024,snapshot["limit"])
        assertEquals(1,snapshot["data_lines"]);assertEquals(1,snapshot["blank_lines"])
        assertEquals(3,snapshot["content_chars"]);assertEquals(5,snapshot["reasoning_chars"])
        assertTrue(snapshot.containsKey("sample"));assertTrue(snapshot.containsKey("longest_line"))
        assertTrue(snapshot.containsKey("field_lines"));assertTrue(snapshot.containsKey("line_bytes"))
    }

    @Test fun limitsAndProtocolBreaksAreDifferentFailures(){
        for(check in listOf("wire_limit","line_limit","data_limit","content_limit","reasoning_limit","tool_limit"))
            assertEquals(check,ChatFailure.STREAM_LIMIT,streamCheckFailure(check))
        for(check in listOf("protocol_probe","bad_json","no_choices","choice_shape","choice_index","after_done",
            "after_finish","finish_value","tool_shape","tool_calls_missing","tool_calls_shape"))
            assertEquals(check,ChatFailure.STREAM_PROTOCOL,streamCheckFailure(check))
        assertEquals(ChatFailure.TOOLS_UNAVAILABLE,streamCheckFailure("tools_unavailable"))
        assertEquals(ChatFailure.INTERRUPTED,streamCheckFailure("interrupted"))
        assertEquals(ChatFailure.REJECTED,streamCheckFailure("service_error"))
        assertEquals(ChatFailure.FORMAT,streamCheckFailure("secret_leak"))
        for(check in listOf("incomplete","done_before_finish","finish_length"))
            assertEquals(check,ChatFailure.TRUNCATED,streamCheckFailure(check))
    }

    @Test fun memoryKeepsTheRealCauseReachable(){
        assertEquals(ChatFailure.MEMORY_STREAM_LIMIT,memoryFailureFor("MODEL_STREAM_LIMIT"))
        assertEquals(ChatFailure.MEMORY_STREAM_PROTOCOL,memoryFailureFor("MODEL_STREAM_PROTOCOL"))
        assertTrue(ChatFailure.MEMORY_STREAM_LIMIT.display.contains("太长"))
        assertTrue(ChatFailure.MEMORY_STREAM_PROTOCOL.display.contains("事件流"))
    }

    @Test fun unreadableStreamIsNotAutoRetriedButCanBeRetriedByHand(){
        assertFalse(MemoryRepairFeedback.retry(1,"MODEL_STREAM_LIMIT"))
        assertFalse(MemoryRepairFeedback.retry(1,"MODEL_STREAM_PROTOCOL"))
        assertTrue(MemoryRepairFeedback.reason("MODEL_STREAM_LIMIT").contains("读取上限"))
        assertTrue(MemoryRepairFeedback.reason("MODEL_STREAM_PROTOCOL").contains("事件流"))
        assertTrue(processingMessage("MODEL_STREAM_LIMIT").contains("原话"))
        assertTrue(processingMessage("MODEL_STREAM_PROTOCOL").contains("原话"))
    }
}
