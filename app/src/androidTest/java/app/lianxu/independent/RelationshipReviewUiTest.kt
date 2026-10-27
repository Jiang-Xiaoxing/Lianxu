package app.lianxu.independent

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File

class RelationshipReviewUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var vm:AppState;private lateinit var oldName:String;private lateinit var oldNamespace:String
    private lateinit var oldClient:()->TextChatClient;private lateinit var name:String
    private lateinit var vault:ModelVault
    private var slow=false
    private val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        check(key=="synthetic-only");if(slow)delay(30000)
        val j=JSONObject(request.messages.last().content)
        return TextChatResult.Success(JSONObject().put("task_id",j.getString("task_id")).put("character_id",j.getString("character_id")).put("complete",true).put("dimensions",JSONObject().put("TRUST",JSONObject().put("score",49).put("statement","重新核对本批合成原话"))).toString())
    }}
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){oldName=AppServices.databaseName;oldNamespace=AppServices.vaultNamespace;oldClient=AppServices.createTextClient;name="review-ui-${System.nanoTime()}";AppServices.databaseName="$name.db";AppServices.vaultNamespace=name;AppServices.createTextClient={client};seedLegacyTestRoles(instrument.targetContext,"$name.db")}
        override fun after(){if(::vault.isInitialized)vault.delete("r");AppServices.databaseName=oldName;AppServices.vaultNamespace=oldNamespace;AppServices.createTextClient=oldClient;instrument.targetContext.deleteDatabase("$name.db")}
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    private fun show(dark:Boolean=false,big:Boolean=false){
        val seedStore=Store(instrument.targetContext,"$name.db");val seedRepo=ProcessingRepository(seedStore)
        runBlocking{
            val r=RelationshipRepository(seedRepo);r.setEnabled("seed-lin",true,null,true)
            for((task,score) in listOf("first" to 50,"second" to 99)){
                val ledger=ChatLedger(seedStore);val t=ledger.begin("seed-lin","合成原话","unused");check(ledger.finish(t.id,1,"合成回复"))
                val w=r.begin("seed-lin",task,"synthetic","test",true)!!
                r.complete(w,JSONObject().put("task_id",w.id).put("character_id",w.friendId).put("complete",true).put("dimensions",JSONObject().put("TRUST",JSONObject().put("score",score).put("statement","原合成评估"))),{true},ChatAttemptLease())
            }
        }
        seedStore.close()
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity)[AppState::class.java]};ui.waitUntil(30000){vm.ready}
        vault=ModelVault(instrument.targetContext,name);vault.save(ModelConfig("r","合成关系模型","custom","https://example.invalid/v1","synthetic",contextTokens=32000,outputTokens=2048),"synthetic-only");vault.setPurpose(ModelPurpose.RELATIONSHIP,"r")
        ui.setContent{val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,if(big)1.6f else 1f)){LianxuTheme(dark){
            var current by remember{mutableStateOf(RelationshipState())}
            LaunchedEffect(vm.processingRevision){current=vm.relationship.repository.state("seed-lin")}
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())){RelationshipReviewControls(vm,"seed-lin",current,false)}
        }}}
        ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-reassess").fetchSemanticsNodes().any{!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
    }
    private fun state()=runBlocking{vm.relationship.repository.state("seed-lin")}
    private fun generate(){ui.onNodeWithTag("relationship-reassess").performScrollTo().performClick();ui.onNodeWithTag("relationship-reassess-confirm").performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-candidate").fetchSemanticsNodes().isNotEmpty()}}
    private fun shot(label:String){ui.waitForIdle();instrument.uiAutomation.takeScreenshot().let{b->File(instrument.targetContext.filesDir,"review-ui-$label.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun previewShowsSixDimensionsAndOnlyExplicitAdoptionWrites(){show();val before=state();generate();listOf("亲近","信任","熟悉","依赖","安全感","嫉妒").forEach{ui.onNodeWithText("$it：",substring=true).assertExists()};shot("candidate");assertEquals(before,state());ui.onNodeWithTag("relationship-discard").performClick();assertEquals(before,state());generate();ui.onNodeWithTag("relationship-adopt").performClick();ui.waitUntil(10000){state().version==before.version+1};assertEquals(49,state().values.getValue(RelationshipDimension.TRUST).score)}
    @Test fun darkLargeRollbackCancelKeepsStateAndConfirmPreservesDialogue(){show(dark=true,big=true);val before=state();val messages=runBlocking{vm.processing.store.messages()};ui.onNodeWithTag("relationship-history").performScrollTo().performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-rollback-1").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("relationship-rollback-1").performScrollTo().performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-rollback-confirm").fetchSemanticsNodes().isNotEmpty()};shot("rollback-dark-large");ui.onNodeWithText("取消").performClick();assertEquals(before,state());ui.onNodeWithTag("relationship-rollback-1").performScrollTo().performClick();ui.onNodeWithTag("relationship-rollback-confirm").performClick();ui.waitUntil(10000){state().version==before.version+1};assertEquals(before.cursor,state().cursor);assertEquals(50,state().values.getValue(RelationshipDimension.TRUST).score);assertEquals(messages,runBlocking{vm.processing.store.messages()})}
    @Test fun cancelRunningReassessmentLeavesOriginalUntouched(){show();slow=true;val before=state();ui.onNodeWithTag("relationship-reassess").performScrollTo().performClick();ui.onNodeWithTag("relationship-reassess-confirm").performClick();ui.onNodeWithTag("relationship-review-cancel").performScrollTo().performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("relationship-review-cancel").fetchSemanticsNodes().isEmpty()};assertEquals(before,state());ui.onNodeWithTag("relationship-candidate").assertDoesNotExist()}
}
