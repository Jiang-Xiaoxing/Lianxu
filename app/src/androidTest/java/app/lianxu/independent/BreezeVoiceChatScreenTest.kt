package app.lianxu.independent

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

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
        val wav = testVoiceWav(app.cacheDir, "conversation")
        try {
            val voiceId = runBlocking {
                val ledger=ChatLedger(store)
                val t=ledger.begin("seed-lin","我到家啦。","local")
                assertTrue(ledger.finish(t.id,t.attempt,"到家就好。"))
                ChatVoiceDelivery.key("seed-lin",t.id,t.attempt,"到家就好。")
            }
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main){vm.refreshChat()} }
            ui.runOnUiThread {
                vm.open("chat", "seed-lin")
                ui.activity.setContent {
                    CompositionLocalProvider(LocalChatVoiceFile provides { context, id ->
                        if (id == voiceId) wav else ChatVoiceDelivery.file(context, id)
                    }) { LianxuTheme { Lianxu(vm) } }
                }
            }
            ui.waitUntil(15000){ui.onAllNodesWithTag("chat-voice-bubble").fetchSemanticsNodes().isNotEmpty()}
            val bubble=ui.onNodeWithTag("chat-voice-bubble")
            bubble.performClick()
            ui.waitUntil(5000){ui.onAllNodes(hasStateDescription("正在播放")).fetchSemanticsNodes().isNotEmpty()}
            ui.waitUntil(10000) { ui.onAllNodes(hasText("9″") or hasText("8″") or hasText("7″")).fetchSemanticsNodes().isNotEmpty() }
            bubble.performClick()
            bubble.assert(hasStateDescription("已暂停"))
            bubble.performTouchInput { longClick() }
            val transcript = ui.onNodeWithText("到家就好。")
            transcript.assertIsDisplayed()
            saveVoiceTestScreenshot("voice-conversation")
            transcript.performTouchInput { longClick() }
            ui.onNodeWithText("这条消息的使用方式").assertIsDisplayed()
        } finally {
            wav.delete()
            store.close()
        }
    }
}
