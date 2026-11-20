package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** 三种思考状态。存进配置的是 key，界面上要让用户一眼看懂各自的意思。 */
internal enum class ThinkingMode(val key:String,val label:String,val hint:String) {
    DEFAULT("default","跟随服务商默认","不写思考参数，用服务商自己的做法"),
    OFF("off","关闭思考","尽量让它直接答，不先想一大段"),
    ON("on","开启思考","先想再答，通常更认真，也更容易慢和贵")
}

/**
 * 思考参数只在一处合并进请求体：这里给出「参数名 → 值」，[ImageWire.body] 负责塞进去。
 * 自定义服务商走整段 JSON：用户填什么就合并什么，但关键字段一律不许碰。
 */
internal object ThinkingFields {
    /** 程序自己维护的字段，自定义片段不能覆盖。 */
    private val protected=setOf("model","messages","stream","tools","tool_choice","max_tokens","max_completion_tokens","response_format")

    fun mode(raw:String)=ThinkingMode.entries.firstOrNull{it.key==raw}?:ThinkingMode.DEFAULT
    fun canDisable(preset:ProviderPreset)=preset.thinkingParam!=null&&preset.thinkingOff!=null
    fun canEnable(preset:ProviderPreset)=preset.thinkingParam!=null&&preset.thinkingOn!=null
    /** 这家能不能选思考设置：表里三列都空、又不是自定义服务，就只能跟随默认。 */
    fun adjustable(preset:ProviderPreset)=preset.id=="custom"||canDisable(preset)||canEnable(preset)

    /** 表里存的是写法片段：对象、数组、true/false、其余当字符串。 */
    fun parse(raw:String):Any {
        val text=raw.trim()
        return when {
            text.startsWith("{")->JSONObject(text)
            text.startsWith("[")->JSONArray(text)
            text=="true"->true
            text=="false"->false
            else->text
        }
    }

    /** 这个配置本次请求要写的思考字段；null 表示一条都不写。 */
    fun field(config:ModelConfig):Pair<String,Any>? {
        val preset=ProviderPresets.get(config.providerId)
        if(preset.id=="custom")return null
        val raw=when(mode(config.thinkingMode)) {
            ThinkingMode.OFF->preset.thinkingOff
            ThinkingMode.ON->preset.thinkingOn
            ThinkingMode.DEFAULT->null
        } ?: return null
        val name=preset.thinkingParam ?: return null
        return name to parse(raw)
    }

    /** 自定义服务商的整段 JSON；没填或写坏了就返回 null，请求照常发。 */
    private fun custom(config:ModelConfig):JSONObject? {
        if(ProviderPresets.get(config.providerId).id!="custom")return null
        if(config.thinkingCustom.isBlank()||customError(config.thinkingCustom)!=null)return null
        return runCatching{JSONObject(config.thinkingCustom.trim())}.getOrNull()
    }

    /** 把思考字段并进请求体：表驱动的写一个键，自定义服务商合并整段。 */
    fun apply(body:JSONObject,config:ModelConfig) {
        custom(config)?.let{extra->
            val keys=extra.keys()
            while(keys.hasNext()){val name=keys.next();if(name !in protected)body.put(name,extra.get(name))}
            return
        }
        field(config)?.let{(name,value)->body.put(name,value)}
    }

    /** 自定义片段的填写错误；null 表示可以存。 */
    fun customError(raw:String):String? {
        if(raw.isBlank())return null
        val text=raw.trim()
        if(!text.startsWith("{"))return "请填一段花括号包起来的设置，例如 {\"reasoning_effort\":\"high\"}"
        val obj=runCatching{JSONObject(text)}.getOrNull() ?: return "这段写法有问题，检查一下括号、引号和逗号。"
        if(obj.length()==0)return "这段是空的，填上内容或者留空都可以。"
        val blocked=obj.keys().asSequence().filter{it in protected}.toList()
        if(blocked.isNotEmpty())return "这里不能写 ${blocked.joinToString("、")}，这些程序自己会填。"
        return null
    }

    /** 界面上那句解释：让用户知道选了以后到底会发生什么。 */
    fun describe(config:ModelConfig):String {
        val preset=ProviderPresets.get(config.providerId)
        if(preset.id=="custom"){
            if(config.thinkingCustom.isBlank())return "不额外写思考参数，用服务商默认。"
            return customError(config.thinkingCustom) ?: "每次请求都会把这几个设置原样带出去。"
        }
        val enabledMode=mode(config.thinkingMode)
        return when {
            enabledMode==ThinkingMode.DEFAULT->"请求里不写思考参数，由服务商自己决定。"
            enabledMode==ThinkingMode.OFF->if(canDisable(preset))"会明确告诉服务商：这次不用思考。" else "这家服务商关不掉思考，选了也不会写关闭参数。"
            else->if(canEnable(preset))"会明确要求服务商认真思考。" else "这家服务商不接开启参数，选了也不会写。"
        }
    }
}
