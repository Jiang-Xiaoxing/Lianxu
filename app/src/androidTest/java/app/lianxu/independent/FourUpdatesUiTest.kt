package app.lianxu.independent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class FourUpdatesUiTest {
 @get:Rule(order=0) val mock=MockChatFixture()
 @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
 private val instrument get()=InstrumentationRegistry.getInstrumentation()
 private fun shot(name:String){ui.waitForIdle();val image=instrument.uiAutomation.takeScreenshot();File(instrument.targetContext.filesDir,"four-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
 @Test fun prominentRulesAndCorrectAuthor(){
  lateinit var vm:AppState;ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]};ui.waitUntil(15000){vm.ready};ui.waitForIdle();if(ui.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithText("知道了").performClick()
  ui.onNodeWithTag("tab-2").performClick();ui.onNodeWithTag("settings-processing").performClick()
  ui.onNodeWithTag("settings-memory-rules").assertIsDisplayed().assertHasClickAction();shot("entry")
  ui.onNodeWithTag("settings-memory-rules").performClick();ui.onNodeWithTag("rules-recall-enabled").performScrollTo().assertExists();ui.onNodeWithTag("rules-write-enabled").assertExists()
  ui.onNodeWithTag("rules-recall-rounds").assertDoesNotExist();ui.onNodeWithTag("rules-recall-tokens").assertDoesNotExist();ui.onNodeWithTag("rules-recall-per-call").assertDoesNotExist();ui.onNodeWithTag("recall-adaptive-info").performScrollTo().assertExists();shot("rules")
  ui.runOnIdle{vm.open("settings-info")};ui.onNodeWithText("姜小星").assertExists();shot("about")
 }
}

class ArchitectureSvgRenderTest {
 @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
 @Test fun renderFourLocalSvgDiagramsWithChineseText(){
  val instrument=InstrumentationRegistry.getInstrumentation();lateinit var web:WebView
  val ready=AtomicBoolean();val painted=AtomicBoolean()
  ui.setContent{AndroidView(factory={context->WebView(context).also{web=it;it.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView,url:String){ready.set(true);view.postVisualStateCallback(1,object:WebView.VisualStateCallback(){override fun onComplete(id:Long){painted.set(true)}})}}}},modifier=Modifier.fillMaxWidth().height(150.dp))}
  for(i in 1..4){ready.set(false);painted.set(false);val svg=instrument.context.assets.open("architecture-flow-$i.svg").bufferedReader().use{it.readText()}
   ui.runOnUiThread{web.loadDataWithBaseURL(null,"<html><head><meta name='viewport' content='width=device-width,initial-scale=1'/><style>body{margin:0}svg{width:100%;height:auto}</style></head><body>$svg</body></html>","text/html","UTF-8",null)}
   ui.waitUntil(15000){ready.get()&&painted.get()};ui.runOnIdle{assertTrue(web.width>0);val image=Bitmap.createBitmap(web.width,(web.width/3),Bitmap.Config.ARGB_8888);web.draw(Canvas(image));File(instrument.targetContext.filesDir,"architecture-flow-$i.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
  }
  ui.runOnUiThread{web.destroy()}
 }
}
