package app.lianxu.independent

import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Run each permission scenario in its own instrumentation process on the dedicated API 35 AVD. */
class ChatNotificationHintTest {
    companion object {
        @BeforeClass @JvmStatic fun dedicatedNotificationScenariosOnly() {
            Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hintScenarios") == "true")
        }
    }
    private val pending = CompletableDeferred<TextChatResult>()
    private val calls = AtomicInteger()
    private val client = object : TextChatClient {
        override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult {
            syntheticCompanionStage(request)?.let { return it }
            calls.incrementAndGet()
            return pending.await()
        }
    }
    @get:Rule(order = 0) val fixture = MockChatFixture({ client })
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private lateinit var vm: AppState
    private val title get() = ui.onNodeWithText("聊天通知未开启")

    private fun start() {
        ui.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[AppState::class.java] }
        ui.waitUntil(15000) { vm.ready }
        ui.runOnIdle { vm.open("chat", "seed-lin") }
        title.assertDoesNotExist()
        ui.onNodeWithTag("message-input").performTextInput("合成进度测试")
        ui.onNodeWithTag("send-message").performClick()
        ui.waitUntil(20000) { calls.get() == 1 && vm.foregroundChatRequest != null }
    }

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(context.filesDir, "hint-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun verifySettings(action: String, expectedLabel: String) {
        ui.onNodeWithText("去设置").performClick()
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName == "com.android.settings" &&
                root.findAccessibilityNodeInfosByText(expectedLabel).isNotEmpty()) break
            SystemClock.sleep(100)
        }
        val root = requireNotNull(automation.rootInActiveWindow)
        assertEquals("com.android.settings", root.packageName)
        assertTrue("Missing settings label: $expectedLabel", root.findAccessibilityNodeInfosByText(expectedLabel).isNotEmpty())
        assertTrue(shell("dumpsys activity activities").contains(action))
        // Accessibility switches windows before the system transition has finished drawing.
        automation.waitForIdle(750, 5000)
    }

    @After fun release() { pending.complete(TextChatResult.Success("合成回复")) }

    @Test fun disabledAppOpensAppSettingsAndGrantHidesHint() {
        assertFalse(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        start()
        title.assertIsDisplayed()
        ui.onNodeWithText("开启后，可在通知栏查看回复进度。").assertIsDisplayed()
        ui.onNodeWithTag("message-input").assertIsEnabled()
        screenshot("card")
        verifySettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, "恋叙")
        screenshot("app-settings")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent KEYCODE_BACK")
        ui.waitUntil(10000) { ui.activity.hasWindowFocus() }
        title.assertDoesNotExist()
        assertTrue(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
    }

    @Test fun blockedChannelOpensRealChatChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(ChatRequestService.CHANNEL_ID).importance)
        start()
        title.assertIsDisplayed()
        verifySettings(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, "正在处理的聊天")
        screenshot("channel-settings")
        shell("input keyevent KEYCODE_BACK")
        ui.waitUntil(10000) { ui.activity.hasWindowFocus() }
        title.assertDoesNotExist()
    }

    @Test fun dismissSurvivesEditingAndAnotherChatRequest() {
        assertFalse(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        start()
        ui.onNodeWithTag("message-input").performTextInput("草稿")
        title.assertIsDisplayed()
        ui.onNodeWithText("暂不").performClick()
        title.assertDoesNotExist()
        ui.runOnIdle { vm.open("chat", "seed-an"); assertTrue(vm.send("第二个合成请求")) }
        ui.waitUntil(20000) { calls.get() == 2 }
        title.assertDoesNotExist()
        ui.runOnIdle { vm.open("chat", "seed-lin") }
        title.assertDoesNotExist()
    }

    @Test fun enabledChatStartsWithoutHint() {
        start()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        assertNotEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(ChatRequestService.CHANNEL_ID).importance)
        title.assertDoesNotExist()
    }
}
