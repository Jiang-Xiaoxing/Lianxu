package app.lianxu.independent

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 真正的系统保存/打开流程；不替换 ActivityResultRegistry，也不使用夹具 ContentProvider。 */
class BackupDocumentsUiTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrument.uiAutomation
    private lateinit var vm: AppState
    private lateinit var namespace: String
    private lateinit var oldDatabase: String
    private lateinit var oldVault: String
    private var oldAccessibilityFlags = 0
    private val calls = AtomicInteger()

    @get:Rule(order = 0)
    val fixture = object : ExternalResource() {
        override fun before() {
            oldDatabase = AppServices.databaseName
            oldVault = AppServices.vaultNamespace
            namespace = "backup-documents-${System.nanoTime()}"
            AppServices.databaseName = "$namespace.db"
            AppServices.vaultNamespace = namespace
            val info = automation.serviceInfo
            oldAccessibilityFlags = info.flags
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = info
        }

        override fun after() {
            val info = automation.serviceInfo
            info.flags = oldAccessibilityFlags
            automation.serviceInfo = info
            if (::vm.isInitialized) vm.processing.store.close()
            instrument.targetContext.deleteDatabase("$namespace.db")
            File(instrument.targetContext.noBackupFilesDir, namespace).deleteRecursively()
            AppServices.databaseName = oldDatabase
            AppServices.vaultNamespace = oldVault
        }
    }

    @get:Rule(order = 1)
    val ui = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        ui.runOnUiThread {
            vm = ViewModelProvider(ui.activity, object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AppState(ui.activity.application, object : TextChatClient {
                        override suspend fun complete(config: ModelConfig, key: String, request: TextChatRequest): TextChatResult {
                            calls.incrementAndGet()
                            return TextChatResult.Failure(ChatFailure.NETWORK)
                        }
                    }, namespace) as T
                }
            })[AppState::class.java]
        }
        ui.waitUntil(30_000) { vm.ready }
        runBlocking {
            RoleAssets(vm.processing).put("seed-lin", RoleAssets.Asset("picker-proof.bin", "attachment", "application/octet-stream", byteArrayOf(11, 22, 33)))
        }
        // 默认 LocalActivityResultRegistryOwner 就是实际 Activity，保持生产页面的系统契约。
        ui.setContent { LianxuTheme { androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(),color=Paper){RoleArchiveScreen(vm)} } }
    }

    @Suppress("DEPRECATION")
    private fun findCopy(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                findCopy(child, predicate)?.let { return it }
            } finally { child.recycle() }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun documentNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return try {
            findCopy(root) { it.isVisibleToUser && it.packageName?.toString()?.contains("documentsui") == true && predicate(it) }
        } finally { root.recycle() }
    }

    private fun awaitNode(label: String, timeout: Long = 20_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            documentNode(predicate)?.let { return it }
            SystemClock.sleep(150)
        } while (SystemClock.uptimeMillis() < deadline)
        saveEvidence("failed-$label")
        throw AssertionError("系统文件选择器未找到：$label；已保存实际界面证据")
    }

    @Suppress("DEPRECATION")
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        val bounds = Rect().also(node::getBoundsInScreen)
        try {
            while (current != null) {
                if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                val parent = current.parent
                current.recycle()
                current = parent
            }
            // DocumentsUI 在窗口切换期间可能拒绝 accessibility ACTION_CLICK；按实际控件边界
            // 发送普通触摸事件，与用户点按相同，不依赖私有接口或虚构屏幕坐标。
            require(!bounds.isEmpty) { "系统文件选择器控件没有可点击的屏幕边界" }
            val down = SystemClock.uptimeMillis()
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            val pointer=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}
            val coordinate=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=1f;size=1f}
            fun touch(action: Int, time: Long): Boolean = MotionEvent.obtain(down,time,action,1,arrayOf(pointer),arrayOf(coordinate),0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0).let {
                try { it.source = InputDevice.SOURCE_TOUCHSCREEN; automation.injectInputEvent(it, true) }
                finally { it.recycle() }
            }
            val pressed = touch(MotionEvent.ACTION_DOWN, down)
            SystemClock.sleep(100)
            val released = touch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
            if (!pressed || !released) {
                saveEvidence("failed-click")
                throw AssertionError("系统文件选择器普通触摸输入未成功")
            }
        } finally { current?.recycle() }
    }

    private fun downloads() {
        val drawer = documentNode {
            val desc = it.contentDescription?.toString().orEmpty()
            desc.equals("Show roots", true) || desc == "显示根目录" || desc == "显示根文件夹" ||
                desc == "打开导航抽屉" || desc.equals("Open navigation drawer", true)
        }
        if (drawer != null) click(drawer)
        // 兼容模拟器英文与中文语言；只选择系统提供的本地 Downloads 根目录。
        click(awaitNode("downloads") { it.text?.toString() in setOf("Downloads", "下载") })
        // 抽屉关闭动画尚在进行时，文件列表已经出现在节点树中；必须等待真实窗口稳定，
        // 否则下一次点选只会关闭抽屉，不能代表实际选中了文件。
        runCatching { automation.waitForIdle(300, 3_000) }
        SystemClock.sleep(400)
    }

    @Suppress("DEPRECATION")
    private fun setText(node: AccessibilityNodeInfo, text: String) {
        try {
            assertTrue("系统文件名输入失败", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
        } finally { node.recycle() }
    }

    private fun waitForApp() {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = documentNode { true }
            if (node == null) return
            @Suppress("DEPRECATION")
            node.recycle()
            SystemClock.sleep(150)
        }
        saveEvidence("failed-return")
        throw AssertionError("系统文件选择器尚未返回应用")
    }

    private fun chooseFile(filename: String) {
        var file = documentNode { it.text?.toString() == filename }
        if (file == null) {
            click(awaitNode("search") { it.contentDescription?.toString() in setOf("Search", "搜索") })
            setText(awaitNode("search-input") { it.className?.toString() == "android.widget.EditText" }, filename)
            file = awaitNode("saved-file") { it.text?.toString() == filename }
        }
        click(requireNotNull(file))
        // ACTION_OPEN_DOCUMENT 通常点选即返回；多选式系统界面可能还显示打开按钮。
        SystemClock.sleep(350)
        documentNode { it.isEnabled && it.text?.toString() in setOf("OPEN", "Open", "打开") }?.let(::click)
    }

    @Suppress("DEPRECATION")
    private fun saveEvidence(label: String) {
        automation.takeScreenshot()?.let { image ->
            File(instrument.targetContext.filesDir, "backup-documents-$label.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        val root = automation.rootInActiveWindow ?: return
        val description = StringBuilder()
        fun append(node: AccessibilityNodeInfo, level: Int) {
            description.append(" ".repeat(level)).append(node.packageName).append(' ')
                .append(node.viewIdResourceName).append(' ').append(node.className).append(' ')
                .append(node.text).append(' ').append(node.contentDescription)
                .append(" visible=").append(node.isVisibleToUser).append(" enabled=").append(node.isEnabled)
                .append(" clickable=").append(node.isClickable).append(" bounds=").append(Rect().also(node::getBoundsInScreen)).append('\n')
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { append(child, level + 1) } finally { child.recycle() }
            }
        }
        try { append(root, 0) } finally { root.recycle() }
        File(instrument.targetContext.filesDir, "backup-documents-$label.txt").writeText(description.toString(), Charsets.UTF_8)
    }

    @Test
    fun systemSaveAndOpenRoundTripPreservesRoleAndResourceWithoutModelCalls() {
        show()
        val before = vm.friends.toList()
        val filename = "lx-doc-${System.nanoTime().toString(36)}.zip"
        ui.onNodeWithTag("archive-export-seed-lin").performScrollTo().performClick()
        ui.onNodeWithTag("archive-export").performScrollTo().performClick()
        awaitNode("save-dialog") { true }.also { @Suppress("DEPRECATION") it.recycle() }
        downloads()
        setText(awaitNode("filename") { it.className?.toString() == "android.widget.EditText" }, filename)
        saveEvidence("save")
        click(awaitNode("save-button") { it.isEnabled && it.text?.toString() in setOf("SAVE", "Save", "保存") })
        waitForApp()
        ui.waitUntil(30_000) { ui.onAllNodesWithText("已保存。备份和详细日志可能含私密资料，请妥善保管。").fetchSemanticsNodes().isNotEmpty() }

        ui.onNodeWithTag("archive-open").performScrollTo().performClick()
        awaitNode("open-dialog") { true }.also { @Suppress("DEPRECATION") it.recycle() }
        downloads()
        saveEvidence("open")
        chooseFile(filename)
        waitForApp()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("archive-mode-add").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("archive-mode-add").performClick()
        ui.onNodeWithTag("archive-preview").performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("archive-impact").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("archive-import").performScrollTo().performClick()
        ui.waitUntil(30_000) { vm.friends.size == before.size + 1 }

        val copy = vm.friends.single { copied -> before.none { it.id == copied.id } }
        assertEquals(before.single { it.id == "seed-lin" }.name, copy.name)
        assertArrayEquals(byteArrayOf(11, 22, 33), runBlocking { RoleAssets(vm.processing).list(copy.id).single().bytes })
        assertEquals("导入导出不应调用模型", 0, calls.get())
        saveEvidence("completed")
        // 专用模拟器 Downloads 中保留这一份合成包，作为实际系统保存流程的可检查证据。
    }
}
