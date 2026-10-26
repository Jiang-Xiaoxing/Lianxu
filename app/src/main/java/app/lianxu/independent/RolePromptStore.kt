package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

object RolePromptStore {
    fun key(friendId:String)="role-prompts:$friendId"
    fun decode(raw:String):RolePrompts {
        if(raw.isBlank())return RolePrompts()
        val j=JSONObject(raw);val examples=j.optJSONArray("examples")?:JSONArray()
        return RolePrompts(j.optString("expression"),j.optString("extra"),j.optString("internalPrompt"),j.optBoolean("internalEnabled"),j.optBoolean("thinkingNone",true),(0 until examples.length()).mapNotNull{i->examples.optJSONObject(i)?.let{e->val u=e.optString("user");val a=e.optString("assistant");if(u.isBlank()||a.isBlank())null else DialogueExample(u,a)}},j.optBoolean("examplesEnabled"),j.optBoolean("examplesClosed"),j.optString("initialRelationship"),j.optJSONObject("initialScores")?.let{a->RelationshipDimension.entries.mapNotNull{d->a.optJSONObject(d.name)?.let{d to RelationshipValue(it.getInt("score"),it.getString("statement"))}}.toMap()}?:emptyMap())
    }
    fun encode(p:RolePrompts):String {
        val examples=JSONArray();p.examples.forEach{examples.put(JSONObject().put("user",it.user).put("assistant",it.assistant))}
        return JSONObject().put("expression",p.expression).put("extra",p.extra).put("internalPrompt",p.internalPrompt).put("internalEnabled",p.internalEnabled).put("thinkingNone",p.thinkingNone).put("examples",examples).put("examplesEnabled",p.examplesEnabled).put("examplesClosed",p.examplesClosed).put("initialRelationship",p.initialRelationship).put("initialScores",JSONObject().apply{p.initialScores.forEach{(d,v)->put(d.name,JSONObject().put("score",v.score).put("statement",v.statement))}}).toString()
    }
}
