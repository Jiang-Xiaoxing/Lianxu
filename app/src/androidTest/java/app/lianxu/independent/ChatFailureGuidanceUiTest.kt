package app.lianxu.independent

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Verifies auto-shown plain-language failure guidance dialogs and their settings/model shortcuts on device. */
class ChatFailureGuidanceUiTest {
    private val nextFailure=AtomicReference<TextChatResult.Failure>(
        TextChatResult.Failure(
            ChatFailure.CONNECTION_INTERRUPTED,
            detail="阶段：请求已发出，等待服务商响应时连接中断（请求期间应用切到过后台）\nSocketException: Software caused connection abort"
        )
    )
    private val client=object:TextChatClient {
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            syntheticCompanionStage(request)?.let{return it}
            return nextFailure.get()
        }
    }
    @get:Rule(order=0) val fixture=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private fun vm():AppState {
        lateinit var app:AppState
        ui.activityRule.scenario.onActivity{app=ViewModelProvider(it)[AppState::class.java]}
        return app
    }

    @Test fun connectionInterruptedAutoShowsGuidanceAndOpensAppSettings() {
        ui.waitUntil(15000){vm().ready}
        ui.runOnIdle{vm().open("chat","seed-lin")}
        ui.onNodeWithTag("message-input").performTextInput("测试后台断连引导")
        ui.onNodeWithTag("send-message").performClick()

        ui.waitUntil(15000){ui.onAllNodesWithTag("turn-failure-action").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("等待回复时连接中断").assertIsDisplayed()
        ui.onNodeWithTag("turn-failure-guidance-steps").assertIsDisplayed()
        ui.onNodeWithTag("turn-failure-detail").assertIsDisplayed()
        ui.onNodeWithTag("turn-failure-copy").assertIsDisplayed()

        ui.onNodeWithTag("turn-failure-action").performClick()
        val deadline=SystemClock.elapsedRealtime()+10000
        while(SystemClock.elapsedRealtime()<deadline){
            if(automation.rootInActiveWindow?.packageName=="com.android.settings")break
            SystemClock.sleep(100)
        }
        assertEquals("com.android.settings",automation.rootInActiveWindow?.packageName)
        automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        ui.waitUntil(10000){ui.activity.hasWindowFocus()}
        // Returning from settings should not pop up the same failed attempt again automatically.
        ui.onAllNodesWithTag("turn-failure-action").assertCountEquals(0)
        val failed=vm().turns.last()
        ui.onNodeWithTag("turn-failure-${failed.id}").performClick()
        ui.onNodeWithTag("turn-failure-action").assertIsDisplayed()
        ui.onNodeWithTag("turn-failure-dismiss").performClick()
        ui.onAllNodesWithTag("turn-failure-action").assertCountEquals(0)
    }

    @Test fun truncatedFailureAutoShowsGuidanceAndNavigatesToModelConfig() {
        nextFailure.set(
            TextChatResult.Failure(
                ChatFailure.TRUNCATED,
                detail="阶段：辅助看图（模型：deepseek-v4.1-flash，输出上限：3072 token，非流式）\n模型返回 finish_reason=length，达到了当前配置的输出上限（3072 token），回复被截断。"
            )
        )
        ui.waitUntil(15000){vm().ready}
        ui.runOnIdle{vm().open("chat","seed-lin")}
        ui.onNodeWithTag("message-input").performTextInput("测试辅助看图截断引导")
        ui.onNodeWithTag("send-message").performClick()

        ui.waitUntil(15000){ui.onAllNodesWithTag("turn-failure-action").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("辅助看图达到输出上限").assertIsDisplayed()
        ui.onNodeWithText("去调大模型配置").assertIsDisplayed().performClick()
        ui.waitUntil(10000){vm().page=="models"}
        assertEquals("models",vm().page)
    }
}
