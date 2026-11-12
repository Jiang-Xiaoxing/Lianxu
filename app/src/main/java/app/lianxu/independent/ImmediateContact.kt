package app.lianxu.independent

import com.google.gson.JsonParser

/** Existing contact fields separated from private mood/relationship accounting. */
internal object ImmediateContact {
    const val NAME="companion_contact"
    fun same(a:RoleFuture,b:RoleFuture)=a.copy(timing=a.timing.copy(expiresAt=0))==b.copy(timing=b.timing.copy(expiresAt=0))
    fun definition(source:Long,now:Long)=mapOf("type" to "function","function" to mapOf("name" to NAME,
        "description" to "有真实兴趣、共同经历或待跟进事项时，设定以后再考虑联系的机会；不是必发承诺。当前来源=$source，当前时间=$now。future={kind:interest_share/shared_continuation/follow_up/actual_result,reason,notBefore:Unix毫秒,evidence:[{source:有效用户消息整数编号,layer:fact/interpretation/action}]}。下次时间由你按实际话题选择，可从现在起安排，不设固定等待下限，也不填写独立有效期；到时再结合最新聊天判断联系、延后或放下，系统可能迟到；不因寂寞或未回复安排。当前来源仅fact；引用旧解释加interpretationRevision，行动加message。没有合适理由不调用。保持当前权限，不能自行打开开关。成功才可说已安排再考虑，同一轮已成功的安排不能重复或替换。\n"+RelationParameterContract.instruction,
        "parameters" to mapOf("type" to "object","properties" to mapOf(
            "future" to mapOf("type" to "object","description" to "沿用上述联系机会协议；不安排时省略"),
            "改主动参数" to mapOf("type" to "object","description" to "用户明确要求的参数建议，仍须用户确认")))))
    data class Request(val flow:RoleUnderstanding?,val parameters:RelationParameterProposal?)
    fun parse(raw:String,input:String,context:RoleContext,now:Long):Request {
        val root=JsonParser.parseString(raw).asJsonObject
        require(root.keySet().all{it in setOf("future","改主动参数")})
        val future=root.get("future")?.takeUnless{it.isJsonNull}
        val flow=if(future==null)null else RoleUnderstandingContract.parse(root.toString(),input,ExperienceProposal(),context,now,false,false,true){_,_->}.also{
            require(it.future!=null){"INVALID_CONTACT"};RoleUnderstandingContract.validateAccepted(it,context,input)
        }
        val parameters=RelationParameterContract.parse(root.get("改主动参数"),context.current,input,now)
        require(!root.has("改主动参数")||parameters!=null){"INVALID_CONTACT_PARAMETERS"}
        require(flow!=null||parameters!=null){"EMPTY_CONTACT"}
        return Request(flow,parameters)
    }
}
