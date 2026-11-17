package app.lianxu.independent

import org.json.JSONArray

internal object ToolboxPreferences {
    const val ORDER_KEY="toolbox-order-v1"
    const val VIDEO_KEY="video-understanding-mode-v1"
    val defaultOrder=listOf("image","video","memory","contact")
    enum class VideoMode { ORIGINAL, FRAMES }
    fun order(raw:String):List<String> {
        val array=JSONArray(raw)
        val values=(0 until array.length()).map{array.getString(it)}
        require(values.size==defaultOrder.size&&values.toSet()==defaultOrder.toSet())
        return values
    }
    fun encodeOrder(values:List<String>):String=JSONArray(values).toString().also{order(it)}
    suspend fun loadOrder(store:Store)=order(store.preference(ORDER_KEY,encodeOrder(defaultOrder)))
    suspend fun saveOrder(store:Store,values:List<String>)=store.setPreference(ORDER_KEY,encodeOrder(values))
    suspend fun videoMode(store:Store)=VideoMode.valueOf(store.preference(VIDEO_KEY,VideoMode.ORIGINAL.name))
}
