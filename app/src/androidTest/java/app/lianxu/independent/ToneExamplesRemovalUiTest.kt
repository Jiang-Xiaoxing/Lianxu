package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Existing legacy values and completed rounds cannot bring back the removed invitation/picker. */
class ToneExamplesRemovalUiTest {
    private val calls=AtomicInteger()
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{
        override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
            // Completed seeded turns may trigger private review; that is unrelated to the retired
            // tone-example generator. Reject/count every other request, including any old generator.
            if(request.messages.any{"[多轮后台回顾]" in it.content}){
                val rows=org.json.JSONArray(request.messages.last{it.role=="user"}.content)
                val records=org.json.JSONArray()
                for(i in 0 until rows.length())records.put(org.json.JSONObject().put("source",rows.getJSONObject(i).getLong("source")).put("record",org.json.JSONObject.NULL))
                return TextChatResult.Success(org.json.JSONObject().put("records",records).toString())
            }
            calls.incrementAndGet();return TextChatResult.Failure(ChatFailure.NETWORK)
        }
    }})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    @Test fun oldOfferedStateHasNoUiOrCallsAndKeepsManualExpression(){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        val id="seed-lin"
        runBlocking {
            val store=vm.processing.store
            store.updateRolePrompts(id){it.copy(characterDefinition="合成角色原设定",expression="合成自填说话方式",opening="合成自填开场白",examples=listOf(DialogueExample("旧参考问题","旧参考回答")),examplesEnabled=true)}
            store.setPreference("role-examples:$id","""{"state":"OFFERED","optedIn":true,"selected":[],"attempt":"","reason":""}""")
            repeat(20){val ledger=ChatLedger(store);val turn=ledger.begin(id,"合成第${it}轮原话","fixture");assertTrue(ledger.finish(turn.id,1,"合成第${it}轮回复"))}
            vm.refreshProcessing()
        }
        ui.runOnIdle{vm.open("role-prompts",id)}
        ui.waitUntil(5000){ui.onAllNodesWithText("合成自填说话方式").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("role-definition").assertTextContains("合成角色原设定")
        ui.onNodeWithTag("role-expression").assertTextContains("合成自填说话方式")
        ui.onNodeWithTag("role-opening").performScrollTo().assertTextContains("合成自填开场白")
        ui.onNodeWithTag("role-examples").assertDoesNotExist()
        ui.onNodeWithText("旧聊天语气参考").assertDoesNotExist()
        ui.onNodeWithTag("role-advanced").performScrollTo().assertIsDisplayed()
        shot("role-prompts-after-removal")
        ui.runOnIdle{vm.open("chat",id)}
        ui.onNodeWithTag("examples-invitation").assertDoesNotExist()
        ui.onNodeWithText("已经聊满 20 轮",substring=true).assertDoesNotExist()
        assertEquals(0,calls.get())
        runBlocking{
            assertTrue(vm.processing.store.preference("role-examples:$id","").contains("OFFERED"))
            assertEquals("合成自填说话方式",vm.rolePrompts(id).expression)
        }
    }
    private fun shot(name:String){
        ui.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"evidence-tone-removal").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
