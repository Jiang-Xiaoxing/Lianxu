package app.lianxu.independent

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Backups cannot cancel or invalidate an unrelated character's synthetic in-flight reply. */
class BackupConcurrencyUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var oldDb:String;private lateinit var oldVault:String
    private lateinit var vm:AppState;private lateinit var vault:ModelVault
    private var response=CompletableDeferred<TextChatResult>();private val calls=AtomicInteger()
    @get:Rule(order=0)val fixture=object:ExternalResource(){
        override fun before(){oldDb=AppServices.databaseName;oldVault=AppServices.vaultNamespace;name="backup-race-${System.nanoTime()}";AppServices.databaseName="$name.db";AppServices.vaultNamespace=name}
        override fun after(){if(::vault.isInitialized)vault.list().forEach{vault.delete(it.id)};if(::vm.isInitialized)vm.processing.store.close();context.deleteDatabase("$name.db");File(context.noBackupFilesDir,name).deleteRecursively();AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldVault}
    }
    @get:Rule(order=1)val ui=createAndroidComposeRule<ComponentActivity>()
    private fun show(){
        ui.runOnUiThread{vm=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory{
            override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T{
                @Suppress("UNCHECKED_CAST")
                return AppState(ui.activity.application,object:TextChatClient{
                    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return response.await()}
                },name) as T
            }
        })[AppState::class.java]}
        ui.waitUntil(30000){vm.ready};vault=ModelVault(context,name,vm.processing.store)
        vault.save(ModelConfig("fake","合成服务","custom","https://example.invalid/v1","synthetic"),"synthetic-race-key")
    }
    @Test fun addingOrReplacingAnotherRolePreservesInflightReply(){
        show()
        for((index,mode) in listOf("ADD","REPLACE").withIndex()){
            response=CompletableDeferred()
            ui.runOnUiThread{vm.open("chat","seed-lin");assertTrue(vm.send("合成并发原话$index"))}
            ui.waitUntil(15000){calls.get()==index+1}
            runBlocking{
                val exported=vm.backup.export("seed-an")
                val pack=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(exported.root,exported.files,it)}.toByteArray().inputStream())
                val plan=vm.backup.preview(pack,mode,if(mode=="REPLACE")"seed-an"else null)
                withContext(Dispatchers.Main){vm.importBackup(plan,mode=="REPLACE",ChatAttemptLease())}
            }
            response.complete(TextChatResult.Success("合成在途回复$index"))
            ui.waitUntil(15000){vm.turns.last{it.friendId=="seed-lin"}.state==TurnState.COMPLETED}
            assertTrue(vm.messages.any{it.friendId=="seed-lin"&&it.text=="合成在途回复$index"})
        }
        assertEquals(2,calls.get())
    }
    @Test fun restoredEndpointWithoutMatchingKeyNeverSendsRequest(){
        show()
        runBlocking{
            val exported=vm.backup.export(null,false)
            val pack=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(exported.root,exported.files,it)}.toByteArray().inputStream())
            vault.save(ModelConfig("fake","另一端点","custom","https://other.invalid/v1","synthetic"),"synthetic-other-key")
            val plan=vm.backup.preview(pack,"REPLACE")
            withContext(Dispatchers.Main){vm.importBackup(plan,true,ChatAttemptLease())}
        }
        ui.runOnUiThread{vm.open("chat",vm.friends.first().id);assertTrue(vm.send("没有密钥的合成消息"))}
        ui.waitUntil(15000){vm.turns.any{it.failure==ChatFailure.MISSING_CONFIG}}
        assertEquals(0,calls.get())
    }
}
