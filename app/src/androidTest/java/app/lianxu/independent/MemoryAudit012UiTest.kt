package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MemoryAudit012UiTest {
    private val calls=AtomicInteger()
    private val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
        calls.incrementAndGet();val j=JSONObject(request.messages.last().content);assertEquals(1,j.getJSONArray("already_saved_operations").length())
        return TextChatResult.Success(JSONObject().put("batch_id",j.getString("batch_id")).put("character_id",j.getString("character_id")).put("operations",JSONArray()).put("operation_count",1).put("complete",true).put("end_marker",j.getString("batch_id")).toString())
    }}
    @get:Rule(order=0) val mock=MockChatFixture({client})
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun app():AppState{lateinit var v:AppState;ui.activityRule.scenario.onActivity{v=ViewModelProvider(it)[AppState::class.java]};return v}
    private fun shot(name:String){ui.waitForIdle();android.os.SystemClock.sleep(350);val dir=File(context.getExternalFilesDir(null),"evidence-memory-012").apply{mkdirs()};val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun explicitBatchReviewInvalidCandidateAndCoreChoice(){
        ui.waitUntil(15000){app().ready};val v=app();val repo=v.processing
        val vault=ModelVault(context,mock.namespace);vault.setPurpose(ModelPurpose.MEMORY,"fixture")
        val credential=runBlocking{v.modelAccess.credential(ModelPurpose.MEMORY)!!};lateinit var source:SourceDialogue;lateinit var work:MemoryWork;var memoryId=""
        runBlocking{
            repo.store.add(listOf(Friend("audit-ui","合成·修复验收","仅本机mock与合成数据。")),false);repo.savePolicy("audit-ui",ProcessingPolicy(coreGoal=100))
            val ledger=ChatLedger(repo.store);val t=ledger.begin("audit-ui","我喜欢合成花园。","fixture");ledger.finish(t.id,t.attempt,"合成回复")
            source=repo.sources("audit-ui").single();val b=MemoryBatchRepository(repo);work=b.prepare("audit-ui",listOf(source),credential.stamp);b.beginAttempt(work,"test")
            val op=JSONObject().put("id","one").put("op","ADD").put("target","new:one").put("category","STATE_FACT").put("about","USER").put("tags",JSONArray()).put("text","模型保存的花园记忆").put("core",true).put("evidence",JSONArray().put(JSONObject().put("turn",source.source.turnId).put("version",1).put("side","user").put("quote",source.user.text)))
            memoryId=b.apply(work,op);repo.manualSave("audit-ui",memoryId,"人工修正：喜欢安静的花园。",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER)
            try{b.complete(work,setOf("one"),1,work.id);fail()}catch(e:ProcessingRejected){assertEquals("BATCH_REVIEW_REQUIRED",e.code)};b.fail(work,"BATCH_REVIEW_REQUIRED");v.refreshProcessing()
        }
        ui.runOnIdle{v.open("processing-role","audit-ui")}
        ui.onNodeWithTag("memory-maintenance").performClick()
        ui.onNodeWithTag("batch-keep-$memoryId").performScrollTo();shot("01-batch-review-real-app")
        ui.onNodeWithTag("batch-keep-$memoryId").performClick();ui.waitUntil(10000){runBlocking{MemoryBatchRepository(repo).reviews("audit-ui").single().acknowledged}}
        assertEquals(0,calls.get());assertEquals(0L,runBlocking{repo.cursor("audit-ui")});ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("memory-retry").performScrollTo().performClick();ui.waitUntil(10000){runBlocking{repo.memoryStatus("audit-ui").state=="COMMITTED"}}
        assertEquals(1,calls.get());ui.onNodeWithTag("core-budget-warning").performScrollTo();shot("02-core-over-goal-real-app")
        ui.onNodeWithTag("core-demote-$memoryId").performScrollTo().performClick();ui.onNodeWithText("取消",useUnmergedTree=true).performClick();assertTrue(runBlocking{repo.memories("audit-ui").single().core})
        ui.onNodeWithTag("core-demote-$memoryId").performClick();ui.onNodeWithTag("core-demote-confirm").performClick();ui.waitUntil(10000){runBlocking{!repo.memories("audit-ui").single().core}}
        runBlocking{
            val p=StoredMemory("proposal","audit-ui",MemoryCategory.USER_PROFILE,"来源撤回前的旧候选",evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
            repo.tx{repo.candidate(it,"audit-ui",null,p,"合成旧候选","withdrawn-ui")};val changes=SourceChanges(repo);val impact=changes.preview("audit-ui",source.source.turnId,"user","",true);assertEquals(1,impact.candidates.size);changes.confirm(impact);v.refreshProcessing()
        }
        ui.onNodeWithTag("back").performClick();ui.onNodeWithTag("memory-candidates").performClick();ui.onNodeWithTag("candidate-accept-withdrawn-ui").performScrollTo().assertIsNotEnabled();shot("03-invalid-candidate-real-app")
        val m=runBlocking{repo.memories("audit-ui").single()};assertTrue(m.manual&&m.locked);assertEquals("人工修正：喜欢安静的花园。",m.text);assertEquals(1,calls.get())
    }
}
