package app.lianxu.independent

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真实服务商对话验收（只在设备上、且外部目录存在 sf-key.txt 时运行）：
 * 录屏里出现的思考文字、工具调用参数、工具返回后的续写和正文全部来自真实模型；
 * 不用合成思考文案、不伪造工具调用。布局按修正版：独立思考卡 + 卡外胶囊 + 接续思考卡。
 */
class RealProviderChatReceiptRecordingTest {
    @get:Rule(order = 0) val fixture = RealProviderChatFixture()
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Snapshot(val turn: ChatTurn, val reasoning: String, val reply: String, val calls: Int, val requests: Int)

    private fun snapshot(): Snapshot = runBlocking {
        val store = Store(context, fixture.database)
        try {
            val turn = ChatLedger(store).all().single()
            val id = requireNotNull(turn.assistantMessageId)
            val text = store.readableDatabase.rawQuery("SELECT display_reasoning FROM message_reasoning WHERE message_id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
            val reply = store.readableDatabase.rawQuery("SELECT body FROM messages WHERE id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
            Snapshot(turn, text, reply, fixture.realToolCalls.get(), fixture.modelRequests.get())
        } finally { store.close() }
    }
    /** 事务提交与回执结算分两步；等结算真正落库后再断言最终状态，避免把瞬时的 staged 当最终结果。 */
    private fun settled(): Boolean = runBlocking {
        val store = Store(context, fixture.database)
        try {
            val turn = ChatLedger(store).all().singleOrNull() ?: return@runBlocking false
            turn.state == TurnState.COMPLETED &&
                turn.toolActivities.filter { it.tool == "agent_memory_update" }.all { it.state != "staged" && it.state != "running" }
        } finally { store.close() }
    }
    private fun waitFor(tag: String, timeout: Long = 30000) {
        ui.waitUntil(timeout) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun hideIme() {
        ui.activityRule.scenario.onActivity { a ->
            (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(a.window.decorView.windowToken, 0)
            a.window.decorView.clearFocus()
        }
        ui.waitForIdle()
    }
    private fun shot(name: String) {
        hideIme(); android.os.SystemClock.sleep(400)
        val dir = File(context.getExternalFilesDir(null), "evidence-real-receipt").apply { mkdirs() }
        val b = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }
    private fun openChat() {
        ui.waitUntil(30000) { ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("tab-0").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithText("林间").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("林间").performClick()
        waitFor("message-input")
    }
    private fun card(id: Long, index: Int) = ui.onNodeWithTag("thinking-card-message-$id-$index").getUnclippedBoundsInRoot()

    private fun assertLayout(s: Snapshot) {
        val receipts = s.turn.toolActivities.filter { it.tool == "agent_memory_update" }
        assertTrue("真实对话必须至少发生一次 agent_memory_update 调用", receipts.isNotEmpty())
        assertEquals("界面回执数必须等于真实模型返回的工具调用数，不能画多或画少", s.calls, receipts.size)
        assertEquals("真实调用参数数量必须与调用数一致", s.calls, fixture.realToolArguments.size)
        assertTrue("真实模型工具调用必须带参数", fixture.realToolArguments.all { it.contains("\"op\"") && it.contains("\"content\"") })
        assertEquals("真实调用 ID 不能重复", s.calls, fixture.realToolCallIds.distinct().size)
        assertTrue("每条回执都要有真实思考位置标记", receipts.all { it.reasoningKnown })
        assertTrue("真实提交后回执应为 success，实际 ${receipts.map { it.state }}", receipts.all { it.state == "success" })
        assertTrue("提交成功后不能留下暂存/未保存文案", receipts.all { it.detail.isBlank() })
        assertTrue("真实模型必须返回过非空思考原文", s.reasoning.isNotBlank())
        assertEquals("回执 ID 不能重复", receipts.size, receipts.distinctBy { it.id }.size)
        assertTrue("回执位置必须在思考文本范围内", receipts.all { it.reasoningAt in 0..s.reasoning.length })
        Log.e("REALRECEIPT", "turn=${s.turn.id} attempt=${s.turn.attempt} reasoning_chars=${s.reasoning.length} real_calls=${s.calls} model_requests=${s.requests} receipts=${receipts.size} offsets=${receipts.map { it.reasoningAt }} reply=${s.reply}")
        val id = requireNotNull(s.turn.assistantMessageId)
        val capsules = ui.onAllNodesWithText("嗯…我记住了").fetchSemanticsNodes()
        assertTrue("每颗记忆回执都必须显示在界面里", receipts.all { ui.onAllNodesWithTag("tool-result-${it.id}").fetchSemanticsNodes().isNotEmpty() })
        assertEquals("本次验收只应有记忆写入回执", receipts.size, s.turn.toolActivities.size)
        assertEquals("界面胶囊数量必须等于真实写入数", receipts.size, capsules.size)
        assertEquals("不能显示暂存文案", 0, ui.onAllNodesWithText("候选已暂存，尚未正式保存").fetchSemanticsNodes().size)
        assertEquals("不能显示未保存文案", 0, ui.onAllNodesWithText("回话没完成，这条没存下").fetchSemanticsNodes().size)
        // 修正版：按真实断点逐项核对“思考卡 → 卡外胶囊 → 接续思考卡 → 正文”，
        // 允许开头或结尾没有文字卡，也覆盖三张以上卡和多颗同位置胶囊。
        val parts = requireNotNull(chatThinkingParts(s.reasoning, receipts.map { ChatThinkingBreak(it.reasoningAt, it.id) }, receipts))
        val sequence = mutableListOf<Triple<String, androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>>()
        var textIndex = 0
        var toolIndex = 0
        parts.forEach { part ->
            when (part) {
                is ChatThinkingPart.Text -> if (part.text.isNotBlank()) {
                    val bounds = card(id, textIndex)
                    sequence += Triple("思考卡${textIndex + 1}", bounds.top, bounds.bottom)
                    textIndex++
                }
                is ChatThinkingPart.Tool -> {
                    val bounds = ui.onNodeWithTag("tool-result-${part.item.id}").getUnclippedBoundsInRoot()
                    sequence += Triple("胶囊${toolIndex + 1}", bounds.top, bounds.bottom)
                    toolIndex++
                }
            }
        }
        assertTrue("至少应有一张思考卡或一颗胶囊", sequence.isNotEmpty())
        for (i in 1 until sequence.size) {
            assertTrue("${sequence[i - 1].first}必须在${sequence[i].first}上方：${sequence[i - 1].third} / ${sequence[i].second}",
                sequence[i - 1].third <= sequence[i].second)
        }
        val body = ui.onNodeWithTag("message-$id").getUnclippedBoundsInRoot()
        assertTrue("正文必须在最后一项下面：${sequence.last().third} / ${body.top}", body.top >= sequence.last().third)
    }

    private fun receiptShape(turn: ChatTurn) = turn.toolActivities.filter { it.tool == "agent_memory_update" }
        .map { listOf(it.id, it.state, it.reasoningAt, it.reasoningKnown, it.detail) }

    private fun memoryRows(): List<StoredMemory> = runBlocking {
        val store = Store(context, fixture.database)
        try { ProcessingRepository(store).memories("seed-lin") } finally { store.close() }
    }
    private fun memoryReceipts(s: Snapshot) = s.turn.toolActivities.filter { it.tool == "agent_memory_update" }

    /** 点开每颗已保存的胶囊：出现库里那条的实际内容；收起后消失；另一颗不受影响。 */
    private fun assertPillsReadTheirOwnRows(s: Snapshot) {
        val receipts = memoryReceipts(s)
        val rows = memoryRows().associateBy { it.id }
        assertTrue("这一轮必须至少两次真实记忆调用，实际 ${receipts.size}", receipts.size >= 2)
        receipts.forEach { r ->
            assertNotNull("提交成功必须指向真实记录", r.memoryId)
            assertTrue("关联的记录必须还在库里", rows.containsKey(r.memoryId))
        }
        receipts.forEachIndexed { index, r ->
            val row = rows.getValue(requireNotNull(r.memoryId))
            ui.onNodeWithTag("tool-receipt-toggle-${r.id}").performClick()
            ui.waitUntil(20000) { ui.onAllNodesWithTag("memory-peek-${r.id}").fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(20000) { ui.onAllNodesWithText(row.text).fetchSemanticsNodes().isNotEmpty() }
            android.os.SystemClock.sleep(1500)
            assertEquals("一次只能展开一颗", 1, ui.onAllNodesWithText("已保存").fetchSemanticsNodes().size)
            receipts.filter { it.id != r.id }.forEach { other ->
                assertEquals("另一颗必须保持收起", 0, ui.onAllNodesWithTag("memory-peek-${other.id}").fetchSemanticsNodes().size)
            }
            shot("04-peek-${index + 1}")
            ui.onNodeWithTag("tool-receipt-toggle-${r.id}").performClick()
            ui.waitUntil(20000) { ui.onAllNodesWithTag("memory-peek-${r.id}").fetchSemanticsNodes().isEmpty() }
            android.os.SystemClock.sleep(800)
        }
        assertEquals("点开不能改库", rows.size, memoryRows().size)
        assertEquals("点开不能重新请求模型", s.requests, fixture.modelRequests.get())
    }

    /** 去记忆页对照：展开卡显示的就是记忆页里那一条。 */
    private fun assertMemoryPageMatches(s: Snapshot) {
        val rows = memoryRows().associateBy { it.id }
        val texts = memoryReceipts(s).mapNotNull { r -> r.memoryId?.let { rows[it]?.text } }
        assertTrue("至少要有一条可对照的记忆", texts.isNotEmpty())
        ui.onNodeWithTag("back").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithTag("tab-2").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("tab-2").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithTag("settings-processing").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("settings-processing").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithTag("processing-friend-seed-lin").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("processing-friend-seed-lin").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithTag("memory-list").fetchSemanticsNodes().isNotEmpty() }
        texts.forEach { t -> assertTrue("记忆页里应有这条内容：$t", ui.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty()) }
        android.os.SystemClock.sleep(1500)
        shot("05-memory-page")
        android.os.SystemClock.sleep(600)
        ui.onNodeWithTag("back").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithTag("processing-friend-seed-lin").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("back").performClick()
        ui.waitUntil(30000) { ui.onAllNodesWithTag("tab-0").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertStable(before: Snapshot, now: Snapshot) {
        assertEquals("重进/重建不能换一条消息", before.turn.id, now.turn.id)
        assertEquals("重进/重建不能换助手消息", before.turn.assistantMessageId, now.turn.assistantMessageId)
        assertEquals("思考原文必须完全一致", before.reasoning, now.reasoning)
        assertEquals("回执 ID/状态/位置必须完全一致", receiptShape(before.turn), receiptShape(now.turn))
        assertEquals("重进/重建不能重新请求模型", before.requests, now.requests)
        assertEquals("重进/重建不能新增工具调用", before.calls, now.calls)
    }

    @Test fun realModelThinkingReceiptAndReplyStayOrderedThroughReentryAndRecreation() {
        assumeTrue("没有 sf-key.txt，跳过真实服务商验收", fixture.available)
        openChat()
        ui.onNodeWithTag("message-input").performTextInput("记住两件事，直接存，不用先解释：我叫小周；我最喜欢吃意大利面。存好后，算一下 17×23 是多少，并简单说说你怎么算的。")
        ui.onNodeWithTag("send-message").performClick()
        // 让录屏看到模型真实思考/工具回执正在返回。
        android.os.SystemClock.sleep(3000)
        hideIme()
        ui.waitUntil(300000) { settled() }
        ui.waitForIdle()
        val first = snapshot()
        assertLayout(first)
        assertPillsReadTheirOwnRows(first)
        shot("01-completed")
        ui.onNodeWithTag("chat-history").performScrollToNode(hasTestTag("message-${requireNotNull(first.turn.assistantMessageId)}"))
        ui.waitForIdle(); android.os.SystemClock.sleep(600)
        shot("01b-body")
        assertMemoryPageMatches(first)
        openChat()
        android.os.SystemClock.sleep(4000)
        ui.onNodeWithTag("back").performClick()
        openChat()
        val second = snapshot()
        assertStable(first, second)
        assertLayout(second)
        shot("02-reentered"); android.os.SystemClock.sleep(2500)
        ui.activityRule.scenario.recreate()
        val reopened = runCatching { waitFor("message-input", 10000); true }.getOrDefault(false)
        if (!reopened) openChat()
        val third = snapshot()
        assertStable(first, third)
        assertLayout(third)
        shot("03-recreated")
        Log.e("REALRECEIPT", "DONE receipts=${third.turn.toolActivities.count { it.tool == "agent_memory_update" }} calls=${third.calls} reasoning=${third.reasoning.length}")
    }
}

/** 真实服务商夹具：独立测试库；Key 读入后立即从设备目录删除；不写进仓库或日志。sf-key.txt 第一行 Key，第二行模型 ID，第三行可选服务商地址。 */
class RealProviderChatFixture : org.junit.rules.ExternalResource() {
    private val stamp = System.nanoTime().toString()
    val database = "real-chat-$stamp.db"
    private val namespace = "real-chat-$stamp"
    var available = false; private set
    private lateinit var keyFile: File
    private lateinit var previousDatabase: String
    private lateinit var previousNamespace: String
    private lateinit var previousClient: () -> TextChatClient
    val realToolCalls = AtomicInteger()
    val modelRequests = AtomicInteger()
    val realToolArguments = CopyOnWriteArrayList<String>()
    val realToolCallIds = CopyOnWriteArrayList<String>()
    /** 每个真实请求带上的参考资料记忆编号，用来证明确实走到了“已在参考资料里”的去重分支。 */
    val requestMemoryIds = CopyOnWriteArrayList<List<String>>()
    val toolOutputs = CopyOnWriteArrayList<String>()

    private fun observe(request: TextChatRequest) {
        requestMemoryIds += request.messages.flatMap { it.memoryIds }
        request.messages.filter { it.role == "tool" }.forEach { toolOutputs += it.content }
    }

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keyFile = File(context.getExternalFilesDir(null), "sf-key.txt")
        if (!keyFile.isFile) return
        val lines = keyFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val deleted = keyFile.delete()
        check(deleted || !keyFile.exists()) { "临时 Key 文件未能删除" }
        available = true
        val key = lines.first()
        val model = lines.getOrNull(1) ?: "deepseek-ai/DeepSeek-V4.1-Flash"
        // 第三行可选：换成另一个 OpenAI 兼容服务商的地址；不写仍是硅基流动。
        val base = lines.getOrNull(2) ?: "https://api.siliconflow.cn/v1"
        previousDatabase = AppServices.databaseName
        previousNamespace = AppServices.vaultNamespace
        previousClient = AppServices.createTextClient
        AppServices.databaseName = database
        AppServices.vaultNamespace = namespace
        seedLegacyTestRoles(context, database)
        val store = Store(context, database)
        try {
            val code = InstalledBuild.read(context).versionCode
            runBlocking {
                if (LocalReleaseNotes.forVersion(code) != null) LocalUpdateRepository(store).acknowledge(code)
                ProcessingRepository(store).saveMemoryRules(MemoryRules(immediateWrite = true))
            }
            val vault = ModelVault(context, namespace, store)
            vault.save(ModelConfig("real", "真实服务商", "custom", base, model, 64000, 4096), key)
            vault.setPurpose(ModelPurpose.CHAT, "real")
        } finally { store.close() }
        val real = HttpsTextChatClient()
        val wrapper = object : TextChatClient by real {
            override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult {
                modelRequests.incrementAndGet(); observe(request); return record(real.complete(config, key, request))
            }
            override suspend fun stream(config: ModelConfig, key: String, request: TextChatRequest, onChunk: suspend (String) -> Unit): TextChatResult {
                modelRequests.incrementAndGet(); observe(request); return record(real.stream(config, key, request, onChunk))
            }
            override suspend fun streamEvents(config: ModelConfig, key: String, request: TextChatRequest, onEvent: suspend (TextStreamEvent) -> Unit): TextChatResult {
                modelRequests.incrementAndGet(); observe(request); return record(real.streamEvents(config, key, request, onEvent))
            }
            private fun record(result: TextChatResult): TextChatResult {
                if (result is TextChatResult.Success) result.toolCalls.filter { it.name == "agent_memory_update" }.forEach {
                    realToolCalls.incrementAndGet(); realToolCallIds += it.id; realToolArguments += it.arguments
                }
                return result
            }
        }
        AppServices.createTextClient = { wrapper }
        Log.e("REALRECEIPT", "fixture ready model=$model")
    }

    override fun after() {
        if (!available) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppServices.createTextClient = previousClient
        AppServices.databaseName = previousDatabase
        AppServices.vaultNamespace = previousNamespace
        runCatching { ModelVault(context, namespace).list().forEach { ModelVault(context, namespace).delete(it.id) } }
        context.deleteDatabase(database)
        keyFile.delete()
    }
}
