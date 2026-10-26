package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Dedicated synthetic stores; no provider requests or real account data. */
@OptIn(ExperimentalTestApi::class)
class CloseoutAcceptanceUiTest {
    @get:Rule(order = 0) val mock = MockChatFixture()
    // Repository effects resume after IO; keep the test recomposer on the UI thread.
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>(effectContext = Dispatchers.Main)

    private fun app(): AppState {
        lateinit var state: AppState
        ui.activityRule.scenario.onActivity { state = ViewModelProvider(it)[AppState::class.java] }
        return state
    }

    private fun ready(): AppState {
        ui.waitUntil(30_000) { app().ready }
        return app()
    }

    private fun hideIme() {
        ui.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.window.decorView.clearFocus()
        }
        ui.waitForIdle()
    }

    private fun screenshot(name: String) {
        hideIme()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "evidence-closeout").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun optionalSwitchesAndQuotaValidationPersistWithoutChangingRetention() {
        val state = ready()
        ui.runOnIdle { state.open("settings-memory") }
        ui.onNodeWithTag("rules-recall-enabled").performScrollTo().assertIsOff().performClick()
        ui.onNodeWithTag("rules-write-enabled").performScrollTo().assertIsOff().performClick()
        ui.onNodeWithTag("rules-recall-rounds").performScrollTo().performTextReplacement("4")
        ui.onNodeWithTag("rules-recall-tokens").performScrollTo().performTextReplacement("5000")
        ui.onNodeWithTag("rules-recall-per-call").performScrollTo().performTextReplacement("5001")
        hideIme()
        ui.onNodeWithTag("memory-rules-save").performClick()
        ui.onNodeWithTag("memory-rules-status").performScrollTo().assertTextContains("请检查", substring = true)
        assertEquals(MemoryRules(), runBlocking { state.processing.memoryRules().first })
        ui.onNodeWithTag("rules-recall-per-call").performScrollTo().performTextReplacement("1600")
        hideIme()
        ui.onNodeWithTag("memory-rules-save").performClick()
        val expected = MemoryRules(proactiveRecall = true, immediateWrite = true, recallRounds = 4, recallTokens = 5000, recallPerCall = 1600)
        ui.waitUntil(30_000) { runBlocking { state.processing.memoryRules().first == expected } }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("rules-recall-enabled").performScrollTo().assertIsOn()
        ui.onNodeWithTag("rules-write-enabled").performScrollTo().assertIsOn()
        ui.onNodeWithTag("rules-recall-per-call").performScrollTo().assertTextContains("1600")
        screenshot("01-saved-tools-and-quotas")
        assertEquals(expected, runBlocking { app().processing.memoryRules().first })
    }

    @Test fun annualBirthdayRejectsInvalidDateAndPreservesUnknownYearOnEdit() {
        val state = ready()
        val repo = state.processing
        runBlocking { repo.store.add(listOf(Friend("annual-ui", "年度日期验收", "仅合成数据")), false); state.refreshProcessing() }
        ui.runOnIdle { state.open("processing-role", "annual-ui") }
        ui.onNodeWithTag("memory-add").performClick()
        ui.onNodeWithTag("memory-text").performTextInput("生日是二月二十九日，出生年份未知。")
        ui.onNodeWithTag("memory-birthday").performScrollTo().performTextInput("02-30")
        hideIme()
        ui.onNodeWithTag("memory-save").performClick()
        ui.onNodeWithText("请检查标签数量、长度和日期格式。").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { repo.memories("annual-ui").isEmpty() })
        ui.onNodeWithTag("memory-birthday").performScrollTo().performTextReplacement("02-29")
        hideIme()
        ui.onNodeWithTag("memory-save").performClick()
        ui.waitUntil(30_000) { runBlocking { repo.memories("annual-ui").size == 1 } }
        val saved = runBlocking { repo.memories("annual-ui").single() }
        assertNull(saved.eventDate)
        assertEquals("02-29", saved.annualMonthDay)
        ui.onNodeWithTag("memory-${saved.id}").performClick()
        ui.onNodeWithText("年度生日 02-29 · 出生年份未知").assertIsDisplayed()
        screenshot("02-annual-birthday")
        ui.onNodeWithTag("memory-edit").performClick()
        ui.onNodeWithTag("memory-text").performTextReplacement("只修改正文，仍然不知道出生年份。")
        hideIme()
        ui.onNodeWithTag("memory-save").performClick()
        ui.waitUntil(30_000) { runBlocking { repo.memories("annual-ui").single().version > saved.version } }
        val edited = runBlocking { repo.memories("annual-ui").single() }
        assertEquals("02-29", edited.annualMonthDay)
        assertNull(edited.eventDate)
    }

    @Test fun sourceKeepConfirmsWholeCollectionWithoutRestoringEvidence() = sourceChoice(SourceMemoryChoice.KEEP)
    @Test fun sourceInvalidatePreservesProtectedTextForReview() = sourceChoice(SourceMemoryChoice.INVALIDATE)

    private fun sourceChoice(choice: SourceMemoryChoice) {
        val state = ready()
        val repo = state.processing
        runBlocking {
            repo.store.add(listOf(Friend("source-ui", "来源确认验收", "仅合成数据")), false)
            val ledger = ChatLedger(repo.store)
            val turn = ledger.begin("source-ui", "合成来源原话", "fixture")
            assertTrue(ledger.finish(turn.id, turn.attempt, "合成回复"))
            val source = repo.sources("source-ui").single()
            val memory = StoredMemory("source-normal", "source-ui", MemoryCategory.STATE_FACT, "普通来源记忆",
                about = MemoryAbout.USER, evidence = listOf(MemoryEvidence(source.source, "user", source.user.text, source.user.text, source.user.created, source.zone)))
            repo.tx { repo.saveMemory(it, memory); repo.saveMemory(it, memory.copy(id = "source-protected", text = "受保护来源记忆", manual = true, locked = true)) }
            state.refreshProcessing()
        }
        ui.runOnIdle { state.open("processing-role", "source-ui") }
        ui.onNodeWithTag("memory-maintenance").performClick()
        ui.onNodeWithTag("processing-sources").performScrollTo().performClick()
        ui.onNodeWithText("编辑 / 撤回 / 历史").performClick()
        ui.onNodeWithTag("source-text").performTextReplacement("修订后的合成来源原话")
        hideIme()
        ui.onNodeWithText("保存修改").performClick()
        // The impact preview reads SQLite asynchronously; Compose idleness alone
        // does not mean that the preview coroutine has returned.
        ui.waitUntil(30_000) { ui.onAllNodesWithText("确认影响范围").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("确认影响范围").assertIsDisplayed()
        ui.onNodeWithText("普通来源记忆").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("受保护来源记忆").performScrollTo().assertIsDisplayed()
        if (choice == SourceMemoryChoice.INVALIDATE) ui.onNodeWithText("整体作废").performScrollTo().performClick()
        screenshot("03-source-${choice.name.lowercase()}-confirmation")
        ui.onNodeWithText("确认修改").performClick()
        ui.waitUntil(30_000) { runBlocking { repo.memories("source-ui").all { !it.valid } } }
        runBlocking {
            val memories = repo.memories("source-ui")
            assertEquals(2, memories.size)
            val normal = memories.first { it.id == "source-normal" }
            assertEquals(if (choice == SourceMemoryChoice.KEEP) MemoryLife.ACTIVE else MemoryLife.RETRACTED, normal.life)
            val protected = memories.first { it.id == "source-protected" }
            assertEquals("受保护来源记忆", protected.text)
            assertTrue(protected.manual && protected.locked)
            assertEquals(MemoryLife.ACTIVE, protected.life)
            assertTrue(repo.candidates("source-ui").any { it.targetId == protected.id && !it.valid })
            assertTrue(repo.read { repo.usableMemories(it, "source-ui").isEmpty() })
        }
    }
}
