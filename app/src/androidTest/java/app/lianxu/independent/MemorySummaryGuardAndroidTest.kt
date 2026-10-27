package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Synthetic isolated databases and clients; never calls a provider. */
class MemorySummaryGuardAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var scope:CoroutineScope
    @Before fun setup()=runBlocking {
        name="summary-guard-${System.nanoTime()}";store=Store(context,"$name.db");repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","A","Synthetic"),Friend("b","B","Synthetic")),false)
        val l=ChatLedger(store);val t=l.begin("a","synthetic coffee fact","synthetic");assertTrue(l.finish(t.id,t.attempt,"synthetic reply"))
        vault=ModelVault(context,name);val c=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","fake",64000,2048)
        vault.save(c,"synthetic-only");vault.setPurpose(ModelPurpose.MEMORY,c.id);scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    }
    @After fun close()=runBlocking {scope.cancel();vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase("$name.db");Unit}
    private suspend fun blocked(action:suspend()->Unit){assertEquals("MEMORY_SUMMARIZING",(runCatching{action()}.exceptionOrNull() as ProcessingRejected).code)}
    private fun receipt(request:TextChatRequest,add:Boolean=false):TextChatResult {
        val d=JSONObject(request.messages.last().content);val ops=JSONArray()
        if(add)ops.put(JSONObject().put("id","one").put("op","ADD").put("target","N1").put("category","STATE_FACT").put("about","USER").put("text","synthetic coffee fact").put("evidence",JSONArray().put("S1")))
        return TextChatResult.Success(JSONObject().put("batch_id",d.getString("batch_id")).put("character_id","a").put("operations",ops).put("operation_count",ops.length()).put("complete",true).put("end_marker",d.getString("batch_id")).toString())
    }
    private fun engine(client:TextChatClient,timeout:Long=300000)=MemoryEngine(scope,repo,ModelAccess(vault),client,{},timeout)
    @Test fun repositoriesRejectAllManualWritesWithoutAUiAndOtherRoleStaysEditable()=runBlocking {
        val m=repo.manualSave("a",null,"manual",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER)
        val source=repo.sources("a").single();val impact=SourceChanges(repo).preview("a",source.source.turnId,"user","edited",false)
        val backup=BackupRepository(store,vault);val deletion=backup.previewRoleDeletion("a")
        val t=store.memorySummary.begin("a",ChatAttemptLease(),10000){}!!
        try{
            val otherRepo=ProcessingRepository(store)
            blocked{otherRepo.manualSave("a",null,"new",m.category,false,true,MemoryAbout.USER)}
            blocked{otherRepo.manualSave("a",m.id,"edited",m.category,true,true,MemoryAbout.USER,life=MemoryLife.RETRACTED)}
            blocked{otherRepo.setLock("a",m.id,false)};blocked{otherRepo.restore("a",m.id,1)}
            blocked{otherRepo.chooseCore("a",m.id,m.version,false)}
            blocked{otherRepo.resolveCandidate("a","synthetic",false)};blocked{otherRepo.resolveCandidate("a","synthetic",true)}
            blocked{MemoryBatchRepository(otherRepo).keepCurrent("a","synthetic",m.id,m.version)}
            blocked{SourceChanges(otherRepo).confirm(impact)};blocked{backup.deleteRole(deletion,true)}
            blocked{otherRepo.savePolicy("a",ProcessingPolicy(coreGoal=999))}
            otherRepo.manualSave("b",null,"independent",m.category,false,true,MemoryAbout.USER)
            assertEquals(m,repo.memories("a").single());assertEquals(1,repo.memories("b").size)
        }finally{store.memorySummary.finish(t)}
        repo.setLock("a",m.id,false)
    }
    @Test fun successfulSummaryCanWriteWhileManualWritesAreBlockedAndReleasesAfterwards()=runBlocking {
        val c=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            blocked{repo.manualSave("a",null,"manual",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)}
            return receipt(request,true)
        }}
        assertTrue(engine(c).ensureRoom("a",forceOne=true).success);assertEquals(1,repo.memories("a").size)
        repo.manualSave("a",null,"after",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER);Unit
    }
    @Test fun failureRestoresManualEditing()=runBlocking {
        val c=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=TextChatResult.Failure(ChatFailure.UNAUTHORIZED)}
        assertFalse(engine(c).ensureRoom("a",forceOne=true).success);store.memorySummary.requireEditable("a")
    }
    @Test fun userCancelRejectsLateOutputAndOldFinallyCannotUnlockNewSummary()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val nextEntered=CompletableDeferred<Unit>();val nextRelease=CompletableDeferred<Unit>();val calls=java.util.concurrent.atomic.AtomicInteger()
        val old=engine(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            if(calls.incrementAndGet()==1)return withContext(NonCancellable){entered.complete(Unit);release.await();receipt(request,true)}
            nextEntered.complete(Unit);nextRelease.await();return receipt(request)
        }})
        val first=scope.async{old.ensureRoom("a",forceOne=true)};withTimeout(5000){entered.await()};old.cancel("a")
        store.memorySummary.requireEditable("a")
        val second=scope.async{old.ensureRoom("a",forceOne=true,manualRetry=true)};withTimeout(5000){nextEntered.await()}
        release.complete(Unit);runCatching{first.await()};blocked{repo.setLock("a","synthetic",false)}
        assertTrue(repo.memories("a").isEmpty());nextRelease.complete(Unit);assertTrue(second.await().success);store.memorySummary.requireEditable("a")
    }
    @Test fun timeoutReleasesEvenIfClientIgnoresCancellationAndCannotWriteLate()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val e=engine(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=withContext(NonCancellable){entered.complete(Unit);release.await();receipt(request,true)}},1500)
        val job=scope.async{e.ensureRoom("a",forceOne=true)};withTimeout(5000){entered.await()}
        withTimeout(5000){while("a" in store.memorySummary.roles.value)delay(20)}
        repo.manualSave("a",null,"after deadline",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)
        release.complete(Unit);assertEquals("SUMMARY_TIMEOUT",job.await().code);assertEquals(listOf("after deadline"),repo.memories("a").map{it.text})
    }
    @Test fun concurrentDuplicateIsBusyAndDoesNotCancelTheOwner()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val e=engine(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{entered.complete(Unit);release.await();return receipt(request)}})
        val job=scope.async{e.ensureRoom("a",forceOne=true)};withTimeout(5000){entered.await()}
        assertEquals("BUSY",e.ensureRoom("a",forceOne=true).code);blocked{repo.setLock("a","synthetic",false)}
        release.complete(Unit);assertTrue(job.await().success)
    }
    @Test fun replacementImportsAreBlockedWhileAddImportKeepsExistingRoleUntouched()=runBlocking {
        val backup=BackupRepository(store,vault)
        fun pack(e:BackupRepository.Export)=BackupArchive.read(java.io.ByteArrayOutputStream().also{BackupArchive.write(e.root,e.files,it)}.toByteArray().inputStream())
        val one=pack(backup.export("a"));val all=pack(backup.export(null,false))
        val replaceOne=backup.preview(one,"REPLACE","a");val replaceAll=backup.preview(all,"REPLACE")
        val legacy=RoleArchiveRepository(store);val old=legacy.preview(RoleArchive.validate(legacy.export("a",true).toString().toByteArray()),"a")
        val before=repo.sources("a");val t=store.memorySummary.begin("a",ChatAttemptLease(),10000){}!!
        try{
            blocked{backup.commit(replaceOne,true)};blocked{backup.commit(replaceAll,true)};blocked{legacy.commit(old,true)}
            val added=backup.commit(backup.preview(one,"ADD"));assertEquals(1,added.size);assertEquals(before,repo.sources("a"))
            blocked{repo.manualSave("a",null,"blocked",MemoryCategory.STATE_FACT,false,true,MemoryAbout.USER)}
        }finally{store.memorySummary.finish(t)}
    }
}
