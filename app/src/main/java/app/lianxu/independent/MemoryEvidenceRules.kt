package app.lianxu.independent

/** Conservative identity only: never infer that two different sentences mean the same fact. */
internal object MemoryEvidenceRules {
    private fun origin(e:MemoryEvidence)=e.source to e.side
    private fun body(s:String)=s.trim().replace(Regex("\\s+")," ")
    fun sameFact(a:StoredMemory,b:StoredMemory)=a.friendId==b.friendId&&a.category==b.category&&a.about==b.about&&body(a.text)==body(b.text)&&
        a.life==b.life&&a.dateExpression==b.dateExpression&&a.eventDate==b.eventDate&&a.dateType==b.dateType&&a.annualMonthDay==b.annualMonthDay&&
        a.dueExpression==b.dueExpression&&a.dueDate==b.dueDate&&a.expiresAt==b.expiresAt
    fun overlaps(a:List<MemoryEvidence>,b:List<MemoryEvidence>)=a.map(::origin).toSet().intersect(b.map(::origin).toSet()).isNotEmpty()
    fun repeated(old:StoredMemory,proposed:StoredMemory,proof:List<MemoryEvidence>)=proof.isNotEmpty()&&sameFact(old,proposed)&&
        proof.all{origin(it) in old.evidence.map(::origin).toSet()}
    /** One citation per source message. Different quoted spans do not become independent support. */
    fun merge(old:List<MemoryEvidence>,added:List<MemoryEvidence>):List<MemoryEvidence> = (old+added).groupBy(::origin).values.map{group->
        val first=group.first()
        rejectUnless(group.all{it.snapshot==first.snapshot&&it.sourceAt==first.sourceAt&&it.zone==first.zone},"SOURCE_CHANGED")
        if(group.map{it.quote}.distinct().size==1)first else first.copy(quote=first.snapshot)
    }
    fun contextCurrent(captured:Int?,own:Int?,current:Int?)=current!=null&&current==maxOf(captured?:0,own?:0)
}
