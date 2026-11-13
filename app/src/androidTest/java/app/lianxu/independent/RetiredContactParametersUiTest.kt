package app.lianxu.independent

import android.content.ContentValues
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real chat rendering with synthetic legacy receipts; no network. */
class RetiredContactParametersUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @Test fun legacyFrequencyReceiptDoesNotClaimEffectWhileToneReceiptRemains(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vm=ViewModelProvider(ui.activity)[AppState::class.java]
        ui.waitUntil(15000){vm.ready}
        val store=Store(context,fixture.database)
        try{
            val db=store.writableDatabase
            fun message(text:String)=db.insertOrThrow("messages",null,ContentValues().apply{
                put("friend_id","seed-lin");put("body",text);put("mine",1);put("created",System.currentTimeMillis())
            })
            val old=message("合成旧频率设置原话仍保留")
            val tone=message("合成语气设置原话仍保留")
            RelationStorage.write(db,"seed-lin",RelationHistory(receipts=listOf(
                ParameterReceipt(SourceRef("seed-lin",old,1),RelationTemplate.STEADY.parameters,keys=setOf("dailyMax")),
                ParameterReceipt(SourceRef("seed-lin",tone,1),RelationTemplate.STEADY.parameters,keys=setOf("tone"))
            )))
            ui.runOnIdle{vm.foreground();vm.open("chat","seed-lin")}
            ui.waitUntil(15000){vm.messages.any{it.id==tone}}
            ui.waitForIdle()
            ui.onNodeWithText("合成旧频率设置原话仍保留").assertIsDisplayed()
            ui.onNodeWithText("合成语气设置原话仍保留").assertIsDisplayed()
            ui.onAllNodesWithText("✓ 已改，从现在起生效 ·").assertCountEquals(1)
            ui.onAllNodesWithText("撤销").assertCountEquals(1)
            ui.onAllNodesWithText("每天最多主动发",substring=true).assertCountEquals(0)
            val folder=File(context.getExternalFilesDir(null),"retired-contact-parameters").apply{mkdirs()}
            val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(folder,"legacy-cards.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
            screenshot.recycle()
        }finally{store.close()}
    }
}
