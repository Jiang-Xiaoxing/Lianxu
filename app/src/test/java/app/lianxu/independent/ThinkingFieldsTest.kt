package app.lianxu.independent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 思考参数表与合并规则：只验本地逻辑，不打开网络。 */
class ThinkingFieldsTest {
    private fun config(provider:String,mode:ThinkingMode,custom:String="")=
        ModelConfig("t","合成","$provider","https://example.invalid/v1","synthetic",thinkingMode=mode.key,thinkingCustom=custom)

    private fun bodyOf(c:ModelConfig):JSONObject {
        val request=TextChatRequest(listOf(WireMessage("user","hi")),outputLimit=256)
        return ImageWire.body(c,request,false)
    }

    @Test fun eachProviderUsesItsOwnFieldAndValue(){
        assertEquals(JSONObject("""{"type":"disabled"}""").toString(),bodyOf(config("deepseek",ThinkingMode.OFF)).getJSONObject("thinking").toString())
        assertEquals(JSONObject("""{"type":"enabled"}""").toString(),bodyOf(config("deepseek",ThinkingMode.ON)).getJSONObject("thinking").toString())
        assertEquals(false,bodyOf(config("silicon-cn",ThinkingMode.OFF)).getBoolean("enable_thinking"))
        assertEquals(true,bodyOf(config("silicon-cn",ThinkingMode.ON)).getBoolean("enable_thinking"))
        assertEquals(false,bodyOf(config("bailian-cn",ThinkingMode.OFF)).getBoolean("enable_thinking"))
        assertEquals("none",bodyOf(config("opencode-go",ThinkingMode.OFF)).getString("reasoning_effort"))
        assertEquals("high",bodyOf(config("opencode-go",ThinkingMode.ON)).getString("reasoning_effort"))
    }

    @Test fun followingProviderDefaultWritesNothing(){
        for(id in listOf("deepseek","silicon-cn","opencode-go","zhipu-cn","openai","gemini")){
            val body=bodyOf(config(id,ThinkingMode.DEFAULT))
            assertFalse(id,body.has("thinking")||body.has("enable_thinking")||body.has("reasoning_effort"))
        }
    }

    @Test fun providersWithoutAThinkingFieldNeverGainOne(){
        for(id in listOf("openai","openrouter","gemini","moonshot","minimax-cn","ark-cn","qianfan","stepfun-cn","tokenhub-cn","mimo")){
            for(mode in ThinkingMode.entries){
                val body=bodyOf(config(id,mode))
                assertFalse("$id/$mode",body.has("thinking")||body.has("enable_thinking")||body.has("reasoning_effort"))
            }
        }
    }

    // 智谱新模型不允许关思考：传 disabled 会被拒，所以关闭时不写参数。
    @Test fun zhipuNeverSendsDisabledButCanAskForThinking(){
        assertFalse(bodyOf(config("zhipu-cn",ThinkingMode.OFF)).has("thinking"))
        assertEquals(JSONObject("""{"type":"enabled"}""").toString(),bodyOf(config("zhipu-cn",ThinkingMode.ON)).getJSONObject("thinking").toString())
        assertFalse(ThinkingFields.canDisable(ProviderPresets.get("zhipu-cn")))
        assertTrue(ThinkingFields.canEnable(ProviderPresets.get("zhipu-cn")))
    }

    @Test fun customPresetMergesTheWholeSnippet(){
        val body=bodyOf(config("custom",ThinkingMode.DEFAULT,"""{"reasoning_effort":"high","thinking":{"type":"disabled"}}"""))
        assertEquals("high",body.getString("reasoning_effort"))
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
        // 程序自己的字段照旧。
        assertEquals("synthetic",body.getString("model"));assertEquals("hi",body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun customSnippetCannotOverwriteProgramFields(){
        val error=ThinkingFields.customError("""{"model":"hijack","reasoning_effort":"low"}""")
        assertNotNull(error);assertTrue(error!!.contains("model"))
        // 就算绕过校验直接存进去，合并时也不会盖掉程序字段。
        val body=bodyOf(config("custom",ThinkingMode.DEFAULT,"""{"model":"hijack"}"""))
        assertEquals("synthetic",body.getString("model"))
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
        val withThinking=ModelConfig("t","合成","deepseek","https://example.invalid/v1","synthetic",thinkingMode="off",thinkingCustom="")
        assertEquals(withThinking,withThinking.copy())
        // 老配置没有这两个字段时按默认读，行为与升级前一致。
        assertEquals(ThinkingMode.DEFAULT.key,ModelConfig("t","a","b","https://example.invalid/v1","m").thinkingMode)
        assertEquals("",ModelConfig("t","a","b","https://example.invalid/v1","m").thinkingCustom)
    }
}
