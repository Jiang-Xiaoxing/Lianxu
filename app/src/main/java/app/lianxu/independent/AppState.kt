package app.lianxu.independent

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.util.UUID

class AppState @JvmOverloads constructor(application: Application, client:TextChatClient=AppServices.createTextClient(),vaultNamespace:String=AppServices.vaultNamespace) : AndroidViewModel(application) {
    private val store = Store(application,AppServices.databaseName)
    private val ledger=ChatLedger(store)
    private val modelVault=ModelVault(application,vaultNamespace,store)
    private val diagnosticClient=LoggedTextChatClient(client)
    internal val backup=BackupRepository(store,modelVault)
    internal val modelAccess=ModelAccess(modelVault)
    internal val processing=ProcessingRepository(store)
    internal val roleArchive=RoleArchiveRepository(store)
    internal suspend fun importRoleArchive(preview:RoleArchiveRepository.Preview,confirmed:Boolean,lease:ChatAttemptLease=ChatAttemptLease()):String {
        return withContext(NonCancellable){val id=Diagnostics.span("backup.legacy.import"){roleArchive.commit(preview,confirmed,lease)};reload();id}
    }
    internal suspend fun exportBackup(id:String?,includeKeys:Boolean)=Diagnostics.span("backup.export",mapOf("scope" to if(id==null)"all" else "role","include_keys" to includeKeys)) {backup.export(id,includeKeys)}
    internal suspend fun importBackup(plan:BackupRepository.Preview,confirmed:Boolean,lease:ChatAttemptLease):List<String> = withContext(NonCancellable){Diagnostics.span("backup.import",mapOf("mode" to plan.mode,"scope" to if(plan.pack.all)"all" else "role")) {
        val ids=backup.commit(plan,confirmed,lease)
        if(plan.mode=="REPLACE"){
            val replaced=if(plan.pack.all)friends.map{it.id}else listOf(requireNotNull(plan.target))
            replaced.forEach{runner.cancel(it);memory.cancel(it);relationship.cancel(it);examplesJobs[it]?.cancel()}
        }
        reload();compact=store.preference("compact","false").toBoolean();nickname=store.preference("nickname","旅人");ids
    }}
    internal val examples=RoleExamplesRepository(store)
    private val examplesEngine=RoleExamplesEngine(examples,modelAccess,diagnosticClient)
    private val examplesJobs=mutableMapOf<String,Job>()
    internal val memory=MemoryEngine(viewModelScope,processing,modelAccess,diagnosticClient,{reload()})
    internal val relationship=RelationshipEngine(viewModelScope,processing,modelAccess,diagnosticClient,{reload()})
    private val manualMemoryJobs=mutableMapOf<String,Job>()
    private val runner=ConversationRunner(viewModelScope,store,modelVault,diagnosticClient,processing,memory,relationship,{reload()},{error=it})
    var processingRevision by mutableIntStateOf(0);private set
    var turns by mutableStateOf(emptyList<ChatTurn>());private set
    val chatSending get()=activeId in runner.sending
    val busy get()=saving||chatSending
    private val generator: FriendGenerator = DemoFriendGenerator()
    private val gate = SearchGate()
    private var searchJob: Job? = null
    var friends by mutableStateOf(emptyList<Friend>()); private set
    var messages by mutableStateOf(emptyList<Message>()); private set
    var tab by mutableIntStateOf(0)
    var page by mutableStateOf("home"); private set
    var activeId by mutableStateOf(""); private set
    private var detailReturn = "home"
    private var processingReturn = "processing"
    private var memorySettingsReturn = "processing"
    var searching by mutableStateOf(false); private set
    var candidates by mutableStateOf(emptyList<Friend>()); private set
    var selected by mutableStateOf(emptySet<String>()); private set
    private var saving by mutableStateOf(false)
    var error by mutableStateOf(""); private set
    var compact by mutableStateOf(false); private set
    var nickname by mutableStateOf("旅人"); private set
    var ready by mutableStateOf(false); private set
    init { viewModelScope.launch { runCatching { withContext(Dispatchers.IO){Diagnostics.configure(java.io.File(application.filesDir,"diagnostics/$vaultNamespace"),application.packageManager.getPackageInfo(application.packageName,0).versionName.orEmpty())};ledger.recoverInterrupted();examples.recover();reload(); compact=store.preference("compact","false").toBoolean(); nickname=store.preference("nickname","旅人"); ready=true;recoverProcessing() }.onFailure { error="本地数据读取失败，请重试。" } } }
    private var reloadSerial=0L
    private suspend fun reload() {val id=++reloadSerial;val f=store.friends();val m=store.messages();val t=ledger.all();if(id==reloadSerial){friends=f;messages=m;turns=t;processingRevision++}}
    internal suspend fun refreshProcessing(){reload()}
    private suspend fun recoverProcessing(){memory.recover();relationship.recover()}
    fun foreground(){if(ready)viewModelScope.launch{runCatching{recoverProcessing()}}}
    fun open(page: String, id: String = "") { if(this.page=="chat"&&(page!="chat"||id!=activeId))runner.cancel(activeId);if(page=="detail") detailReturn=this.page;if(page=="settings-memory")memorySettingsReturn=this.page;if(page=="processing-role")processingReturn=if(this.page=="detail")"detail" else "processing"; cancelSearch(); this.page=page; activeId=id; error="" }
    fun back() { if(page=="chat")runner.cancel(activeId);if(page=="settings-memory")page=memorySettingsReturn else if(page=="role-prompts")page="detail" else if(page=="processing-role")page=processingReturn else if(page=="detail") page=detailReturn else { cancelSearch(); page="home" }; error="" }
    fun home() {runner.cancelAll();cancelSearch(); page="home"; error="" }
    fun startSearch() { page="search"; findAgain() }
    fun findAgain() {
        searchJob?.cancel(); val id=gate.begin(); syncSearch(); error=""
        searchJob=viewModelScope.launch {
            try { val result=generator.generate(id); if(page=="search" && gate.complete(id,result)) syncSearch() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(id==gate.generation) { gate.cancel(); syncSearch(); error="寻找未完成，可以重新寻找。" } }
        }
    }
    fun cancelSearch() { searchJob?.cancel(); gate.cancel(); syncSearch() }
    private fun syncSearch() { searching=gate.searching; candidates=gate.candidates; selected=gate.selected }
    fun toggle(id: String) { gate.toggle(id); syncSearch() }
    fun all() { gate.selectAll(); syncSearch() }
    fun none() { gate.clearSelection(); syncSearch() }
    fun addSelected() { if(busy || selected.isEmpty()) return; val chosen=candidates.filter { it.id in selected }; save { store.add(chosen); reload(); tab=1; home() } }
    fun create(name: String,bio: String,prompt: String) {
        if(busy) return
        if(name.isBlank() || bio.isBlank()) { error="请填写名字和角色介绍。"; return }
        val f=Friend(UUID.randomUUID().toString(),name.trim(),bio.trim(),prompt.trim())
        save { store.add(listOf(f),demoGreeting=false); reload(); open("chat",f.id) }
    }
    fun send(text:String,onPersisted:()->Unit={}):Boolean {
        if(saving||friends.none{it.id==activeId})return false
        return runner.send(activeId,text,onPersisted)
    }
    fun cancelChat(friendId:String){runner.cancel(friendId)}
    fun cancelChats(){runner.cancelAll()}
    fun cancelProcessing(id:String){memory.cancel(id);relationship.cancel(id)}
    fun retryMemory(id:String){
        if(id in manualMemoryJobs||friends.none{it.id==id})return
        error=""
        val job=viewModelScope.launch(start=CoroutineStart.LAZY){try{val result=memory.ensureRoom(id,forceOne=true,manualRetry=true);if(!result.success)error=processingMessage(result.code);reload()}finally{manualMemoryJobs.remove(id)}}
        manualMemoryJobs[id]=job;job.start()
    }
    internal suspend fun previewRoleDeletion(id:String)=backup.previewRoleDeletion(id)
    internal suspend fun deleteRole(plan:BackupRepository.RoleDeletion,confirmed:Boolean){
        require(confirmed){"删除角色需要再次确认"}
        withContext(NonCancellable){
            val id=plan.friendId
            runner.cancel(id);memory.cancel(id);relationship.cancel(id);cancelExamples(id);manualMemoryJobs[id]?.cancel()
            backup.deleteRole(plan,true)
            if(activeId==id){activeId="";page="home"}
            reload()
        }
    }
    fun retryChat(turn:ChatTurn){runner.retry(turn)}
    internal suspend fun examplesConsent()=examplesEngine.consent()
    internal fun selectExamples(id:String,consent:ExamplesConsent){
        if(examplesJobs[id]?.isActive==true)return
        examplesJobs[id]=viewModelScope.launch{try{examplesEngine.select(id,consent){reload()};reload()}catch(e:CancellationException){throw e}catch(_:Exception){error="示例挑选未完成，请检查配置后重试。"}}
    }
    internal fun cancelExamples(id:String){examplesJobs.remove(id)?.cancel()}
    internal fun stopExamples(id:String){cancelExamples(id);viewModelScope.launch{examples.stop(id);reload()}}
    suspend fun rolePrompts(id:String)=RolePromptStore.decode(store.preference(RolePromptStore.key(id),""))
    fun saveRolePrompts(id:String,expression:String,extra:String,internal:String,enabled:Boolean,initialRelationship:String=""){
        save{store.updateRolePrompts(id){old->old.copy(expression=expression,extra=extra,internalPrompt=internal,internalEnabled=enabled,thinkingNone=!enabled,initialRelationship=initialRelationship)};back()}
    }
    fun saveProfile(name: String) { if(name.isBlank()) {error="昵称不能为空。"; return}; save { store.setPreference("nickname",name.trim()); nickname=name.trim() } }
    fun updateCompact(value: Boolean) { save { store.setPreference("compact",value.toString()); compact=value } }
    private fun save(block: suspend () -> Unit) { saving=true; error=""; viewModelScope.launch { try { block() } catch(e: Exception) {error="保存未完成，请重试。"} finally {saving=false} } }
}

