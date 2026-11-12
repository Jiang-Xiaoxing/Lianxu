package app.lianxu.independent

internal sealed interface ContactPermission {
    data object Allowed:ContactPermission
    data object Denied:ContactPermission
    data class Deferred(val at:Long):ContactPermission
    companion object {
        /** Existing quota retry guard, unrelated to a model's chosen contact time. */
        const val QUOTA_RECHECK_GUARD_MS=15*60_000L
        /** Relationship contact waits for the missing threshold; promised follow-ups only respect the daily cap. */
        fun evaluate(o:CompanionOpportunity,now:Long,dailyMax:Int,dailyCap:Boolean,dayStart:Long,due:()->Long?):ContactPermission {
            if(o.id.startsWith("relationship:")){
                val at=due()?:return Denied
                return if(now<o.notBefore)Allowed else if(dailyCap||now<at)Denied else Allowed
            }
            if(dailyMax<=0)return Denied
            if(now<o.notBefore||!dailyCap)return Allowed
            val next=maxOf(dayStart+RelationshipProjector.DAY,now+QUOTA_RECHECK_GUARD_MS)
            return Deferred(next)
        }
    }
}
