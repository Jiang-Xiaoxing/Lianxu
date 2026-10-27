package app.lianxu.independent

data class Friend(val id: String, val name: String, val bio: String, val prompt: String = "", val tone: Int = 0)
data class Message(val id: Long, val friendId: String, val text: String, val mine: Boolean, val created: Long)

interface FriendGenerator { suspend fun generate(requestId: Long): List<Friend> }
class DemoFriendGenerator : FriendGenerator {
    override suspend fun generate(requestId: Long): List<Friend> {
        kotlinx.coroutines.delay(2400)
        val names = listOf("林间", "知遥", "泊舟", "小满", "听雨", "青禾", "南枝", "时安", "初晴")
        return (0..2).map { i ->
            val n = ((requestId * 3 + i) % names.size).toInt()
            Friend("demo-$n", names[n], listOf("喜欢散步、植物与日常的小发现。", "愿意听你聊音乐，也聊平凡的一天。", "慢慢认识，不急着找到所有答案。")[i], tone = n % 3)
        }
    }
}

/** Request identity gates even providers that ignore cancellation. All transitions run on UI thread. */
class SearchGate {
    var generation = 0L; private set
    var searching = false; private set
    var candidates = emptyList<Friend>(); private set
    var selected = emptySet<String>(); private set
    fun begin(): Long { generation++; searching = true; candidates = emptyList(); selected = emptySet(); return generation }
    fun cancel() { generation++; searching = false; candidates = emptyList(); selected = emptySet() }
    fun complete(id: Long, friends: List<Friend>): Boolean {
        if (!searching || id != generation) return false
        require(friends.size == 3 && friends.map { it.id }.distinct().size == 3) { "每轮必须恰好三个不同角色" }
        candidates = friends; searching = false; return true
    }
    fun toggle(id: String) { if (candidates.any { it.id == id }) selected = if (id in selected) selected - id else selected + id }
    fun selectAll() { selected = candidates.map { it.id }.toSet() }
    fun clearSelection() { selected = emptySet() }
}

data class SourceRef(val characterId: String, val turnId: Long, val version: Long)
interface TokenCounter { fun tokens(text: String): Int }
