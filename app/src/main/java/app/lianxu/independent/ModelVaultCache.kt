package app.lianxu.independent

/** Process-wide decrypted model configs and keys; wiped when settings change or the process dies. */
internal object ModelVaultCache {
    data class Snapshot(val directoryPath:String,val configs:List<ModelConfig>,val secrets:Map<String,String>) {
        override fun toString()="Snapshot(directoryPath=$directoryPath, configs=${configs.size}, secrets=redacted)"
    }
    private val snapshots=mutableMapOf<String,Snapshot>()
    private val loads=mutableMapOf<String,Int>()

    fun peek(namespace:String,directoryPath:String):Snapshot?=snapshots[namespace]?.takeIf{it.directoryPath==directoryPath}

    fun getOrLoad(namespace:String,directoryPath:String,onMainThread:Boolean,load:()->Snapshot):Snapshot {
        peek(namespace,directoryPath)?.let{return it}
        check(!onMainThread){"model vault must not decrypt on the main thread"}
        val snapshot=load()
        require(snapshot.directoryPath==directoryPath)
        snapshots[namespace]=snapshot
        loads[namespace]=(loads[namespace]?:0)+1
        return snapshot
    }

    fun invalidate(namespace:String){snapshots.remove(namespace)}

    internal fun loadCount(namespace:String):Int=loads[namespace]?:0
    internal fun resetForTest(){snapshots.clear();loads.clear()}
}
