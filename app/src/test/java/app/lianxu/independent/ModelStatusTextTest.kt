package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ModelStatusTextTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val config=ModelConfig("c","Chat","deepseek","https://api.deepseek.com","deepseek-chat")
    private val at=1793577600000L // 2026-11-02 08:00 Asia/Shanghai
    @Test fun lastTestShowsTimeModelAndResult(){
        assertEquals("未测试",ModelStatusText.test(null,config,zone))
        assertEquals("上次测试：11-02 08:00 · deepseek-chat · 成功",ModelStatusText.test(ModelTestRecord(at,config.baseUrl,"deepseek-chat",true,""),config,zone))
        assertEquals("上次测试：11-02 08:00 · deepseek-chat · 失败：认证失败（401）",ModelStatusText.test(ModelTestRecord(at,config.baseUrl,"deepseek-chat",false,"认证失败（401）"),config,zone))
    }
    @Test fun changedAddressOrModelMeansUntested(){
        val record=ModelTestRecord(at,config.baseUrl,"deepseek-chat",true,"")
        assertEquals("未测试",ModelStatusText.test(record,config.copy(modelId="deepseek-reasoner"),zone))
        assertEquals("未测试",ModelStatusText.test(record,config.copy(baseUrl="https://example.invalid/v1"),zone))
    }
    @Test fun recentRealCallsCountConsecutiveFailuresPerKind(){
        assertEquals("最近一次真实调用结果：暂无",ModelStatusText.calls(null,zone))
        var r:ModelCallRecord?=null
        repeat(3){r=ModelStatusText.next(r,at,"总结",false,"超时")}
        assertEquals(3,r!!.failures);assertEquals("最近 3 次总结失败：超时",ModelStatusText.calls(r,zone))
        val one=ModelStatusText.next(null,at,"聊天",false,"网络错误")
        assertEquals("最近一次聊天失败：网络错误（11-02 08:00）",ModelStatusText.calls(one,zone))
        val ok=ModelStatusText.next(r,at,"总结",true,"ignored")
        assertEquals(0,ok.failures);assertEquals("最近一次真实调用结果：总结成功（11-02 08:00）",ModelStatusText.calls(ok,zone))
        assertEquals(1,ModelStatusText.next(r,at,"聊天",false,"x").failures)
    }
}
