package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BreezeVoiceChatScreenTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun opensSavedSpeechInRealConversation() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        lateinit var vm:AppState
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        val store=Store(app,fixture.database)
        try {
            val file=runBlocking {
                val ledger=ChatLedger(store)
                val t=ledger.begin("seed-lin","我到家啦。","local")
                assertTrue(ledger.finish(t.id,t.attempt,"到家就好。"))
                val id=ChatVoiceDelivery.key("seed-lin",t.id,t.attempt,"到家就好。")
                ChatVoiceDelivery.file(app,id).apply { parentFile!!.mkdirs()
                    inst.context.assets.open("voice/modulated-tone.mp3").use{writeBytes(it.readBytes())} }
            }
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){vm.refreshChat()} }
            ui.runOnIdle{vm.open("chat","seed-lin")}
            ui.waitUntil(15000){ui.onAllNodesWithTag("chat-voice-bubble").fetchSemanticsNodes().isNotEmpty()}
            val bubble=ui.onNodeWithTag("chat-voice-bubble")
            val recording=inst.uiAutomation.executeShellCommand("screenrecord --size 540x960 --time-limit 4 /data/local/tmp/lianxu-wave-preview.mp4")
            bubble.performClick()
            ui.waitUntil(5000){ui.onAllNodes(hasStateDescription("正在播放")).fetchSemanticsNodes().isNotEmpty()}
            ui.waitUntil(6000){bubble.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current in 0.27f..0.9f}
            val screenshot=inst.uiAutomation.takeScreenshot()
            File(app.cacheDir,"voice-conversation.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
            screenshot.recycle()
            bubble.performClick()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(recording).use{it.readBytes()}
            bubble.performTouchInput{longClick()}
            ui.onNodeWithText("查看原文").performClick()
            ui.onNodeWithText("到家就好。").assertIsDisplayed()
            file.delete()
        } finally {store.close()}
    }
}

