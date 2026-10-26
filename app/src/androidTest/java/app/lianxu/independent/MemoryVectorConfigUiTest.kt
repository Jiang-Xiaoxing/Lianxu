package app.lianxu.independent

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

/** One focused configuration wiring test; ComponentActivity does not open the production app store. */
class MemoryVectorConfigUiTest {
    @get:Rule val ui=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace="embedding-ui-${System.nanoTime()}"
    private val model=ModelConfig("embedding-ui","Synthetic embedding","custom","https://example.invalid/v1","synthetic-embedding",64000,2048)
    private val directoryCalls=AtomicInteger()
    private val models=ViewModelStore()
    private val vault get()=ModelVault(context,namespace)
    private lateinit var state:ModelConfigState
    @Before fun setup(){
        vault.save(model,"synthetic-only")
        ui.runOnIdle{
            state=ModelConfigState(context.applicationContext as Application,object:ModelDirectoryClient{
                override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult {directoryCalls.incrementAndGet();return ConfigResult.Success()}
            },namespace);models.put("embedding-ui",state)
        }
        ui.setContent{LianxuTheme{ModelConfigScreen({},state)}}
        ui.waitUntil(10000){state.configs.size==1}
    }
    @After fun close(){ui.runOnIdle{models.clear()};vault.list().forEach{vault.delete(it.id)}}
    @Test fun existingModelPurposeControlSelectsAndClearsEmbeddingWithoutNetwork(){
        assertEquals("",vault.purposeId(ModelPurpose.EMBEDDING))
        ui.onNodeWithTag("purpose-select-embedding").performScrollTo().performClick()
        ui.onNodeWithTag("purpose-option-embedding-embedding-ui").performClick()
        ui.waitUntil(10000){state.purposes[ModelPurpose.EMBEDDING]==model.id&&!state.busy}
        assertEquals(model.id,vault.purposeId(ModelPurpose.EMBEDDING));assertEquals(0,directoryCalls.get())
        runBlocking{
            val dbName="embedding-ui-${System.nanoTime()}.db";val store=Store(context,dbName)
            try{
                store.add(listOf(Friend("a","Synthetic","Synthetic")),false);val repo=ProcessingRepository(store)
                repo.manualSave("a",null,"garden",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
                var calls=0;val index=MemoryVectorIndex(repo,ModelAccess(vault),object:MemoryEmbeddingClient{
                    override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>{calls++;assertEquals(model.id,credential.config.id);return texts.map{doubleArrayOf(1.0,0.0)}}
                })
                index.index("a");assertEquals(1,index.scores("a","query").size);assertEquals(2,calls)
            }finally{store.close();context.deleteDatabase(dbName)}
        }
        ui.onNodeWithTag("purpose-select-embedding").performScrollTo().performClick();ui.onNodeWithTag("purpose-clear-embedding").performClick()
        ui.waitUntil(10000){state.purposes[ModelPurpose.EMBEDDING].orEmpty().isEmpty()&&!state.busy}
        assertEquals("",vault.purposeId(ModelPurpose.EMBEDDING));assertEquals(0,directoryCalls.get())
    }
}
