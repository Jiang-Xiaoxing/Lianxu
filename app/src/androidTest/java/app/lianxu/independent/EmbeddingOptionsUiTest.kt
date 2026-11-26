package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class EmbeddingOptionsUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace="embedding-options-${System.nanoTime()}"
    private val vault get()=ModelVault(context,namespace)
    private lateinit var state:ModelConfigState
    private val calls=AtomicInteger()
    private val vector=ModelConfig("options-vector","合成已识别向量","openai",ProviderPresets.get("openai").base,"text-embedding-3-small")
    private val unknown=ModelConfig("options-unknown","合成未知配置","custom","https://example.invalid/v1","synthetic-model")
    @Before fun setup(){
        ui.activityRule.scenario.onActivity{activity->state=ViewModelProvider(activity,object:ViewModelProvider.Factory{
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=ModelConfigState(activity.application,object:ModelDirectoryClient{
                override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult {calls.incrementAndGet();error("No network in option visibility test")}
            },namespace) as T
        })[ModelConfigState::class.java]}
        ui.waitUntil(15000){ui.onAllNodesWithTag("tab-2").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("tab-2").performClick();ui.onNodeWithTag("settings-purposes").performClick()
    }
    @After fun close(){ui.runOnIdle{state.clearResult()};vault.list().forEach{vault.delete(it.id)};assertEquals(0,calls.get())}
    private fun seed(configs:List<ModelConfig>,selected:String=""){
        configs.forEach{vault.save(it,"synthetic-only")};vault.setPurpose(ModelPurpose.EMBEDDING,selected)
        ui.runOnIdle{state.refresh()};ui.waitUntil(10000){state.configs.size==configs.distinctBy{it.id}.size&&state.purposes[ModelPurpose.EMBEDDING].orEmpty()==selected}
    }
    private fun menu(){ui.onNodeWithTag("purpose-select-embedding").performScrollTo().performClick()}
    @Test fun emptyHasNoOtherSavedEntry(){menu();ui.onNodeWithTag("purpose-unknown-embedding").assertDoesNotExist();ui.onNodeWithTag("purpose-clear-embedding").assertIsDisplayed();shot("empty")}
    @Test fun onlyKnownVectorStaysSelectableWithoutOtherEntry(){
        seed(listOf(vector));menu();ui.onNodeWithTag("purpose-unknown-embedding").assertDoesNotExist()
        shot("known-vector")
        ui.onNodeWithTag("purpose-option-embedding-${vector.id}").performClick()
        ui.waitUntil(10000){!state.busy&&vault.purposeId(ModelPurpose.EMBEDDING)==vector.id}
    }
    @Test fun unknownAppearsOnlyAfterOtherEntryAndCanBeSelected(){
        seed(listOf(unknown));menu();ui.onNodeWithTag("purpose-option-embedding-${unknown.id}").assertDoesNotExist()
        shot("unknown-hidden")
        ui.onNodeWithTag("purpose-unknown-embedding").performClick()
        ui.onNodeWithTag("purpose-unknown-embedding").assertDoesNotExist()
        shot("unknown-shown")
        ui.onNodeWithTag("purpose-option-embedding-${unknown.id}").performClick()
        ui.waitUntil(10000){!state.busy&&vault.purposeId(ModelPurpose.EMBEDDING)==unknown.id}
    }
    @Test fun selectedUnknownRemainsUsableWithoutAdvertisingItAgain(){
        seed(listOf(unknown),unknown.id);menu()
        ui.onNodeWithTag("purpose-unknown-embedding").assertDoesNotExist()
        ui.onNodeWithTag("purpose-option-embedding-${unknown.id}").assertIsDisplayed()
        shot("selected-unknown")
        ui.onNodeWithTag("purpose-clear-embedding").performClick()
        ui.waitUntil(10000){!state.busy&&vault.purposeId(ModelPurpose.EMBEDDING).isEmpty()}
        menu();ui.onNodeWithTag("purpose-unknown-embedding").assertIsDisplayed()
    }
    @Test fun additionalUnknownAndCurrentSelectionAreListedOnce(){
        val second=unknown.copy(id="options-second",name="合成第二未知配置")
        seed(listOf(vector,unknown,unknown,second),unknown.id);menu()
        ui.onNodeWithTag("purpose-unknown-embedding").performClick()
        listOf(vector,unknown,second).forEach{ui.onAllNodesWithTag("purpose-option-embedding-${it.id}").assertCountEquals(1)}
        shot("unique-candidates")
        ui.onNodeWithTag("purpose-option-embedding-${second.id}").performClick()
        ui.waitUntil(10000){!state.busy&&vault.purposeId(ModelPurpose.EMBEDDING)==second.id}
    }
    private fun shot(name:String){
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle();android.os.SystemClock.sleep(200)
        val dir=File(context.getExternalFilesDir(null),"evidence-updates-ui").apply{mkdirs()}
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"vector-$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
