package app.lianxu.independent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RelationshipUiAndroidTest {
    @get:Rule(order=0) val fixture=MockChatFixture({object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No network")}})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:AppState
    @Test fun unverifiedLegacyShowsPausedStatusAndPreservesTheOldState(){
        seed()
        val before=runBlocking{vm.companion.relationship("seed-lin").state}
        runBlocking{vm.processing.tx{db->val saved=requireNotNull(RelationFeeling.read(db,"seed-lin"));RelationFeeling.write(db,"seed-lin",saved.copy(attributionIncomplete=true))}}
        ui.runOnIdle{vm.open("role-relationship","seed-lin")}
        ui.waitUntil(10000){ui.onAllNodesWithText(RelationshipPrompt.UNVERIFIED_STATUS).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(RelationshipPrompt.UNVERIFIED_STATUS).assertIsDisplayed()
        ui.onNodeWithText("旧分值仅作历史查看，不提供给模型").assertIsDisplayed()
        runBlocking{assertEquals(before.trust,vm.companion.relationship("seed-lin").state.trust,1e-6)}
        shot("unverified-legacy-paused-status")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText(RelationshipPrompt.UNVERIFIED_STATUS).assertIsDisplayed()
    }
    private fun shot(name:String){
        ui.waitForIdle();android.os.SystemClock.sleep(700)
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val dir=java.io.File(i.targetContext.getExternalFilesDir(null),"evidence-ui1").apply{mkdirs()}
        val bitmap=i.uiAutomation.takeScreenshot()
        java.io.File(dir,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun seed(){
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(15000){vm.ready}
        runBlocking{
            val core=vm.companion;val ledger=ChatLedger(vm.processing.store)
            val turn=ledger.begin("seed-lin","合成失约原话","synthetic");val ref=core.sourceFor(turn);val snapshot=core.snapshot("seed-lin");val now=System.currentTimeMillis()
            val p=ExperienceProposal(relationInput=RelationInput(ref,now,now/RelationshipProjector.DAY,"关于关系",v=.9),relationAttempted=true,timingReferenceAt=now,parameterProposal=RelationParameterProposal(ref,mapOf("tone" to "温柔简洁"),"说得温柔简洁些",now))
            assertTrue(core.accept(turn,p,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"))
            ProcessingRepository(vm.processing.store).tx{core.finish(it,turn,snapshot,p)}
            vm.refreshProcessing()
        }
    }
    private fun enrich()=runBlocking {
        val core=vm.companion;val ledger=ChatLedger(vm.processing.store)
        suspend fun row(text:String,make:(SourceRef,Long)->RelationInput):SourceRef {
            val turn=ledger.begin("seed-lin",text,"synthetic");val ref=core.sourceFor(turn);val snapshot=core.snapshot("seed-lin");val now=System.currentTimeMillis()
            val p=ExperienceProposal(relationInput=make(ref,now),relationAttempted=true,timingReferenceAt=now,relationIssues=listOf(RelationIssue("合成字段","合成未计入")))
            assertTrue(core.accept(turn,p,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"));ProcessingRepository(vm.processing.store).tx{core.finish(it,turn,snapshot,p)};return ref
        }
        val old=row("那次没赶上电影"){ref,at->RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",v=.6)}
        row("那次其实是临时加班，解释清楚了"){ref,at->RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",explains=old,formatVersion=2,releaseKind="有原因",explanationPolicy=1)}
        row("周末一起去公园"){ref,at->RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",promise=true,p=.3)}
        vm.refreshProcessing()
    }
    private fun await(text:String){ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    @Test fun relationshipRendersPendingBigAndFooter(){seed();enrich();ui.runOnIdle{vm.open("detail","seed-lin")};ui.onNodeWithTag("role-relationship-open").performScrollTo();shot("detail-entry");ui.runOnIdle{vm.open("role-relationship","seed-lin")};ui.waitUntil(10000){ui.onAllNodesWithText("心结 ·",substring=true).fetchSemanticsNodes().isNotEmpty()};shot("relationship-1");ui.onNodeWithText("心结 ·",substring=true).performClick();ui.onNodeWithText("你说：「合成失约原话」").assertIsDisplayed();assertTrue(ui.onAllNodesWithText("她一直记着",substring=true).fetchSemanticsNodes().isEmpty());assertTrue(ui.onAllNodesWithText("跟她说开就会好一些",substring=true).fetchSemanticsNodes().isEmpty());shot("relationship-ledger");ui.onNodeWithText("数值帮助她理解当前感受，不规定表达").assertIsDisplayed();shot("relationship-2")}
    @Test fun confirmReenterAndUndoOnlyOnce(){
        seed();val old=runBlocking{vm.companion.relationship("seed-lin").parameters}
        ui.runOnIdle{vm.open("chat","seed-lin")};await("就这样");shot("card-pending");ui.onNodeWithText("就这样").performClick();await("✓ 已改，从现在起生效 ·")
        runBlocking{assertEquals(old.copy(tone="温柔简洁"),vm.companion.relationship("seed-lin").parameters)}
        ui.runOnIdle{vm.open("detail","seed-lin")};ui.waitForIdle();ui.runOnIdle{vm.open("chat","seed-lin")};await("✓ 已改，从现在起生效 ·")
        shot("card-confirmed");ui.onNodeWithText("撤销").performClick();await("已撤销")
        runBlocking{
            val receipt=vm.companion.relationshipParameterReceipts("seed-lin").single();assertTrue(receipt.undone)
            val history=vm.companion.relationshipChangeHistory("seed-lin");assertEquals(2,history.size);assertEquals(history.first().new,history.last().old);assertEquals(old,history.last().new)
            vm.companion.changeRelationshipParameters("seed-lin",old,"合成",receipt.source)
            assertEquals(history,vm.companion.relationshipChangeHistory("seed-lin"))
        }
    }
    @Test fun rejectLeavesParametersUnchanged(){seed();val old=runBlocking{vm.companion.relationship("seed-lin").parameters};ui.runOnIdle{vm.open("chat","seed-lin")};await("不用改");ui.onNodeWithText("不用改").performClick();ui.waitUntil(10000){ui.onAllNodesWithText("不用改").fetchSemanticsNodes().isEmpty()};runBlocking{assertEquals(old,vm.companion.relationship("seed-lin").parameters);assertTrue(vm.companion.relationshipParameterReceipts("seed-lin").isEmpty())}}
}
