package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase

data class CoreBudgetState(val goal:Int,val used:Int,val protected:Int,val entries:List<StoredMemory>){val overGoal get()=used>goal}
internal fun ProcessingRepository.coreBudget(db:SQLiteDatabase,friendId:String):CoreBudgetState {
    val entries=memories(db,friendId).filter{it.valid&&it.core&&it.life==MemoryLife.ACTIVE&&it.evidence.all{e->sourceValid(db,e.source)}}
    val pending=db.rawQuery("SELECT DISTINCT o.result_id FROM memory_operations o JOIN memory_batches b ON o.batch_id=b.id WHERE b.friend_id=? AND b.state<>'COMMITTED'",arrayOf(friendId)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    fun cost(m:StoredMemory)=ConservativeTokens.tokens(describe(m))+ConservativeTokens.tokens("核心记忆 ${m.id}@${m.version}")+16
    return CoreBudgetState(policy(db,friendId).first.coreGoal,entries.sumOf{cost(it)},entries.filter{it.manual||it.locked||it.id in pending}.sumOf{cost(it)},entries)
}
suspend fun ProcessingRepository.coreBudget(friendId:String)=read{coreBudget(it,friendId)}
/** Downgrade only automatic, unlocked core flags. Text, evidence, lifecycle and history stay intact. */
internal fun ProcessingRepository.maintainCore(db:SQLiteDatabase,friendId:String):CoreBudgetState {
    var status=coreBudget(db,friendId)
    val pending=db.rawQuery("SELECT DISTINCT o.result_id FROM memory_operations o JOIN memory_batches b ON o.batch_id=b.id WHERE b.friend_id=? AND b.state<>'COMMITTED'",arrayOf(friendId)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    val eligible=status.entries.filter{!it.manual&&!it.locked&&it.id !in pending}.sortedBy{m->m.evidence.maxOfOrNull{it.sourceAt}?:0L}
    for(m in eligible){if(!status.overGoal)break;saveMemory(db,m.copy(core=false,version=m.version+1));status=coreBudget(db,friendId)}
    return status
}
suspend fun ProcessingRepository.maintainCore(friendId:String)=tx{maintainCore(it,friendId)}
suspend fun ProcessingRepository.chooseCore(friendId:String,id:String,expectedVersion:Int,value:Boolean)=tx{db->
    val old=memory(db,friendId,id)?:throw ProcessingRejected("SOURCE_CHANGED")
    rejectUnless(old.version==expectedVersion,"SOURCE_CHANGED")
    saveMemory(db,old.copy(core=value,manual=true,version=old.version+1))
}
