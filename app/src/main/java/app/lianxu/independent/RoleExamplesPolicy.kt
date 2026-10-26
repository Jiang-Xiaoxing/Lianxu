package app.lianxu.independent

import com.google.gson.JsonParser

// Adapted from Jiang-XX's CompanionExamplesPolicy at Nekobot-Android 71257279.
// See docs/REUSE-PROVENANCE.md. GPL-3.0-only; database/UI adapters are Lianxu's.
internal data class ExampleCandidate(val id:String,val revision:Int,val pair:DialogueExample,val nextUser:String="")
internal object RoleExamplesPolicy {
    const val INVITATION_ROUNDS=20
    private val correction=Regex("不是|不对|说错|错了|纠正|别这样|不要这样|你误会|我说的是|你理解错|重来|重新回答|not what|that's wrong|incorrect",RegexOption.IGNORE_CASE)
    private val failure=Regex("报错|请求失败|生成失败|网络错误|连接失败|超时|HTTP\\s*[45]\\d\\d|exception|traceback|error[:：]|content_filter",RegexOption.IGNORE_CASE)
    fun eligible(c:ExampleCandidate)=c.pair.user.length in 4..300&&c.pair.assistant.length in 8..800&&!failure.containsMatchIn(c.pair.user+"\n"+c.pair.assistant)&&!correction.containsMatchIn(c.nextUser)
    fun balanced(candidates:List<ExampleCandidate>,limit:Int=15):List<ExampleCandidate>{
        val groups=candidates.groupBy{scene(it.pair.user)}.values.map{it.toMutableList()}
        return buildList{while(size<limit&&groups.any{it.isNotEmpty()})for(group in groups)if(group.isNotEmpty()&&size<limit)add(group.removeAt(0))}
    }
    private fun scene(text:String)=when {
        Regex("难过|伤心|累|失败|没考好|紧张|担心").containsMatchIn(text)->1
        Regex("想你|喜欢|爱你|抱抱|亲|吃醋").containsMatchIn(text)->2
        Regex("明天|约|一起|打算|计划").containsMatchIn(text)->3
        Regex("哈哈|玩笑|逗|傻|调皮").containsMatchIn(text)->4
        else->0
    }
    fun selected(raw:String,candidates:List<ExampleCandidate>):List<ExampleCandidate>{
        val ids=JsonParser.parseString(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).asJsonObject.getAsJsonArray("ids").map{require(it.isJsonPrimitive&&it.asJsonPrimitive.isString);it.asString}
        require(ids.size in 2..3&&ids.distinct().size==ids.size){"示例挑选结果无效"}
        return ids.map{id->requireNotNull(candidates.firstOrNull{it.id==id}){"示例不是候选原话"}}
    }
}
