package app.lianxu.independent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrderedChatScreenDeviceTest {
    @get:Rule val fixture = MockChatFixture()

    @Test fun reopenedConversationShowsFallbackInPlaceAndPlaysLaterSpeech() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val parts = listOf(ReplyPart("第一段", false), ReplyPart("失败段原文", true),
            ReplyPart("第三段", false), ReplyPart("最后一段", true))
        val body = OrderedChatReply.body(parts)
        val store = Store(app, fixture.database)
        val audio = try {
            runBlocking {
                val ledger = ChatLedger(store)
                val turn = ledger.begin("seed-lin", "设备样例", "local")
                assertTrue(ledger.finishWithMemory(turn.id, turn.attempt, body, onCompleted = { db ->
                    OrderedChatReply.save(db, turn.id, OrderedChatReply.encode(parts))
                }))
                ChatVoiceDelivery.file(app, OrderedChatReply.audioKey(turn, body, 3))
            }
        } finally { store.close() }
        audio.parentFile!!.mkdirs()
        val wav = testVoiceWav(app.cacheDir, "ordered-device")
        try {
            wav.copyTo(audio, overwrite = true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun openChat() {
                    eventually {
                        var ready = false
                        scenario.onActivity {
                            val vm = ViewModelProvider(it)[AppState::class.java]
                            if (vm.ready) { vm.open("chat", "seed-lin"); ready = true }
                        }
                        ready
                    }
                }
                fun nodes(): List<AccessibilityNodeInfo> {
                    val found = mutableListOf<AccessibilityNodeInfo>()
                    fun visit(node: AccessibilityNodeInfo) {
                        found.add(node)
                        for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
                    }
                    inst.uiAutomation.rootInActiveWindow?.let(::visit)
                    return found
                }
                fun isVoice(node: AccessibilityNodeInfo) = node.contentDescription?.startsWith("语音，") == true
                fun verifyOrder() {
                    eventually {
                        val screen = nodes()
                        val visible = parts.take(3).map { part -> screen.firstOrNull { it.text?.toString() == part.text } } +
                            listOf(screen.firstOrNull(::isVoice))
                        visible.all { it != null } && visible.filterNotNull().map { node ->
                            Rect().also(node::getBoundsInScreen).centerY()
                        }.zipWithNext().all { (a, b) -> a < b }
                    }
                }
                openChat()
                verifyOrder()
                scenario.recreate()
                openChat()
                verifyOrder()
                val button = Rect().also(nodes().first(::isVoice)::getBoundsInScreen)
                inst.uiAutomation.executeShellCommand("input tap ${button.centerX()} ${button.centerY()}").use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
                eventually { nodes().any { it.stateDescription?.toString() == "正在播放" ||
                    it.contentDescription?.startsWith("语音，剩余") == true } }
            }
        } finally { wav.delete(); audio.delete() }
    }

    private fun eventually(check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            Thread.sleep(120)
        }
        assertTrue("Expected conversation state did not appear", check())
    }
}
