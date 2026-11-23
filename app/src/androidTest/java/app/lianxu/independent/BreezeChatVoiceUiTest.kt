package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class BreezeChatVoiceUiTest {
    @get:Rule val ui=createComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private fun bytes()=inst.context.assets.open("voice/modulated-tone.mp3").use{it.readBytes()}
    private val bubble get() = ui.onNodeWithTag("chat-voice-bubble")
    private val original = "这是本地测试音。"

    private fun show(file: File, manage: (() -> Unit)? = null) {
        ui.setContent {
            LianxuTheme {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    ChatVoiceBubble(file, original, rememberChatVoicePlayback(), manage)
                }
            }
        }
    }

    private fun awaitState(description: String, timeoutMs: Long = 10000) {
        ui.waitUntil(timeoutMs) { ui.onAllNodes(hasStateDescription(description)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun displayedSeconds(node: SemanticsNodeInteraction): Int =
        node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text.removeSuffix("″").toInt()

    @Test fun localPlaybackPauseReplayAndInlineTranscript() {
        val file = testVoiceWav(app.cacheDir, "local-tone")
        var actions = 0
        show(file) { actions++ }
        awaitState("待播放")
        bubble.assertContentDescriptionEquals("语音，12秒")
        val bounds = bubble.getUnclippedBoundsInRoot()
        bubble.performTouchInput { longClick() }
        bubble.assert(hasStateDescription("待播放"))
        val body = ui.onNodeWithText(original)
        assertTrue(body.getUnclippedBoundsInRoot().top >= bounds.bottom)
        assertEquals(bounds, bubble.getUnclippedBoundsInRoot())
        body.performTouchInput { longClick() }
        ui.runOnIdle { assertEquals(1, actions) }
        body.performClick()
        body.assertDoesNotExist()

        bubble.performClick()
        awaitState("正在播放")
        for (expected in listOf(11, 10)) {
            ui.waitUntil(6000) { displayedSeconds(bubble) == expected }
        }
        bubble.performClick()
        bubble.assert(hasStateDescription("已暂停"))
        val paused = displayedSeconds(bubble)
        assertTrue(paused in 1..11)
        bubble.assertContentDescriptionEquals("语音，剩余${paused}秒")
        bubble.performSemanticsAction(SemanticsActions.Expand)
        body.assertIsDisplayed()
        Thread.sleep(1300) // Let a real media second pass while paused.
        assertEquals(paused, displayedSeconds(bubble))
        bubble.performSemanticsAction(SemanticsActions.Collapse)
        body.assertDoesNotExist()

        bubble.performClick()
        awaitState("正在播放")
        assertTrue(displayedSeconds(bubble) <= paused)
        ui.waitUntil(6000) { displayedSeconds(bubble) in 1 until paused }
        bubble.performTouchInput { longClick() }
        body.assertIsDisplayed()
        bubble.assert(hasStateDescription("正在播放"))
        body.performClick()
        body.assertDoesNotExist()
        bubble.assert(hasStateDescription("正在播放"))
        awaitState("待播放", timeoutMs = 20000)
        assertEquals(12, displayedSeconds(bubble))
        bubble.performClick()
        awaitState("正在播放")
    }

    @Test fun startingAnotherBubbleStopsTheFirst() {
        val first = testVoiceWav(app.cacheDir, "first-tone")
        val second = testVoiceWav(app.cacheDir, "second-tone")
        lateinit var playback: ChatVoicePlayback
        ui.setContent {
            playback = rememberChatVoicePlayback()
            Column {
                ChatVoiceBubble(first, "第一条", playback)
                ChatVoiceBubble(second, "第二条", playback)
            }
        }
        val bubbles = ui.onAllNodesWithTag("chat-voice-bubble")
        ui.waitUntil(10000) { bubbles.fetchSemanticsNodes().size == 2 }
        val bounds = bubbles[0].getUnclippedBoundsInRoot()
        bubbles[0].performClick()
        ui.waitUntil(10000) { displayedSeconds(bubbles[0]) in 1..9 }
        assertEquals(bounds, bubbles[0].getUnclippedBoundsInRoot())
        assertEquals(12, displayedSeconds(bubbles[1]))
        bubbles[1].performClick()
        awaitState("正在播放")
        bubbles[1].assert(hasStateDescription("正在播放"))
        ui.waitUntil(6000) { displayedSeconds(bubbles[1]) in 1..11 }
        bubbles[0].assert(hasStateDescription("待播放"))
        assertEquals(12, displayedSeconds(bubbles[0]))
        ui.runOnIdle { playback.release() }
        bubbles[1].assert(hasStateDescription("待播放"))
        assertEquals(12, displayedSeconds(bubbles[1]))
    }

    @Test fun brokenAudioKeepsOriginalAndActions() {
        val file = File(app.cacheDir, "broken.mp3").apply { writeText("损坏音频") }
        var actions = 0
        ui.mainClock.autoAdvance = false
        try {
            show(file) { actions++ }
            ui.onNodeWithText("正在读取语音…").assertIsDisplayed().assertHasNoClickAction()
            saveVoiceTestScreenshot("voice-loading")
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitUntil(10000) { ui.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty() }
        bubble.assertDoesNotExist()
        ui.onNodeWithText("语音无法播放").assertIsDisplayed()
        val body = ui.onNodeWithText(original).assertIsDisplayed().assertHasNoClickAction()
        saveVoiceTestScreenshot("voice-unavailable")
        body.performTouchInput { click() }
        ui.runOnIdle { assertEquals(0, actions) }
        body.performTouchInput { longClick() }
        ui.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun playbackFailureAfterReadingDurationKeepsOriginal() {
        val file = testVoiceWav(app.cacheDir, "removed-tone")
        show(file)
        awaitState("待播放")
        assertTrue(file.delete())
        bubble.performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText(original).fetchSemanticsNodes().isNotEmpty() }
        bubble.assertDoesNotExist()
        ui.onNodeWithText("语音无法播放").assertIsDisplayed()
        ui.onNodeWithText(original).assertIsDisplayed().assertHasNoClickAction()
    }

    @Test fun serialPerRoleFailureAndLateInvalidation()=runBlocking {
        val role=UUID.randomUUID().toString()
        val first=UUID.randomUUID().toString()
        val second=UUID.randomUUID().toString()
        val third=UUID.randomUUID().toString()
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val audio=bytes()
        ChatVoiceDelivery.submit(app,role,first,{true}){entered.complete(Unit);release.await();throw java.io.IOException()}
        withTimeout(3000){entered.await()}
        val ran=CompletableDeferred<Unit>()
        ChatVoiceDelivery.submit(app,role,second,{true}){ran.complete(Unit);audio}
        assertFalse(ran.isCompleted)
        val valid=java.util.concurrent.atomic.AtomicBoolean(true)
        ChatVoiceDelivery.submit(app,role,third,{valid.get()}){valid.set(false);audio}
        release.complete(Unit)
        withTimeout(10000){while(listOf(first,second,third).any{it in ChatVoiceDelivery.pending.value}) delay(20)}
        assertFalse(ChatVoiceDelivery.file(app,first).exists())
        assertArrayEquals(audio,ChatVoiceDelivery.file(app,second).readBytes())
        assertFalse(ChatVoiceDelivery.file(app,third).exists())
    }
}
