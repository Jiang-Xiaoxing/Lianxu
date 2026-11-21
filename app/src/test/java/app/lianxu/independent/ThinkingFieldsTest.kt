package app.lianxu.independent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 思考参数表与合并规则：只验本地请求体，不打开网络，也不调用真实 API。 */
class ThinkingFieldsTest {
    private val deepSeekBase="https://api.deepseek.com/v1"
    private val goBase="https://opencode.ai/inference/go/openai/v1"
    private val customBase="https://my-gateway.example.com/v1"

    private fun config(provider:String,base:String,model:String,mode:ThinkingMode,custom:String="")=
        ModelConfig("t","合成",provider,base,model,thinkingMode=mode.key,thinkingCustom=custom)

    private fun deepSeek(mode:ThinkingMode,model:String="deepseek-flash",base:String=deepSeekBase)=
        config("deepseek",base,model,mode)

    private fun go(mode:ThinkingMode,model:String="glm-5.3",base:String=goBase)=
        config("opencode-go",base,model,mode)

    private fun bodyOf(c:ModelConfig):JSONObject {
        val request=TextChatRequest(listOf(WireMessage("user","hi")),outputLimit=256)
        return ImageWire.body(c,request,false)
    }

    private fun assertNoThinkingFields(body:JSONObject,label:String=""){
        for(key in listOf("thinking","reasoning_effort","enable_thinking","thinking_budget"))
            assertFalse("$label 不应写 $key",body.has(key))
    }

