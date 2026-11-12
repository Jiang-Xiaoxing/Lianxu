package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ProcessingUiTest {
    private val memoryCalls=AtomicInteger()
    private val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
        check(key=="synthetic-key-only")
        syntheticCompanionStage(request)?.let{return it}
        if(config.modelId=="memory"){
            memoryCalls.incrementAndGet();delay(200);val j=JSONObject(request.messages.last().content);val s=SourceDialogue.from(j.getJSONArray("target_dialogue").getJSONObject(0));val existing=j.getJSONArray("existing_memories").getJSONObject(0)
            val op=JSONObject().put("id","one").put("op","UPDATE").put("target",existing.getString("id")).put("category","STATE_FACT").put("about","USER").put("tags",JSONArray()).put("text","喜欢安静的花园与散步").put("core",true).put("date_expression",JSONObject.NULL).put("evidence",JSONArray().put(JSONObject().put("turn",s.source.turnId).put("version",s.source.version).put("side","user").put("quote",s.user.text)))
            return TextChatResult.Success(JSONObject().put("character_id",j.getString("character_id")).put("batch_id",j.getString("batch_id")).put("operations",JSONArray().put(op)).put("operation_count",1).put("complete",true).put("end_marker",j.getString("batch_id")).toString())
        }
        return TextChatResult.Success("【Mock API】可以慢慢说，我会认真听。")
    }}
    @get:Rule(order=0)val mock=MockChatFixture({client})
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm():AppState {lateinit var value:AppState;ui.activityRule.scenario.onActivity{value=ViewModelProvider(it)[AppState::class.java]};return value}
    private fun hideIme(){ui.activityRule.scenario.onActivity{a->(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(a.window.decorView.windowToken,0);a.window.decorView.clearFocus()};ui.waitForIdle()}
    private fun shot(name:String){hideIme();android.os.SystemClock.sleep(350);val dir=File(context.getExternalFilesDir(null),"evidence-live-chat/processing-ui").apply{mkdirs()};val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun roleMemoryConflictHistoryRemainsAvailable(){
        ui.waitUntil(15000){vm().ready}
        val vault=ModelVault(context,mock.namespace)
        for(p in listOf(ModelPurpose.MEMORY)){val c=ModelConfig(p.key,p.title,"custom","https://example.invalid/v1",p.key,contextTokens=32000,outputTokens=4096);vault.save(c,"synthetic-key-only");vault.setPurpose(p,c.id)}
        ui.onNodeWithTag("add-menu").performClick();ui.onNodeWithText("创建角色").performClick();ui.onNodeWithTag("friend-name").performTextInput("合成·运行验收");ui.onNodeWithTag("friend-bio").performTextInput("喜欢植物与日常聊天，本机合成测试。")
        ui.onNodeWithTag("create-friend").performScrollTo().performClick();ui.waitUntil(10000){vm().page=="chat"};val id=vm().activeId
        ui.onNodeWithTag("message-input").performTextInput("我喜欢安静的花园，也喜欢散步。");ui.onNodeWithTag("send-message").performClick();ui.waitUntil(10000){vm().turns.any{it.friendId==id&&it.state==TurnState.COMPLETED}}
        shot("05-real-chat-mock-only")
        ui.onNodeWithTag("chat-role-detail").performClick();ui.onNodeWithTag("role-processing-open").performScrollTo().performClick()
        ui.onNodeWithText("我写的").performClick();ui.onNodeWithTag("memory-add").performClick();ui.onNodeWithTag("memory-text").performTextInput("喜欢安静的花园");hideIme();ui.onNodeWithTag("memory-core").performClick();ui.onNodeWithTag("memory-save").performClick()
        ui.waitUntil(10000){runBlocking{vm().processing.memories(id).size==1}};ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("memory-list").performScrollToNode(hasText("喜欢安静的花园"));ui.onNodeWithText("喜欢安静的花园").assertExists();ui.onNodeWithText("更多",substring=true).performClick();ui.onNodeWithTag("memory-maintenance").performClick();ui.onNodeWithTag("memory-retry").performScrollTo().performTouchInput{doubleClick(delayMillis=40)}
        ui.waitUntil(10000){runBlocking{vm().processing.candidates(id).size==1}};assertEquals(1,memoryCalls.get())
        ui.onNodeWithTag("memory-task-status").performScrollTo();shot("03-memory-status-real-app")
        ui.onNodeWithTag("back").performClick();ui.onNodeWithText("更多",substring=true).performClick();ui.onNodeWithTag("memory-candidates").performClick();ui.onNodeWithText("采用并人工确认").performScrollTo();shot("03b-memory-conflict-real-app")
        ui.onNodeWithText("保留原内容").performClick();ui.waitUntil(10000){runBlocking{vm().processing.candidates(id).isEmpty()}}
        ui.onNodeWithTag("back").performClick()
        ui.onNodeWithTag("processing-relationship").assertDoesNotExist()
        runBlocking{assertEquals(1,vm().processing.memories(id).size);assertTrue(vm().processing.memories("seed-an").isEmpty())}
    }
}
