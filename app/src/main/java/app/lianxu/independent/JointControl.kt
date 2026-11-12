package app.lianxu.independent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.json.JSONObject

internal object JointControl {
    const val NAME="companion_memory_control"
    val definition=mapOf("type" to "function","function" to mapOf("name" to NAME,"description" to "只执行用户当前直接要求的遗忘、更正旧来源或停止提醒。先回想定位真实用户消息编号；未知不得猜。单独调用，成功回执后按人设自然回复；失败可改正参数重试，不能把未执行说成成功。更正停用旧来源，不能编造新事实。",
        "parameters" to mapOf("type" to "object","properties" to mapOf(
            "action" to mapOf("type" to "string","enum" to listOf("forget","correct","stop_reminding")),
            "targets" to mapOf("type" to "array","items" to mapOf("type" to "integer"),"minItems" to 1),
            "quote" to mapOf("type" to "string","description" to "逐字引用当前直接要求"),
            "authorization" to mapOf("type" to "string","enum" to listOf("current_direct_request"))),
            "required" to listOf("action","targets","quote","authorization"))))
    fun parse(args:JSONObject,input:String,now:Long,allowed:Set<Long>,previous:ExperienceProposal?):ExperienceProposal {
        require(args.getString("authorization")=="current_direct_request")
        val action=args.getString("action");require(action in setOf("forget","correct","stop_reminding"))
        val quote=args.getString("quote")
        val targets=args.getJSONArray("targets")
        val j=JsonObject().apply{
            addProperty("quote",quote);addProperty("memory","");addProperty("emotion","unknown");addProperty("valence",0)
            addProperty("relationship","none");addProperty("control",action);addProperty("controlIntent",action)
            addProperty("requestQuote",quote);addProperty("controlQuote",quote);addProperty("controlAuthorized",true)
            addProperty("claimKind","interaction")
            add("targets",JsonArray().apply{for(i in 0 until targets.length()){
                val n=targets.get(i);require(n is Number);add(n)
            }})
        }
        return CompanionContracts.experience(j.toString(),input,now,allowed+previous?.targets.orEmpty()).copy(controlChecked=true)
    }
    fun receipt(p:ExperienceProposal):Map<String,Any> = mapOf("success" to true,"action" to p.control,"targets" to p.targets,"status" to "applied","note" to "程序已执行；按当前人设自然回应，不引用停用内容。原聊天仍保留供用户查看。")
}
