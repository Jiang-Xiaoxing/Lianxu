package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SourceDialogue(val requestId:String,val source:SourceRef,val user:Message,val assistant:Message,val zone:String) {
    val tokens get()=ConservativeTokens.tokens(user.text)+ConservativeTokens.tokens(assistant.text)
    fun json()=JSONObject().put("request",requestId).put("character",source.characterId).put("turn",source.turnId).put("version",source.version).put("user_id",user.id).put("assistant_id",assistant.id).put("user",user.text).put("assistant",assistant.text).put("user_at",user.created).put("assistant_at",assistant.created).put("zone",zone)
    companion object {fun from(j:JSONObject):SourceDialogue {
        val friend=j.getString("character");val ref=SourceRef(friend,j.getLong("turn"),j.getLong("version"))
        return SourceDialogue(j.getString("request"),ref,Message(j.getLong("user_id"),friend,j.getString("user"),true,j.getLong("user_at")),Message(j.getLong("assistant_id"),friend,j.getString("assistant"),false,j.getLong("assistant_at")),j.getString("zone"))
    }}
}
// recent/batch/recall token fields remain serialized only for old-policy compatibility. Live counts use MemoryRules.
data class ProcessingPolicy(val recentBudget:Int=8000,val batchBudget:Int=2000,val coreGoal:Int=1000,val recallBudget:Int=2000,val relationshipEnabled:Boolean=false,val relationshipAutomatic:Boolean=false,val relationshipInterval:Int=5,val historyChoice:String="UNCONFIRMED") {
    fun valid()=recentBudget in 1000..200000&&batchBudget in 100..(recentBudget-1)&&coreGoal in 100..10000&&recallBudget in 100..20000&&relationshipInterval in 1..100&&historyChoice in setOf("UNCONFIRMED","BACKFILL","FROM_NOW")
    fun json()=JSONObject().put("recent",recentBudget).put("batch",batchBudget).put("core",coreGoal).put("recall",recallBudget).put("relationshipEnabled",relationshipEnabled).put("relationshipAutomatic",relationshipAutomatic).put("relationshipInterval",relationshipInterval).put("historyChoice",historyChoice)
    companion object {fun from(j:JSONObject)=ProcessingPolicy(j.optInt("recent",8000),j.optInt("batch",2000),j.optInt("core",1000),j.optInt("recall",2000),j.optBoolean("relationshipEnabled"),j.optBoolean("relationshipAutomatic"),j.optInt("relationshipInterval",5),j.optString("historyChoice","UNCONFIRMED"))}
}
enum class MemoryCategory(val label:String,val modern:Boolean=false) {
    STATE_FACT("现状与事实",true),PREFERENCE_BOUNDARY("偏好与边界",true),EXPERIENCE_MEMORY("经历与回忆",true),COMMITMENT_OPEN("约定与未竟事项",true),
    USER_PROFILE("旧版：用户画像"),CHARACTER_PROFILE("旧版：角色画像"),RELATIONSHIP_FACT("旧版：双方关系事实"),EXPERIENCE("旧版：经历"),PROMISE("旧版：约定"),UNRESOLVED("旧版：未解决事项")
}
enum class MemoryAbout(val label:String){ USER("用户"),CHARACTER("角色"),BOTH("双方") }
enum class MemoryLife { ACTIVE,CANCELLED,COMPLETED,RETRACTED }
data class MemoryEvidence(val source:SourceRef,val side:String,val quote:String,val snapshot:String,val sourceAt:Long,val zone:String) {
    fun json()=JSONObject().put("character",source.characterId).put("turn",source.turnId).put("version",source.version).put("side",side).put("quote",quote).put("snapshot",snapshot).put("at",sourceAt).put("zone",zone)
    companion object{fun from(j:JSONObject)=MemoryEvidence(SourceRef(j.getString("character"),j.getLong("turn"),j.getLong("version")),j.getString("side"),j.getString("quote"),j.getString("snapshot"),j.getLong("at"),j.getString("zone"))}
}
data class StoredMemory(val id:String,val friendId:String,val category:MemoryCategory,val text:String,val life:MemoryLife=MemoryLife.ACTIVE,val core:Boolean=false,val locked:Boolean=false,val manual:Boolean=false,val version:Int=1,val valid:Boolean=true,val dateExpression:String?=null,val eventDate:String?=null,val evidence:List<MemoryEvidence> = emptyList(),val about:MemoryAbout?=null,val tags:List<String> = emptyList(),val dueExpression:String?=null,val dueDate:String?=null,val expiresAt:Long?=null,val dateType:String?=null,val annualMonthDay:String?=null) {
    // The current app has one fixed conversation per character; BOTH never widens this scope.
    val conversationId get()=friendId
    fun json()=JSONObject().put("id",id).put("character",friendId).put("category",category.name).put("text",text).put("life",life.name).put("core",core).put("locked",locked).put("manual",manual).put("version",version).put("valid",valid).put("dateExpression",dateExpression?:JSONObject.NULL).put("eventDate",eventDate?:JSONObject.NULL).put("dateType",dateType?:JSONObject.NULL).put("annualMonthDay",annualMonthDay?:JSONObject.NULL).put("expiresAt",expiresAt?:JSONObject.NULL).put("evidence",JSONArray().apply{evidence.forEach{put(it.json())}}).apply{
        if(category.modern){put("schema",2);put("conversation",conversationId);put("about",about?.name?:JSONObject.NULL);put("tags",JSONArray(tags));put("dueExpression",dueExpression?:JSONObject.NULL);put("dueDate",dueDate?:JSONObject.NULL)}
    }
    companion object{fun from(j:JSONObject):StoredMemory {
        val a=j.optJSONArray("evidence")?:JSONArray()
        val category=MemoryCategory.valueOf(j.getString("category"));val tags=j.optJSONArray("tags")?:JSONArray()
        if(category.modern)require(j.optInt("schema",2)==2&&j.optString("conversation",j.getString("character"))==j.getString("character"))
        return StoredMemory(j.getString("id"),j.getString("character"),category,j.getString("text"),MemoryLife.valueOf(j.optString("life","ACTIVE")),j.optBoolean("core"),j.optBoolean("locked"),j.optBoolean("manual"),j.getInt("version"),j.optBoolean("valid",true),if(j.isNull("dateExpression"))null else j.getString("dateExpression"),if(j.isNull("eventDate"))null else j.getString("eventDate"),(0 until a.length()).map{MemoryEvidence.from(a.getJSONObject(it))},if(category.modern)MemoryAbout.valueOf(j.getString("about")) else null,if(category.modern)(0 until tags.length()).map{tags.getString(it)} else emptyList(),if(category.modern&&!j.isNull("dueExpression"))j.getString("dueExpression") else null,if(category.modern&&!j.isNull("dueDate"))j.getString("dueDate") else null,if(j.has("expiresAt")&&!j.isNull("expiresAt"))j.getLong("expiresAt") else null,if(j.isNull("dateType"))null else j.getString("dateType"),if(j.isNull("annualMonthDay"))null else MemoryDates.monthDay(j.getString("annualMonthDay")))
    }}
}
internal fun stableDigest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
/** Program-resolved single days only; ambiguous expressions never fall back to invented model dates. */
object MemoryDates {
    fun monthDay(value:String):String {
        require(value.matches(Regex("[0-9]{2}-[0-9]{2}")))
        java.time.MonthDay.parse("--"+value)
        return value
    }
    /** Birthday is opt-in metadata. A missing birth year never uses the source year. */
    fun birthday(expression:String?):Pair<String?,String?> {
        if(expression.isNullOrBlank())return null to null
        val full=Regex("(?<![0-9])([0-9]{4})[年/-]([0-9]{1,2})[月/-]([0-9]{1,2})(?:日|号)?(?![0-9])").find(expression)
        if(full!=null){val (y,m,d)=full.destructured;val date=runCatching{LocalDate.of(y.toInt(),m.toInt(),d.toInt())}.getOrNull()?:return null to null;return date.toString() to date.toString().takeLast(5)}
        val match=Regex("(?<![0-9])([0-9]{1,2})[月/-]([0-9]{1,2})(?:日|号)?(?![0-9])").find(expression)?:return null to null
        val value="%02d-%02d".format(java.util.Locale.ROOT,match.groupValues[1].toInt(),match.groupValues[2].toInt())
        return null to runCatching{monthDay(value)}.getOrNull()
    }

    fun resolve(expression:String?,sourceAt:Long,zone:String):String? {
        if(expression.isNullOrBlank())return null
        return runCatching {
            val day=Instant.ofEpochMilli(sourceAt).atZone(ZoneId.of(zone)).toLocalDate()
            (app.lianxu.independent.time.TimeWordEngine.resolve(expression,day) as? app.lianxu.independent.time.TimeWordEngine.Result.Day)?.date?.toString()
        }.getOrNull()
    }
    // Reused from our CompanionPromptLanguage.memory; dates remain separate from the saved body.
    fun display(content:String,eventDate:String?,today:LocalDate=LocalDate.now()):String {
        val date=eventDate?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
        val prefix=date?.let { when(it) {
            today->"今天";today.minusDays(1)->"昨天";today.minusDays(2)->"前天"
            else->(if(it.year==today.year)"" else "${it.year}年")+"${it.monthValue}月${it.dayOfMonth}日"
        }+"：" }.orEmpty()
        return prefix+content.replace(Regex("[\\r\\n]+")," ").trim()
    }
}