package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class MemoryV2UiTest {
    @get:Rule(order=0)val mock=MockChatFixture()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun app():AppState{lateinit var v:AppState;ui.activityRule.scenario.onActivity{v=ViewModelProvider(it)[AppState::class.java]};return v}
    private fun hideIme(){ui.activityRule.scenario.onActivity{a->(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.window.decorView.clearFocus()};ui.waitForIdle()}
    private fun shot(name:String){hideIme();android.os.SystemClock.sleep(400);val dir=File(context.getExternalFilesDir(null),"evidence-memory-013").apply{mkdirs()};val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun originalContentBrowserNewEditorIndependentFiltersAndLegacyDetails(){
        ui.waitUntil(15000){app().ready};val v=app();val repo=v.processing;var preferenceId="";var legacyId=""
        runBlocking{
            repo.store.add(listOf(Friend("modern-ui","时安","合成数据 · 本机验收")),false)
            repo.manualSave("modern-ui",null,"最近在学烘焙，想把面包做得松软一点。",MemoryCategory.STATE_FACT,false,false,MemoryAbout.CHARACTER,listOf("日常"))
            preferenceId=repo.manualSave("modern-ui",null,"不喜欢太甜的饮料，更爱清淡一点。",MemoryCategory.PREFERENCE_BOUNDARY,true,true,MemoryAbout.USER,listOf("饮食")).id
            repo.manualSave("modern-ui",null,"一起讨论过旅行计划，想找个安静的地方。",MemoryCategory.EXPERIENCE_MEMORY,false,false,MemoryAbout.BOTH,listOf("旅行"))
            repo.manualSave("modern-ui",null,"周五聊聊最近读的书。",MemoryCategory.COMMITMENT_OPEN,false,false,MemoryAbout.BOTH,listOf("阅读"),"2000-01-01")
            legacyId=repo.manualSave("modern-ui",null,"旧版原文，不做迁移。",MemoryCategory.USER_PROFILE,true,true).id;v.refreshProcessing()
        }
        ui.runOnIdle{v.open("processing-role","modern-ui")};ui.onNodeWithTag("memory-list").assertExists();shot("01-memory-browser-real-app")
        ui.onNodeWithTag("memory-add").performClick();ui.onNodeWithTag("memory-text").performTextInput("喜欢夜晚散步。")
        ui.onNodeWithTag("memory-category-picker").performScrollTo().performClick();ui.onNodeWithTag("category-option-PREFERENCE_BOUNDARY").performClick();ui.onNodeWithTag("about-BOTH").performScrollTo().performClick();ui.onNodeWithTag("memory-tags").performScrollTo().performTextInput("习惯，散步")
        ui.onNodeWithTag("memory-save").assertIsDisplayed();shot("02-memory-editor-real-app");ui.onNodeWithTag("memory-save").performClick();ui.waitUntil(10000){runBlocking{repo.memories("modern-ui").size==6}}
        ui.onNodeWithTag("memory-filter-PREFERENCE_BOUNDARY").performScrollTo().performClick();ui.onNodeWithTag("memory-about-USER").performScrollTo().performClick();ui.onNodeWithText("喜欢夜晚散步。").assertDoesNotExist();ui.onNodeWithTag("memory-list").performScrollToNode(hasText("不喜欢太甜的饮料，更爱清淡一点。"));ui.onNodeWithText("不喜欢太甜的饮料，更爱清淡一点。").assertExists();shot("03-memory-filtered-real-app")
        ui.onNodeWithTag("memory-about-BOTH").performClick();ui.onNodeWithTag("memory-search").performTextInput("习惯");hideIme();ui.onNodeWithTag("memory-list").performScrollToNode(hasText("喜欢夜晚散步。"));ui.onNodeWithText("喜欢夜晚散步。").assertExists();ui.onNodeWithText("不喜欢太甜的饮料，更爱清淡一点。").assertDoesNotExist();hideIme();ui.onNodeWithTag("memory-search").performTextClearance();hideIme();ui.onNodeWithTag("memory-filter-all").performScrollTo().performClick();ui.onNodeWithTag("memory-about-all").performScrollTo().performClick()
        ui.onNodeWithTag("memory-list").performScrollToNode(hasTestTag("memory-$preferenceId"));ui.onNodeWithTag("memory-$preferenceId").performClick();shot("04-memory-detail-real-app");ui.onNodeWithTag("memory-edit").performClick();ui.onNodeWithTag("memory-text").performTextReplacement("清淡就好，不爱太甜。");hideIme();ui.onNodeWithTag("memory-save").performClick()
        ui.waitUntil(10000){runBlocking{repo.history("modern-ui",preferenceId).size==2}};ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("memory-filter-legacy").assertDoesNotExist()
        val original=runBlocking{repo.memories("modern-ui").single{it.id==legacyId}}
        ui.onNodeWithTag("memory-list").performScrollToNode(hasTestTag("memory-$legacyId"));ui.onNodeWithTag("memory-$legacyId").performClick()
        ui.onNodeWithTag("memory-edit").assertDoesNotExist();ui.onNodeWithText("未归类 · 原分类：用户画像").assertExists()
        ui.activityRule.scenario.recreate();ui.onNodeWithText("旧版原文，不做迁移。").assertExists()
        assertEquals(original,runBlocking{repo.memories("modern-ui").single{it.id==legacyId}})
        runBlocking{val p=repo.memories("modern-ui").first{it.id==preferenceId};assertEquals(MemoryAbout.USER,p.about);assertTrue(p.core&&p.locked&&p.manual);assertEquals(listOf("饮食"),p.tags);assertEquals(MemoryLife.ACTIVE,repo.memories("modern-ui").first{it.category==MemoryCategory.COMMITMENT_OPEN}.life);assertTrue(repo.memories("seed-an").isEmpty())}
    }
}
