package app.lianxu.independent

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatReplyNotificationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val role = "seed-lin"
    private val reply = "本地测试的完整回复"
    private val received = CompletableDeferred<Unit>()
    private val result = CompletableDeferred<TextChatResult>()

    @get:Rule val fixture = MockChatFixture(clientFactory = { object : TextChatClient {
        override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult {
            received.complete(Unit)
            val answer = result.await()
            if (answer is TextChatResult.Failure) return answer
            return syntheticCompanionStage(request) ?: answer
        }
    } })

    private fun replies() = manager.activeNotifications.filter { it.tag == ChatReplyNotifications.TAG }
    private fun waiting() = manager.activeNotifications.filter { it.notification.channelId == ChatRequestService.CHANNEL_ID }
    private suspend fun until(condition: () -> Boolean) = withTimeout(45_000) {
        while (!condition()) delay(100)
    }

    private fun chat(test: suspend (ActivityScenario<MainActivity>, AppState) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppState
            scenario.onActivity { vm = ViewModelProvider(it)[AppState::class.java] }
            runBlocking {
                try {
                    withContext(Dispatchers.Main) {
                        until { vm.ready }
                        vm.open("chat", role)
                        assertTrue(vm.isReadingChat(role))
                        assertTrue(vm.send("合成测试输入"))
                    }
                    withTimeout(45_000) { received.await() }
                    if (manager.areNotificationsEnabled()) until { waiting().isNotEmpty() }
                    test(scenario, vm)
                } finally {
                    result.complete(TextChatResult.Failure(ChatFailure.NETWORK))
                    replies().forEach { manager.cancel(it.tag, it.id) }
                }
            }
        }
    }

    private suspend fun settled(vm: AppState, state: TurnState) = withContext(Dispatchers.Main) {
        val finished = withTimeoutOrNull(45_000) {
            while (vm.turns.singleOrNull()?.state != state || vm.isReplying(role)) delay(100)
            true
        }
        assertEquals("Expected $state, got ${vm.turns.map { it.state to it.failure }} (active=${vm.isReplying(role)})", true, finished)
        if (state == TurnState.COMPLETED) {
            val id = vm.turns.single().assistantMessageId
            assertEquals(reply, vm.messages.single { it.id == id }.text)
        }
        until { waiting().isEmpty() }
    }

    @Test fun backgroundSavesThenPostsOnePrivateMessage() = chat { _, vm ->
        shell("input keyevent KEYCODE_HOME")
        withContext(Dispatchers.Main) { until { !vm.isReadingChat(role) } }
        result.complete(TextChatResult.Success(reply))
        settled(vm, TurnState.COMPLETED)
        until { replies().size == 1 }
        val entry = replies().single()
        assertEquals(vm.turns.single().assistantMessageId, entry.id.toLong())
        assertEquals(Notification.CATEGORY_MESSAGE, entry.notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, entry.notification.visibility)
        assertEquals("轻点查看聊天", entry.notification.extras.getCharSequence(Notification.EXTRA_TEXT))

        val automation = inst.uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        try {
            info.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            shell("cmd statusbar expand-notifications")
            val bounds = visibleText("com.android.systemui", "收到新消息")
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            withContext(Dispatchers.Main) { until { vm.isReadingChat(role) } }
            visibleText(app.packageName, reply)
        } finally {
            info.flags = previousFlags
            automation.serviceInfo = info
            shell("cmd statusbar collapse")
        }
        until { replies().isEmpty() }
    }

    private suspend fun visibleText(pkg: String, text: String): Rect {
        fun find(node: AccessibilityNodeInfo): Rect? {
            if (node.isVisibleToUser && node.text?.contains(text) == true) {
                return Rect().also { node.getBoundsInScreen(it) }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }?.let { return it }
            return null
        }
        Log.i("LianxuReplyTest", "Checking visible text in $pkg")
        return withTimeoutOrNull(45_000) {
            var bounds: Rect? = null
            while (bounds == null) {
                bounds = inst.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName == pkg }
                    .firstNotNullOfOrNull { find(it) }
                if (bounds == null) delay(250)
            }
            bounds
        } ?: error("Visible text missing in $pkg")
    }

    @Test fun visibleChatFinishesWithoutNotification() = chat { _, vm ->
        result.complete(TextChatResult.Success(reply))
        settled(vm, TurnState.COMPLETED)
        assertTrue(replies().isEmpty())
    }

    @Test fun otherChatReceivesNotificationAndTapOpensSavedReply() = chat { _, vm ->
        withContext(Dispatchers.Main) { vm.open("chat", "seed-an") }
        result.complete(TextChatResult.Success(reply))
        settled(vm, TurnState.COMPLETED)
        until { replies().size == 1 }
        replies().single().notification.contentIntent.send()
        withContext(Dispatchers.Main) { until { vm.isReadingChat(role) } }
        assertEquals(vm.turns.single().assistantMessageId, vm.messages.single { it.text == reply }.id)
    }

    @Test fun disabledNotificationsKeepSavedReply() {
        fun openSettings() {
            shell("am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE ${app.packageName}")
        }
        val original = manager.areNotificationsEnabled()
        try {
            openSettings()
            runBlocking { setAppNotifications(false) }
            shell("input keyevent KEYCODE_HOME")
            chat { _, vm ->
                withContext(Dispatchers.Main) { vm.open("home") }
                result.complete(TextChatResult.Success(reply))
                settled(vm, TurnState.COMPLETED)
                assertTrue(replies().isEmpty())
            }
        } finally {
            openSettings()
            runBlocking { setAppNotifications(original) }
            shell("input keyevent KEYCODE_HOME")
        }
    }

    private suspend fun setAppNotifications(enabled: Boolean) {
        if (manager.areNotificationsEnabled() == enabled) return
        fun switches(node: AccessibilityNodeInfo): List<Rect> = buildList {
            if (node.isCheckable && node.isVisibleToUser && node.isEnabled) {
                add(Rect().also { node.getBoundsInScreen(it) })
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(switches(it)) }
        }
        var master: Rect? = null
        until {
            val root = inst.uiAutomation.rootInActiveWindow
            if (root?.packageName == "com.android.settings") master = switches(root).minByOrNull { it.top }
            master != null
        }
        requireNotNull(master).let { shell("input tap ${it.centerX()} ${it.centerY()}") }
        until { manager.areNotificationsEnabled() == enabled }
    }

    @Test fun failureAwayDoesNotPost() = chat { _, vm ->
        withContext(Dispatchers.Main) { vm.open("home") }
        result.complete(TextChatResult.Failure(ChatFailure.NETWORK))
        settled(vm, TurnState.FAILED)
        assertTrue(replies().isEmpty())
    }

    @Test fun stoppedFromWaitingNotificationDoesNotPost() = chat { _, vm ->
        withContext(Dispatchers.Main) { vm.open("home") }
        waiting().single().notification.actions.single().actionIntent.send()
        settled(vm, TurnState.SUPERSEDED)
        result.complete(TextChatResult.Success(reply))
        assertTrue(replies().isEmpty())
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
