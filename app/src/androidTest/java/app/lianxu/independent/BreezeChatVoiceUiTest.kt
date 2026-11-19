package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
    private fun shot(name:String) {
        val bitmap=inst.uiAutomation.takeScreenshot()
        File(app.cacheDir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun localAudioWavePlaybackPauseReplayAndTranscript() {
        val file=File(app.cacheDir,"local-tone.mp3").apply{writeBytes(bytes())}
        val envelope=VoiceAudio.decode(file)
        assertTrue(envelope.durationMs in 3900..4300)
        assertTrue(envelope.levels.max()-envelope.levels.min()>0.5f)
        ui.setContent { LianxuTheme { Box(Modifier.fillMaxSize().padding(24.dp)){ChatVoiceBubble(file,"这是本地测试音。")} } }
        val node=ui.onNodeWithTag("chat-voice-bubble")
        ui.waitUntil(10000){ui.onAllNodesWithTag("chat-voice-bubble").fetchSemanticsNodes().isNotEmpty()}
        node.performClick()
        ui.waitUntil(5000){ui.onAllNodes(hasStateDescription("正在播放")).fetchSemanticsNodes().isNotEmpty()}
        Thread.sleep(1200);node.performClick()
        node.assert(hasStateDescription("已暂停"));shot("voice-paused")
        node.performClick()
        ui.waitUntil(8000){ui.onAllNodes(hasStateDescription("待播放")).fetchSemanticsNodes().isNotEmpty()}
        node.performClick()
        ui.waitUntil(5000){ui.onAllNodes(hasStateDescription("正在播放")).fetchSemanticsNodes().isNotEmpty()}
        node.performClick()
        node.performTouchInput{longClick()}
        ui.onNodeWithText("查看原文").performClick()
        ui.onNodeWithText("这是本地测试音。").assertIsDisplayed()
        shot("voice-transcript")
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

