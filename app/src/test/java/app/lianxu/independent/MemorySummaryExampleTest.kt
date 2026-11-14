package app.lianxu.independent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemorySummaryExampleTest {
    private fun body(document:Boolean)=MemorySummaryExample.text(document).substringAfter("<memory_format_example>").substringBefore("</memory_format_example>").trim()
    @Test fun jsonDocumentExampleIsCompleteAndUsesTheExistingOperationSchema(){
        val root=JSONObject(body(true));val ops=root.getJSONArray("operations");val op=ops.getJSONObject(0)
        assertTrue(root.getBoolean("complete"));assertEquals(root.getString("batch_id"),root.getString("end_marker"));assertEquals(1,root.getInt("operation_count"))
        assertEquals("example-op-1",op.getString("id"));assertEquals("N1",op.getString("target"));assertEquals("S1",op.getJSONArray("evidence").getString(0))
        assertTrue(MemoryOperationFormat.issues(op,1).isEmpty());assertFalse(op.has("operation"))
    }
    @Test fun ndjsonExampleHasOneOperationAndOneMatchingCompletion(){
        val lines=body(false).lines().map(::JSONObject);assertEquals(2,lines.size)
        val first=lines[0];val last=lines[1];assertEquals("operation",first.getString("type"));assertEquals("complete",last.getString("type"))
        assertEquals(first.getString("batch_id"),last.getString("end_marker"));assertEquals(first.getString("character_id"),last.getString("character_id"));assertEquals(1,last.getInt("operation_count"));assertTrue(last.getBoolean("complete"))
        assertTrue(MemoryOperationFormat.issues(first.getJSONObject("operation"),1).isEmpty())
    }
    @Test fun fictionalValuesAreExplicitlyExcludedAndSavedNumbersRemainReserved(){
        for(document in listOf(true,false)){val text=MemorySummaryExample.text(document);assertTrue(text.contains("不得把示例"));assertTrue(text.contains("未占用"));assertTrue(text.contains("ref字段"));assertTrue(text.contains("实际输出必须替换"));assertFalse(body(document).contains("上述操作字段"))}
    }
}
