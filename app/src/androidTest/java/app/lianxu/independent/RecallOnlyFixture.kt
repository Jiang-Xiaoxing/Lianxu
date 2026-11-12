package app.lianxu.independent

/** Isolate related/association recall from automatic core selection. These synthetic records have
 * a readable gist and medium clarity, so they can be recalled but are not permanent core facts.
 * Source/version checks and association traversal still run through the real repositories. */
internal suspend fun seedRecallOnlyMemories(repo:ProcessingRepository,role:String){
    val now=AppServices.companionClock()
    val day=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    repo.tx{db->repo.memories(db,role).forEach{m->
        if(!(m.manual&&m.core)){
            require(m.text.length<=40){"Use a short synthetic gist fixture"}
            // Seed presentation metadata, not a new version of the fact/association endpoint.
            db.execSQL("UPDATE memory_current SET json=? WHERE id=?",arrayOf(m.copy(gist=m.text).json().toString(),m.id))
            MemoryForgetting.put(db,m,MemoryRecallState(now,0,36500.0,40.0,day))
        }
    }}
}
