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

// Future memory contract. No extraction is called by the demo application.
data class SourceRef(val characterId: String, val turnId: Long, val version: Long)
data class CompleteTurn(val source: SourceRef, val userText: String, val assistantText: String, val tokens: Int)
data class MemoryBatch(val id: String, val characterId: String, val turns: List<CompleteTurn>)
data class MemoryFact(val id: String, val text: String, val sources: Set<SourceRef>, val exactDate: String? = null, val locked: Boolean = false)
sealed interface BatchStatus {
    data object Pending : BatchStatus
    data class Partial(val savedIds: Set<String>) : BatchStatus
    data object Committed : BatchStatus
    data class Conflict(val reason: String) : BatchStatus
}
interface TokenCounter { fun tokens(text: String): Int }
interface MemoryExtractor { suspend fun extract(batch: MemoryBatch): List<MemoryFact> }
interface MemoryRepository {
    /** Atomic compare-source-versions + idempotent upsert + commit marker. Do not delete original messages. */
    suspend fun persist(batch: MemoryBatch, facts: List<MemoryFact>): BatchStatus
    suspend fun source(ref: SourceRef): CompleteTurn?
    suspend fun history(characterId: String, memoryId: String): List<MemoryFact>
}
object ContextPolicy {
    const val RECENT_TOKEN_BUDGET = 8000 // Original dialogue only; excludes system prompt and memories.
    const val BATCH_TARGET_TOKENS = 2000 // Input turns to extraction, NOT summary length.
    fun plan(characterId: String, recent: List<CompleteTurn>, recentBudget:Int=RECENT_TOKEN_BUDGET,batchBudget:Int=BATCH_TARGET_TOKENS): MemoryBatch? {
        require(recent.all { it.source.characterId == characterId && it.tokens >= 0 })
        require(recent.map { it.source.turnId }.distinct().size == recent.size)
        require(batchBudget>0&&recentBudget>batchBudget)
        if (recent.sumOf { it.tokens } <= recentBudget) return null
        var count = 0
        val prefix = recent.takeWhile { if (count+it.tokens>batchBudget) false else { count += it.tokens; true } }
        check(prefix.isNotEmpty()){ "最早完整轮次超过整理预算，请调整容量；没有移出原话。" }
        return MemoryBatch("$characterId:" + prefix.joinToString(",") { "${it.source.turnId}@${it.source.version}" }, characterId, prefix)
    }
    fun mayRetire(batch: MemoryBatch, status: BatchStatus, currentVersions: Map<SourceRef, Long>): Boolean =
        status == BatchStatus.Committed && batch.turns.all { currentVersions[it.source] == it.source.version }
    fun prompt(custom: String, default: String) = custom.trim().ifEmpty { default }
    fun conflict(existing: MemoryFact, incoming: MemoryFact): Boolean = existing.locked && existing != incoming
}
