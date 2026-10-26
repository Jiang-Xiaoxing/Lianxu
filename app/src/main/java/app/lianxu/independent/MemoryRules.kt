package app.lianxu.independent

import org.json.JSONObject

/** Global user-chosen counts. Context capacity is an independent hard constraint. */
data class MemoryRules(val recentRounds:Int=40,val summaryRounds:Int=20,val relatedCount:Int=15){
    fun valid()=recentRounds>0&&summaryRounds>0&&summaryRounds<=recentRounds&&relatedCount>0
    fun json()=JSONObject().put("recentRounds",recentRounds).put("summaryRounds",summaryRounds).put("relatedCount",relatedCount)
    companion object{
        const val KEY="memory-rules-counts-v1"
        fun from(j:JSONObject)=MemoryRules(j.getInt("recentRounds"),j.getInt("summaryRounds"),j.getInt("relatedCount")).also{require(it.valid())}
        /** Consecutive user messages plus their first nonblank assistant reply form one round. */
        fun completeRounds(messages:List<Message>):Int{var waiting=false;var count=0;messages.forEach{m->if(m.text.isNotBlank()){if(m.mine)waiting=true else if(waiting){count++;waiting=false}}};return count}
    }
}