    private fun assertCommonBody(body:JSONObject,model:String){
        assertEquals(model,body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals(256,body.getInt("max_tokens"))
        assertEquals("hi",body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun deepSeekFlashSendsThinkingAndEffortForLowHighMax(){
        for(mode in listOf(ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX)){
            val body=bodyOf(deepSeek(mode))
            assertCommonBody(body,"deepseek-flash")
            val thinking=body.getJSONObject("thinking")
            assertEquals(1,thinking.length())
            assertEquals("enabled",thinking.getString("type"))
            assertEquals(mode.key,body.getString("reasoning_effort"))
            assertFalse(body.has("enable_thinking"))
        }
    }

    @Test fun deepSeekOffOnlyWritesDisabledThinking(){
        val body=bodyOf(deepSeek(ThinkingMode.OFF))
        assertCommonBody(body,"deepseek-flash")
        val thinking=body.getJSONObject("thinking")
        assertEquals(1,thinking.length())
        assertEquals("disabled",thinking.getString("type"))
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("enable_thinking"))
    }

    @Test fun deepSeekDefaultWritesNothing(){
        val body=bodyOf(deepSeek(ThinkingMode.DEFAULT))
        assertCommonBody(body,"deepseek-flash")
        assertNoThinkingFields(body,"deepseek-flash/default")
    }

    @Test fun goModelsSendOnlyReasoningEffortForLowHighMax(){
        for(model in listOf("glm-5.3","deepseek-v4.1-flash")){
            for(mode in listOf(ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX)){
                val body=bodyOf(go(mode,model))
                assertCommonBody(body,model)
                assertEquals(mode.key,body.getString("reasoning_effort"))
                assertFalse("$model/$mode 不应写 thinking",body.has("thinking"))
                assertFalse(body.has("enable_thinking"))
            }
        }
    }

    @Test fun goOffAndDefaultSendNoReasoningFields(){
        for(model in listOf("glm-5.3","deepseek-v4.1-flash")){
            for(mode in listOf(ThinkingMode.OFF,ThinkingMode.DEFAULT)){
                val body=bodyOf(go(mode,model))
                assertCommonBody(body,model)
                assertNoThinkingFields(body,"$model/$mode")
            }
        }
    }

    @Test fun legacyOnMapsToHighOnBothVerifiedRoutes(){
        val direct=bodyOf(deepSeek(ThinkingMode.ON))
        assertEquals("enabled",direct.getJSONObject("thinking").getString("type"))
        assertEquals("high",direct.getString("reasoning_effort"))

        val gateway=bodyOf(go(ThinkingMode.ON))
        assertEquals("high",gateway.getString("reasoning_effort"))
        assertFalse(gateway.has("thinking"))
    }

    @Test fun lowercaseKeysAndEffectiveModeFollowEachVerifiedRoute(){
        assertEquals(ThinkingMode.DEFAULT,ThinkingFields.mode("default"))
        assertEquals(ThinkingMode.OFF,ThinkingFields.mode("off"))
        assertEquals(ThinkingMode.ON,ThinkingFields.mode("on"))
        assertEquals(ThinkingMode.LOW,ThinkingFields.mode("low"))
        assertEquals(ThinkingMode.HIGH,ThinkingFields.mode("high"))
        assertEquals(ThinkingMode.MAX,ThinkingFields.mode("max"))
        assertEquals(ThinkingMode.DEFAULT,ThinkingFields.mode("nonsense"))

        assertEquals(listOf(ThinkingMode.DEFAULT,ThinkingMode.OFF,ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX),ThinkingFields.options(deepSeek(ThinkingMode.DEFAULT)))
        assertEquals(listOf(ThinkingMode.DEFAULT,ThinkingMode.OFF,ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX),ThinkingFields.options(deepSeek(ThinkingMode.DEFAULT,base="https://api.deepseek.com")))
        assertEquals(listOf(ThinkingMode.DEFAULT,ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX),ThinkingFields.options(go(ThinkingMode.DEFAULT)))

        for(mode in listOf(ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX)){
            assertEquals(mode,ThinkingFields.effectiveMode(deepSeek(mode)))
            assertEquals(mode,ThinkingFields.effectiveMode(go(mode)))
        }
        assertEquals(ThinkingMode.OFF,ThinkingFields.effectiveMode(deepSeek(ThinkingMode.OFF)))
        assertEquals(ThinkingMode.DEFAULT,ThinkingFields.effectiveMode(go(ThinkingMode.OFF)))
        assertEquals(ThinkingMode.HIGH,ThinkingFields.effectiveMode(deepSeek(ThinkingMode.ON)))
        assertEquals(ThinkingMode.HIGH,ThinkingFields.effectiveMode(go(ThinkingMode.ON)))
    }

    @Test fun wrongGatewayOrUnverifiedModelOnlyFollowsDefault(){
        val routes=listOf(
            deepSeek(ThinkingMode.LOW,base="https://api.deepseek.com.example/v1"),
            deepSeek(ThinkingMode.LOW,base="https://proxy.example/v1"),
            deepSeek(ThinkingMode.LOW,model="deepseek-chat"),
            go(ThinkingMode.LOW,base="https://opencode.ai/v1"),
            go(ThinkingMode.LOW,model="glm-5.2"),
            config("openai",deepSeekBase,"deepseek-flash",ThinkingMode.LOW)
        )
        for(route in routes){
            assertEquals(listOf(ThinkingMode.DEFAULT),ThinkingFields.options(route))
            for(mode in ThinkingMode.entries){
                val stored=route.copy(thinkingMode=mode.key)
                assertEquals("${route.providerId}/${route.baseUrl}/${route.modelId}/$mode",ThinkingMode.DEFAULT,ThinkingFields.effectiveMode(stored))
                assertNoThinkingFields(bodyOf(stored),"${route.providerId}/${route.baseUrl}/${route.modelId}/$mode")
            }
        }
    }

    // 未确知路由完全不发思考参数：不再按服务商名称或旧字段规则猜测。
    @Test fun unknownRouteNeverSendsAnyThinkingFields(){
        val route=config("zhipu-cn","https://open.bigmodel.cn/api/paas/v4","glm-4.6",ThinkingMode.LOW)
        assertEquals(listOf(ThinkingMode.DEFAULT),ThinkingFields.options(route))
        for(mode in ThinkingMode.entries){
            assertNoThinkingFields(bodyOf(route.copy(thinkingMode=mode.key)),"zhipu-cn/$mode")
        }
    }

    @Test fun customPresetMergesTheWholeSnippet(){
        val body=bodyOf(config("custom",customBase,"my-model",ThinkingMode.DEFAULT,"""{"reasoning_effort":"high","thinking":{"type":"disabled"}}"""))
        assertEquals("high",body.getString("reasoning_effort"))
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
        assertCommonBody(body,"my-model")
    }

    @Test fun customSnippetCannotOverwriteProgramFields(){
        val error=ThinkingFields.customError("""{"model":"hijack","reasoning_effort":"low"}""")
        assertNotNull(error);assertTrue(error!!.contains("model"))
        val body=bodyOf(config("custom",customBase,"my-model",ThinkingMode.DEFAULT,"""{"model":"hijack","max_tokens":1}"""))
        assertEquals("my-model",body.getString("model"))
        assertEquals(256,body.getInt("max_tokens"))
    }

    @Test fun customSnippetRejectsNonObjectsAndBrokenJson(){
        assertNotNull(ThinkingFields.customError("reasoning_effort=high"))
        assertNotNull(ThinkingFields.customError("""{"reasoning_effort":}"""))
        assertNotNull(ThinkingFields.customError("{}"))
        assertNull(ThinkingFields.customError(""))
        assertNull(ThinkingFields.customError("""{"reasoning_effort":"high"}"""))
    }

    @Test fun unknownStoredModeFallsBackToDefault(){
        assertEquals(ThinkingMode.DEFAULT,ThinkingFields.mode("nonsense"))
        assertEquals(ThinkingMode.OFF,ThinkingFields.mode("off"))
    }

    @Test fun savedConfigKeepsThinkingButOldConfigStillLoads(){
        val withThinking=deepSeek(ThinkingMode.OFF)
        assertEquals(withThinking,withThinking.copy())
        assertEquals(ThinkingMode.DEFAULT.key,ModelConfig("t","a","deepseek",deepSeekBase,"deepseek-flash").thinkingMode)
        assertEquals("",ModelConfig("t","a","deepseek",deepSeekBase,"deepseek-flash").thinkingCustom)
    }
}
