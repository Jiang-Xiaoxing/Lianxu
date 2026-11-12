package app.lianxu.independent

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class ProviderSelectionIncrementUiTest {
 @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val namespace="provider-ui-${System.nanoTime()}"
 private val models=ViewModelStore()
 private lateinit var state:ModelConfigState
 private var calls=0
 @Before fun setup(){
  ui.runOnIdle{state=ModelConfigState(context.applicationContext as Application,object:ModelDirectoryClient{
   override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult{calls++;return ConfigResult.Success(listOf("Qwen/Qwen3-Embedding-4B","unknown-embedding-looking-id","chat-model"))}
  },namespace);models.put("state",state)}
  ui.setContent{LianxuTheme{ModelConfigScreen({},state)}}
  ui.waitForIdle()
 }
 @After fun cleanup(){ui.runOnIdle{models.clear()};ModelVault(context,namespace).list().forEach{ModelVault(context,namespace).delete(it.id)}}
 @Test fun commonThenAllAndAliasSearchOpensGoWithoutNetwork(){
  ui.onNodeWithTag("config-add").performScrollTo().performClick()
  ui.onNodeWithTag("provider-opencode-go").assertDoesNotExist()
  ui.onNodeWithTag("providers-all").performScrollTo().performClick()
  ui.onNodeWithTag("provider-search").performTextInput("opencodego")
  ui.onNodeWithTag("provider-opencode-go").performClick()
  ui.onNodeWithTag("config-editor").assertExists();ui.onNodeWithText(ProviderPresets.get("opencode-go").hint).assertExists();assertEquals(0,calls)
 }
 @Test fun tencentRegionIsChosenWithinOneProviderGroup(){
  ui.onNodeWithTag("config-add").performScrollTo().performClick();ui.onNodeWithTag("provider-search").performTextInput("腾讯")
  ui.onNodeWithTag("provider-tokenhub-cn").performClick();ui.onNodeWithTag("provider-region-tokenhub-intl").performClick()
  ui.onNodeWithTag("config-advanced").performScrollTo().performClick();ui.onNodeWithTag("config-url").performScrollTo().assertTextContains("https://tokenhub-intl.tencentmaas.com/v1");assertEquals(0,calls)
 }
 @Test fun embeddingDefaultHidesUnknownProvidersAndKeepsManualCustomRoute(){
  ui.onNodeWithTag("config-add-embedding").performScrollTo().performClick();ui.onNodeWithTag("providers-all").performScrollTo().performClick()
  ui.onNodeWithTag("provider-opencode-go").assertDoesNotExist();ui.onNodeWithTag("provider-ark-cn").assertDoesNotExist()
  ui.onNodeWithTag("provider-search").performTextInput("未知服务")
  ui.onNodeWithText("手动填写").performClick();ui.onNodeWithTag("config-url").performScrollTo().assertExists();ui.onNodeWithTag("config-model").performScrollTo().assertExists();assertEquals(0,calls)
 }
 @Test fun editedGoAddressShowsCompatibilityWarningWithoutSending(){
  ui.onNodeWithTag("config-add").performScrollTo().performClick();ui.onNodeWithTag("provider-search").performTextInput("opencodego");ui.onNodeWithTag("provider-opencode-go").performClick()
  ui.onNodeWithTag("config-advanced").performScrollTo().performClick();ui.onNodeWithTag("config-url").performScrollTo().performTextReplacement("https://example.invalid/v1")
  ui.onNodeWithText(ProviderRequestMetadata.GO_UNVERIFIED_NOTICE).performScrollTo().assertIsDisplayed();ui.onNodeWithTag("config-url").performScrollTo().assertTextContains("https://example.invalid/v1");assertEquals(0,calls)
 }
}
