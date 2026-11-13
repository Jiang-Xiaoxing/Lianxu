package app.lianxu.independent

import android.content.ContentValues
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated persisted synthetic messages; uses the real Chat and visibility/read path. */
class LatestReadMarkerUiTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm()=ViewModelProvider(ui.activity)[AppState::class.java]
    private fun seed(text:String,mine:Boolean=false,receipt:Boolean=!mine,role:String="seed-lin"):Long {
        val store=Store(context,fixture.database)
        try {
            val db=store.writableDatabase
            val id=db.insertOrThrow("messages",null,ContentValues().apply{
                put("friend_id",role);put("body",text);put("mine",if(mine)1 else 0);put("created",System.currentTimeMillis())
            })
            if(receipt)db.insertOrThrow("companion_receipts",null,ContentValues().apply{
                put("message_id",id);put("role",role);put("delivery","chat")
            })
            return id
        } finally { store.close() }
    }
    private fun receipts():List<CompanionReceipt> = runBlocking {
        val store=Store(context,fixture.database)
        try { CompanionRepository(store).receipts() } finally { store.close() }
    }
    private fun refresh(id:Long) {
        ui.runOnIdle{vm().foreground()}
        ui.waitUntil(15000){vm().messages.any{it.id==id}&&vm().companionReceipts.size==receipts().size}
    }
    private fun open(role:String="seed-lin") { ui.runOnIdle{vm().open("chat",role)};ui.waitForIdle() }
    private fun labels()=ui.onAllNodes(SemanticsMatcher("read labels"){
        it.config.getOrElse(SemanticsProperties.TestTag){""}.startsWith("read-")
    },useUnmergedTree=true)
    private fun shot(name:String) {
        ui.waitForIdle()
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(context.getExternalFilesDir(null),"latest-read-marker-evidence").apply{mkdirs()}
        File(dir,name).outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun onlyLatestReplyIsLabelledWhileEveryVisibleReplyStillBecomesRead() {
        ui.waitUntil(15000){vm().ready}
        val older=seed("今天的风还挺舒服的。")
        val latest=seed("我刚走到楼下。\n\n等你忙完再聊。")
        val user=seed("好，我一会儿就来。",mine=true)
        refresh(user);open()
        ui.waitUntil(10000){receipts().filter{it.messageId in setOf(older,latest)}.all{it.readAt!=null}}
        labels().assertCountEquals(1)
        ui.onNodeWithTag("read-$latest").assertTextEquals("已读")
        ui.onNodeWithTag("read-$older").assertDoesNotExist()
        ui.onNodeWithTag("read-$user").assertDoesNotExist()
        ui.onNodeWithTag("bubble-$latest-0",useUnmergedTree=true).assertIsDisplayed()
        val lastBubble=ui.onNodeWithTag("bubble-$latest-1",useUnmergedTree=true)
        lastBubble.assertIsDisplayed()
        assertTrue(ui.onNodeWithTag("read-$latest").getUnclippedBoundsInRoot().top>=lastBubble.getUnclippedBoundsInRoot().bottom)
        shot("01-latest-split-reply-read.png")

        // The existing attachment sheet pauses visibility marking, so a newly persisted
        // reply is deterministically unread until the sheet closes (no clock tricks).
        val before=receipts().associate{it.messageId to it.readAt}
        ui.onNodeWithTag("chat-attach").performClick()
        val next=seed("那我等你。")
        refresh(next)
        labels().assertCountEquals(1)
        ui.onNodeWithTag("read-$next").assertTextEquals("未读")
        ui.onNodeWithTag("read-$latest").assertDoesNotExist()
        assertNull(receipts().single{it.messageId==next}.readAt)
        ui.onNodeWithTag("close-attachment-info").performClick()
        ui.waitUntil(10000){receipts().single{it.messageId==next}.readAt!=null}
        ui.waitUntil(10000){ui.onAllNodes(hasTestTag("read-$next") and hasText("已读")).fetchSemanticsNodes().size==1}
        before.forEach{(id,at)->assertEquals(at,receipts().single{it.messageId==id}.readAt)}
        labels().assertCountEquals(1)
        shot("02-new-reply-replaces-marker.png")
    }
    @Test fun latestReplyWithoutReceiptDoesNotBorrowOlderStatus() {
        ui.waitUntil(15000){vm().ready}
        val older=seed("这条有回执。")
        val latest=seed("这条没有回执。",receipt=false)
        refresh(latest);open()
        ui.onNodeWithTag("message-$latest").assertIsDisplayed()
        labels().assertCountEquals(0)
        ui.waitUntil(10000){receipts().single{it.messageId==older}.readAt!=null}
        assertFalse(receipts().any{it.messageId==latest})
    }
    @Test fun userOnlyAndEmptyRolesHaveNoMarkerOrBorrowedReceipt() {
        ui.waitUntil(15000){vm().ready}
        val store=Store(context,fixture.database)
        try { runBlocking{store.add(listOf(Friend("user-only","只有用户消息","合成测试"),Friend("empty","空聊天","合成测试")),false)} } finally { store.close() }
        val other=seed("别的角色消息。")
        val user=seed("只有我说的话。",mine=true,role="user-only")
        refresh(user);open("user-only")
        ui.onNodeWithTag("message-$user").assertIsDisplayed()
        labels().assertCountEquals(0)
        assertNull(receipts().single{it.messageId==other}.readAt)
        open("empty");labels().assertCountEquals(0)
        assertEquals(1,receipts().size)
    }
}
