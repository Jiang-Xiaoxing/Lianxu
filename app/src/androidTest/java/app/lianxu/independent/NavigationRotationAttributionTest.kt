package app.lianxu.independent

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Compare the unchanged navigation path on original/candidate APKs using only synthetic data. */
class NavigationRotationAttributionTest {
 @get:Rule(order=0) val fixture=MockChatFixture()
 @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
 @Test fun compareUnscrolledAndScrolledAboutAfterRotation(){
  val instrument=InstrumentationRegistry.getInstrumentation()
  val label=InstrumentationRegistry.getArguments().getString("artifactLabel")?:"unknown"
  require(label.matches(Regex("[a-z-]+")))
  lateinit var vm:AppState;ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
  ui.waitUntil(15000){vm.ready};ui.onNodeWithTag("tab-2").performClick()
  ui.onNodeWithTag("settings-display").performScrollTo().performClick()
  ui.onNodeWithTag("settings-display-page").assertIsDisplayed()
  ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
  ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE}
  ui.onNodeWithTag("settings-display-page").assertIsDisplayed()
  ui.activityRule.scenario.onActivity{it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
  ui.waitUntil(15000){ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
  ui.onNodeWithContentDescription("返回设置").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
  val info=ui.onNodeWithTag("settings-info");val beforeVisible=info.isDisplayed();val bounds=info.fetchSemanticsNode().boundsInRoot.toString()
  fun shot(suffix:String){val image=instrument.uiAutomation.takeScreenshot();File(instrument.targetContext.filesDir,"nav-$label-$suffix.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
  shot("before")
  info.performClick();ui.waitForIdle()
  val unscrolledPage=vm.page;val unscrolledOpened=ui.onAllNodesWithTag("settings-info-page").fetchSemanticsNodes().isNotEmpty()
  if(unscrolledOpened)ui.onNodeWithTag("settings-back").performClick() else if(vm.page!="home")ui.runOnIdle{vm.home()}
  ui.onNodeWithTag("settings-home").assertIsDisplayed()
  ui.onNodeWithTag("settings-info").performScrollTo().assertIsDisplayed().performClick()
  ui.waitUntil(10000){ui.onAllNodesWithTag("settings-info-page").fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithText("姜小星").assertIsDisplayed();shot("after-scroll")
  val result=JsonObject().apply{addProperty("apk",label);addProperty("aboutVisibleBeforeScroll",beforeVisible);addProperty("aboutBoundsBeforeScroll",bounds);addProperty("pageAfterUnscrolledClick",unscrolledPage);addProperty("aboutOpenedWithoutScroll",unscrolledOpened);addProperty("aboutOpenedAfterScroll",true);addProperty("syntheticOnly",true)}
  File(instrument.targetContext.filesDir,"nav-$label.json").writeText(result.toString())
  ui.onNodeWithTag("settings-back").performClick();ui.onNodeWithTag("settings-home").assertIsDisplayed()
  assertEquals("home",vm.page)
 }
}
