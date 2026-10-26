package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** Adapter of our FusedMemoryReferences (16047cbc/e5f84f44). Call-local S/M/N identities. */
internal class MemoryNumberedReferences(private val targets:List<SourceDialogue>,existingIds:Collection<String>) {
    private val sources=targets.flatMap{listOf(it to "user",it to "assistant")}
    private val memories=existingIds.sorted()
    fun sourceRows()=JSONArray().apply{sources.forEachIndexed{i,(s,side)->
        val m=if(side=="user")s.user else s.assistant
        put(JSONObject().put("ref","S${i+1}").put("side",side).put("content",m.text).put("at",m.created).put("zone",s.zone))
    }}
    fun memoryRef(id:String)="M${memories.indexOf(id)+1}"
    fun normalize(operation:JSONObject):JSONObject {
        val op=JSONObject(operation.toString())
        val target=op.optString("target")
        if(target.matches(Regex("M[1-9][0-9]*")))op.put("target",memories.getOrNull(target.drop(1).toInt()-1)?:throw ProcessingRejected("UNKNOWN_TARGET"))
        if(target.matches(Regex("N[1-9][0-9]*")))op.put("target","new:$target")
        val evidence=op.optJSONArray("evidence")?:return op
        val normalized=JSONArray()
        for(i in 0 until evidence.length()) {
            val item=evidence.get(i)
            if(item !is String){normalized.put(item);continue}
            rejectUnless(item.matches(Regex("S[1-9][0-9]*")),"EVIDENCE")
            val(s,side)=sources.getOrNull(item.drop(1).toInt()-1)?:throw ProcessingRejected("EVIDENCE")
            normalized.put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side",side).put("quote",if(side=="user")s.user.text else s.assistant.text))
        }
        return op.put("evidence",normalized)
    }
}
