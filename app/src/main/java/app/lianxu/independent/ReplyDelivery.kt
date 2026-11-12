package app.lianxu.independent

import kotlinx.coroutines.CompletableDeferred

/** The transcript contains only bubbles acknowledged by the page, never a generated tail. */
internal class ReplyDelivery {
    private val earlier = mutableListOf<String>()
    private var current = emptyList<String>()
    private var shown = 0
    private var finished = false
    private var stopped = false
    private var restored = emptyList<String>()
    val completed = CompletableDeferred<Unit>()
    val bubbles get() = earlier + current
    val visible get() = earlier.size + shown
    val spoken get() = (earlier + current.take(shown)).joinToString("\n\n")

    fun restore(text:String) {
        check(earlier.isEmpty()&&current.isEmpty())
        restored = ChatBubbleSegments.split(text)
        earlier += ChatBubbleSegments.capped(restored)
    }

    fun update(text: String, final: Boolean = false) {
        if (stopped) return
        val raw = ChatBubbleSegments.split(text)
        var repeated=0
        while(repeated<minOf(raw.size,restored.size)&&raw[repeated]==restored[repeated])repeated++
        val parts = ChatBubbleSegments.capped(raw.drop(repeated))
        // A tool can start another response. What was already shown still belongs to the chat.
        if (current.take(shown) != parts.take(shown)) nextResponse()
        current = if (final) parts else parts.take((ChatBubbleSegments.closedWhileStreaming(text)-repeated).coerceAtLeast(0))
        finished = final
        settle()
    }
    fun nextResponse() {
        if (stopped) return
        earlier += current.take(shown)
        current = emptyList(); shown = 0; finished = false
    }
    fun acknowledge(count: Int) {
        if (stopped) return
        shown = maxOf(shown, (count - earlier.size).coerceIn(0, current.size))
        settle()
    }
    fun stop(): String { stopped = true; return spoken }
    private fun settle() { if (finished && shown == current.size) completed.complete(Unit) }
}
