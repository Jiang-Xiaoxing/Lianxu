package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ToolRetryExhaustedTest {
    private val names=listOf(CompanionRecord.NAME,JointControl.NAME)
    @Test fun firstTwoFailuresAreIdentical(){
        for(name in names)for(attempt in 1..2){
            val base=mapOf<String,Any>("success" to false,"error" to "invalid_arguments","detail" to "原说明")
            val result=ToolArgumentDiagnosis.retryExhausted(name,attempt,base)
            assertSame(base,result)
            assertEquals(Gson().toJson(base),Gson().toJson(result))
        }
    }
    @Test fun thirdFailureOnlyAppendsDetail(){
        for(name in names){
            val base=mapOf<String,Any>("success" to false,"error" to "invalid_arguments","detail" to "原说明","hint" to "原提示","problems" to listOf("原问题"))
            val result=ToolArgumentDiagnosis.retryExhausted(name,3,base)
            assertEquals(base-"detail",result-"detail")
            val notice=if(name==CompanionRecord.NAME)
                "本轮 companion_record 已失败 3 次，下一步不再提供这个工具；不要再调用它，直接按人设自然回话。"
            else "本轮 companion_memory_control 已失败 3 次，下一步不再提供这个工具；不要再调用它，按真实结果自然说明，不要声称已执行。"
            assertEquals("原说明\n$notice",result["detail"])
            assertEquals("原说明",base["detail"])
        }
    }
    @Test fun successIsUnchanged(){
        for(name in names)for(attempt in 1..4){
            val base=mapOf<String,Any>("success" to true,"detail" to "已完成")
            assertSame(base,ToolArgumentDiagnosis.retryExhausted(name,attempt,base))
        }
    }
    @Test fun separateBatchFailureCreatesDetailOnlyAtLimit(){
        val base=mapOf<String,Any>("success" to false,"error" to "control_requires_separate_batch")
        for(attempt in 1..2)assertSame(base,ToolArgumentDiagnosis.retryExhausted(JointControl.NAME,attempt,base))
        val result=ToolArgumentDiagnosis.retryExhausted(JointControl.NAME,3,base)
        assertEquals(base,result-"detail")
        assertTrue(result["detail"].toString().contains("不要声称已执行"))
    }
    @Test fun missingDetailAndLaterAttempts(){
        for(name in names)for(detail in listOf(null,"")){
            val base=mapOf<String,Any>("success" to false)+if(detail==null)emptyMap()else mapOf("detail" to detail)
            for(attempt in 3..4)assertTrue(ToolArgumentDiagnosis.retryExhausted(name,attempt,base)["detail"].toString().startsWith("本轮 $name 已失败 3 次"))
        }
    }
    @Test fun otherToolsAreUnchanged(){
        val base=mapOf<String,Any>("success" to false)
        assertSame(base,ToolArgumentDiagnosis.retryExhausted("agent_memory_update",3,base))
    }
}