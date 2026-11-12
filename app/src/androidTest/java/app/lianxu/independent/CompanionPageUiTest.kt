package app.lianxu.independent

import android.content.ContentValues
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 只用合成数据库：聊天顶栏没有「陪伴」，资料页进「陪伴与消息」，两个开关带 ⓘ，真失败过才有「上次没发出去」卡。截图放在应用外部目录 companion-page-shots。 */
class CompanionPageUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm()=ViewModelProvider(ui.activity)[AppState::class.java]
    private val raw="HTTP 429 Too Many Requests\n"+"""{\n  "error": {\n    "api_key": "sk-****************abcd",\n    "type": "rate_limit_exceeded",\n    "message": "Too many requests. Please try again later.",\n    "code": "too_many_requests",\n    "retry_after_seconds": 60,\n    "request_id": "synthetic-request-429"\n  }\n}""".replace("\\n","\n")
    private fun shot(name:String){
        ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;automation.waitForIdle(100,3000);Thread.sleep(400)
        val folder=File(context.getExternalFilesDir(null),"companion-page-shots").apply{mkdirs()}
        automation.takeScreenshot().let{b->File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
    private fun seedFailure(){
        val store=Store(context,fixture.database)
        try{
            val db=store.writableDatabase
            val source=db.rawQuery("SELECT id FROM messages WHERE friend_id='seed-lin' ORDER BY id LIMIT 1",null).use{it.moveToFirst();it.getLong(0)}
            db.execSQL("INSERT OR IGNORE INTO companion_state(role) VALUES('seed-lin')")
            db.execSQL("UPDATE companion_state SET policy='enabled' WHERE role='seed-lin'")
            val version=db.rawQuery("SELECT policy_version FROM companion_state WHERE role='seed-lin'",null).use{it.moveToFirst();it.getLong(0)}
            val at=LocalDate.now().minusDays(1).atTime(LocalTime.of(23,14)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            db.insert("companion_opportunities",null,ContentValues().apply{
                put("id","synthetic-failed");put("role","seed-lin");put("source_id",source);put("revision",0);put("epoch",0);put("policy_version",version)
                put("not_before",at);put("expires_at",at+3_600_000);put("reason","合成回访");put("state","failed");put("fail_kind",ContactFailureKind.MODEL_RESPONSE.name);put("fail_at",at)
            })
            db.insert("preferences",null,ContentValues().apply{put("key",ContactFailures.errorKey("synthetic-failed"));put("value",raw)})
        }finally{store.close()}
    }
    @Before fun ready(){ui.waitUntil(15000){vm().ready}}
    @Test fun chatBarProfileRowSwitchHintsAndLastFailure(){
        seedFailure()
        ui.runOnIdle{vm().open("chat","seed-lin")}
        ui.onNodeWithTag("chat-role-detail").assertExists();assertTrue(ui.onAllNodesWithText("陪伴").fetchSemanticsNodes().isEmpty())
        shot("1-chatbar")
        ui.onNodeWithTag("chat-role-detail").performClick()
        ui.onNodeWithTag("role-avatar").assertHasClickAction();assertTrue(ui.onAllNodesWithText("给她选张头像",substring=true).fetchSemanticsNodes().isEmpty())
        shot("0-profile-header")
        ui.onNodeWithTag("role-companion-open").performScrollTo().assertTextContains("主动联系与通知",substring=true)
        shot("2-profile")
        ui.onNodeWithTag("role-companion-open").performClick()
        ui.onNodeWithTag("companion-page").assertExists()
        ui.waitUntil(10000){ui.onAllNodesWithTag("companion-last-failure").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("companion-last-failure-summary",useUnmergedTree=true).assertTextEquals("她想联系你，但没发出去。服务商返回 HTTP 429。")
        assertTrue(ui.onAllNodesWithText("网络连不上",substring=true).fetchSemanticsNodes().isEmpty())
        assertTrue(ui.onAllNodesWithText("不保证准时",substring=true).fetchSemanticsNodes().isEmpty())
        shot("3-companion")
        ui.onNodeWithTag("companion-enabled-info").performClick()
        ui.onNodeWithTag("companion-enabled-hint",useUnmergedTree=true).assertTextEquals(CompanionHints.CONTACT)
        shot("4-hint")
        Espresso_back()
        ui.onNodeWithTag("companion-last-failure").performClick()
        ui.onNodeWithTag("turn-failure-detail").assertExists()
        ui.onNodeWithText("服务返回的原文：",substring=true).assertExists()
        shot("5-dialog")
    }
    private fun Espresso_back(){
        // 点 ⓘ 外面让解释气泡收起。
        ui.onNodeWithTag("companion-enabled-hint").assertExists();InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close();ui.waitUntil(5000){ui.onAllNodesWithTag("companion-enabled-hint").fetchSemanticsNodes().isEmpty()}
    }
}
