package app.lianxu.independent

enum class RelationshipDimension(val label:String,val initial:Int){
    CLOSENESS("亲近",50),TRUST("信任",50),FAMILIARITY("熟悉",30),DEPENDENCE("依赖",30),SAFETY("安全感",50),JEALOUSY("嫉妒",0)
}
data class RelationshipValue(val score:Int,val statement:String)
data class ProposedRelationshipValue(val score:Int?,val statement:String?)
object RelationshipPolicy {
    /** Adapted from the user's eba14deb clamp; see docs/REUSE-PROVENANCE.md. */
    fun clamp(dimension:RelationshipDimension,previous:Int?,proposed:Int):Int {
        if(previous==null)return proposed.coerceIn(0,100)
        require(previous in 0..100)
        var delta=(proposed.toLong()-previous).coerceIn(
            if(dimension==RelationshipDimension.TRUST)-3L else -8L,
            if(dimension==RelationshipDimension.TRUST)3L else 5L)
        if(previous>=80&&delta>0)delta/=2
        return (previous+delta).toInt().coerceIn(0,100)
    }
    fun update(previous:Map<RelationshipDimension,RelationshipValue>,proposed:Map<RelationshipDimension,ProposedRelationshipValue?>,initial:Map<RelationshipDimension,RelationshipValue> = emptyMap()):Map<RelationshipDimension,RelationshipValue> = RelationshipDimension.entries.associateWith{dimension->
        val old=previous[dimension];val next=proposed[dimension]
        if(next?.score==null)old?:initial[dimension]?.let{it.copy(score=it.score.coerceIn(0,100))}?:RelationshipValue(dimension.initial,"尚未形成明确判断。")
        else {
            require(!next.statement.isNullOrBlank()){"关系自述缺失"}
            RelationshipValue(clamp(dimension,old?.score,next.score),next.statement)
        }
    }
    fun shouldSchedule(enabled:Boolean,automatic:Boolean,historyConfirmed:Boolean,pendingCompleteTurns:Int,interval:Int=5):Boolean {
        require(interval in 1..100)
        return enabled&&automatic&&historyConfirmed&&pendingCompleteTurns>=interval
    }
}
