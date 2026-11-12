package app.lianxu.independent

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One live draft and its submission receipt survive an Activity rebuild together. */
internal class ChatDraftState(text:String="",version:Long=0){
    val text=mutableStateOf(text)
    val version=mutableLongStateOf(version)
    val submittedVersion=mutableLongStateOf(-1)
    val persistedVersion=mutableLongStateOf(-1)
    val submittedAt=mutableLongStateOf(0)
}

class AppState @JvmOverloads constructor(application: Application, client:TextChatClient=AppServices.createTextClient(),vaultNamespace:String=AppServices.vaultNamespace) : AndroidViewModel(application) {
    internal val chatDrafts=mutableMapOf<String,ChatDraftState>()
    private val store = Store(application,AppServices.databaseName)
    private val ledger=ChatLedger(store)
    private val modelVault=ModelVault(application,vaultNamespace,store)
    private val diagnosticClient=LoggedTextChatClient(client)
    internal val backup=BackupRepository(store,modelVault)
    internal val modelAccess=ModelAccess(modelVault)
    internal val processing=ProcessingRepository(store)
    private val roleAssets=RoleAssets(processing)
    internal var roleAvatars by mutableStateOf(emptyMap<String,ByteArray>());private set
    internal val companion=CompanionRepository(store){AppServices.companionClock()}
    private val companionNotifications=CompanionNotifications(application,store,companion)
    internal var companionReceipts by mutableStateOf(emptyList<CompanionReceipt>());private set
    internal var companionPolicy by mutableStateOf("disabled")
    var notificationTarget by mutableStateOf<Long?>(null);private set
    internal suspend fun settleCompanion(){companionNotifications.reconcile();CompanionScheduler.enqueue(getApplication(),companion)}
    internal fun setCompanionEnabled(enabled:Boolean,onEnabled:()->Unit={}){val role=activeId;viewModelScope.launch{
        companion.setPolicy(role,enabled,CompanionRuntime.owner)
        if(!enabled)CompanionScheduler.cancel(getApplication(),role)
        if(activeId==role){companionPolicy=companion.policy(role);if(enabled&&companionPolicy=="enabled")onEnabled()}
        settleCompanion();reload()
    }}
    internal fun companionRead(role:String,id:Long,stillVisible:()->Boolean){viewModelScope.launch{companion.markRead(role,id){page=="chat"&&activeId==role&&stillVisible()};companionReceipts=companion.receipts();companionNotifications.reconcile()}}
    internal fun sourceControl(messageId:Long,forget:Boolean){val role=activeId;viewModelScope.launch{
        val id=companion.sourceOfMessage(role,messageId)?:return@launch
        runner.cancel(role)
        if(forget)companion.forget(role,id)else companion.stopReminder(role,id)
        settleCompanion();reload()
    }}
    internal fun notificationOpen(role:String,id:Long){viewModelScope.launch{
        while(!ready)delay(20)
        reload()
        if(messages.any{it.id==id&&it.friendId==role}&&friends.any{it.id==role}){
            open("chat",role);notificationTarget=id;companion.clicked(role,id)
        }
    }}
    internal val memoryChatRequest = MemoryChatRequest()
    private var memoryChatReturn = false
    internal suspend fun jumpToMemoryChat(source: SourceRef): Boolean {
        val current = processing.sources(source.characterId).firstOrNull { it.source.turnId == source.turnId } ?: return false
        reload() // Store.messages loads the complete history; there is no paging boundary.
        if (memoryChatIndex(messages, current.source) == null) return false
        memoryChatRequest.pending = current.source
        notificationTarget = null
        open("chat", source.characterId)
        memoryChatReturn = true
        return true
    }
    internal fun notificationShown(){notificationTarget=null}
    internal val roleArchive=RoleArchiveRepository(store)
    internal suspend fun importRoleArchive(preview:RoleArchiveRepository.Preview,confirmed:Boolean,lease:ChatAttemptLease=ChatAttemptLease()):String {
        return withContext(NonCancellable){val id=Diagnostics.span("backup.legacy.import"){roleArchive.commit(preview,confirmed,lease)};avatarCache.reset();messagePrints=emptyMap();reload();id}
    }
    internal suspend fun exportBackup(id:String?,includeKeys:Boolean,password:CharArray?=null)=Diagnostics.span("backup.export",mapOf("scope" to if(id==null)"all" else "role","include_keys" to includeKeys)) {backup.export(id,includeKeys,password)}
    internal suspend fun importBackup(plan:BackupRepository.Preview,confirmed:Boolean,lease:ChatAttemptLease,password:CharArray?=null):List<String> = withContext(NonCancellable){Diagnostics.span("backup.import",mapOf("mode" to plan.mode,"scope" to if(plan.pack.all)"all" else "role")) {
        val ids=backup.commit(plan,confirmed,lease,password=password)
        if(plan.mode=="REPLACE"){
            val replaced=if(plan.pack.all)friends.map{it.id}else listOf(requireNotNull(plan.target))
            replaced.forEach{runner.cancel(it);memory.cancel(it)}
        }
        avatarCache.reset();messagePrints=emptyMap();reload();persona=store.persona();ids
    }}
    internal val memory=MemoryEngine(viewModelScope,processing,modelAccess,diagnosticClient,{reload()})
    private val manualMemoryJobs=mutableMapOf<String,Job>()
    private val runner=ConversationRunner(viewModelScope,store,modelVault,diagnosticClient,processing,memory,{reload()},{error=it},{settleCompanion()},displayReplies=true)
    var processingRevision by mutableIntStateOf(0);private set
    var turns by mutableStateOf(emptyList<ChatTurn>());private set
    val chatSending get()=activeId in runner.sending
    internal val chatPreview get()=runner.previews[activeId]
    val busy get()=saving||chatSending
    val chatInputAvailable get()=!saving
    internal fun replyShown(role:String,turn:String,attempt:Int,count:Int)=runner.acknowledge(role,turn,attempt,count)
    fun stopReply(role:String)=runner.stop(role)
    private val generator=OriginalRoleGenerator(diagnosticClient,{modelAccess.credential(ModelPurpose.CHAT)},{modelAccess.current(ModelPurpose.CHAT,it)})
    private val gate = SearchGate()
    private var searchJob: Job? = null
    var friends by mutableStateOf(emptyList<Friend>()); private set
    var messages by mutableStateOf(emptyList<Message>()); private set
    /** One row per committed memory batch; placed in the chat where that batch finished. */
    internal var memoryPills by mutableStateOf(emptyList<MemoryPill>()); private set
    var tab by mutableIntStateOf(0)
    var page by mutableStateOf("home"); private set
    var activeId by mutableStateOf(""); private set
    private var detailReturn = "home"
    private var processingReturn = "processing"
    private var memorySettingsReturn = "processing"
    var searching by mutableStateOf(false); private set
    var candidates by mutableStateOf(emptyList<Friend>()); private set
    var generatedRoles by mutableStateOf(emptyList<GeneratedRole>()); private set
    var searchRequirements by mutableStateOf(""); private set
    var candidateId by mutableStateOf(""); private set
    private var saving by mutableStateOf(false)
    var error by mutableStateOf(""); private set
    var persona by mutableStateOf(UserPersona());private set
    val nickname get()=persona.displayName
    var ready by mutableStateOf(false); private set
    /** One switch: detailed journal mode records reasoning text and exports detailed rows. */
    internal var diagnosticDetailed by mutableStateOf(false); private set
    private var savedDiagnosticDetailed=false
    private var diagnosticChoice=0L
    private val diagnosticSave=Mutex()
    /** Test seam: lets device tests inject a slow or failing save without touching real preferences. */
    internal var diagnosticPersist: (suspend (String)->Unit)?=null
    internal fun setDiagnosticDetailed(value:Boolean){
        val choice=++diagnosticChoice
        diagnosticDetailed=value;Diagnostics.detailed=value
        viewModelScope.launch{
            // Saves are serialized and write the value current at write time, so a fast on→off cannot
            // resurrect "on" after restart; a failed save rolls the switch back to what is really stored.
            val written=withContext(Dispatchers.IO){runCatching{diagnosticSave.withLock{
                val current=diagnosticDetailed
                val raw=if(current)"1" else "0"
                diagnosticPersist?.invoke(raw)
                store.savePreference("diagnostic-detailed",raw)
                current
            }}.getOrNull()}
            // The disk always reflects the newest completed write, whatever the switch shows now.
            if(written!=null)savedDiagnosticDetailed=written
            // A newer choice owns the switch now: an older save may not roll it back.
            if(choice!=diagnosticChoice)return@launch
            if(written==null){Diagnostics.event("diagnostic.preference.failed");diagnosticDetailed=savedDiagnosticDetailed;Diagnostics.detailed=savedDiagnosticDetailed}
        }
    }
    init { viewModelScope.launch { runCatching { val savedDetailed=withContext(Dispatchers.IO){
        val saved=runCatching{store.preference("diagnostic-detailed","0")}.getOrDefault("0")=="1";Diagnostics.detailed=saved;saved
    };diagnosticDetailed=savedDetailed;savedDiagnosticDetailed=savedDetailed;withContext(Dispatchers.IO){Diagnostics.configure(java.io.File(application.filesDir,"diagnostics/$vaultNamespace"),application.packageManager.getPackageInfo(application.packageName,0).versionName.orEmpty())};ledger.recoverInterrupted();companion.checkContinuity(CompanionRuntime.owner);reload();  persona=store.persona(); ready=true;settleCompanion();recoverProcessing() }.onFailure { error="本地数据读取失败，请重试。" } } }
    private var reloadSerial=0L
    private val avatarCache=RoleAvatarCache()
    private var messagePrints=emptyMap<String,MessageFingerprint>()
    init {viewModelScope.launch{CompanionRuntime.revision.collect{if(ready)reload()}}}
    private suspend fun reload() {val id=++reloadSerial;val f=store.friends();val (m,prints)=loadMessages();val t=ledger.all();val receipts=companion.receipts();val pills=withContext(Dispatchers.IO){ChatMemoryPills.read(store.readableDatabase)};val avatars=loadAvatars(f);if(id==reloadSerial){friends=f;messages=m;messagePrints=prints;turns=t;companionReceipts=receipts;memoryPills=pills;roleAvatars=avatars;processingRevision++}}
    private suspend fun loadMessages():Pair<List<Message>,Map<String,MessageFingerprint>>{
        // A discarded reload must not publish fingerprints for rows the UI never received.
        // Capture the accepted pair before suspension; publish the new pair together in reload.
        val currentMessages=messages;val previousPrints=messagePrints
        val prints=store.messageFingerprints()
        if(currentMessages.isEmpty())return store.messages() to prints
        val stale=MessageReload.friendsToReload(previousPrints,prints)
        val reloaded=stale.associateWith{store.messagesFor(it)}
        return MessageReload.merge(currentMessages,reloaded,prints) to prints
    }
    private suspend fun loadAvatars(f:List<Friend>):Map<String,ByteArray> = withContext(Dispatchers.IO){
        avatarCache.retain(f.map{it.id}.toSet())
        for(friend in f){
            if(avatarCache.cached(friend.id)!=null||avatarCache.knownMissing(friend.id))continue
            avatarCache.put(friend.id,roleAssets.avatar(friend.id)?.bytes)
        }
        avatarCache.snapshot()
    }
    internal suspend fun refreshProcessing(){reload()}
    private suspend fun recoverProcessing(){memory.recover();ReplyAccountingScheduler.recover(store)}
    fun foreground(){if(ready)viewModelScope.launch{runCatching{companion.checkContinuity(CompanionRuntime.owner);settleCompanion();reload();recoverProcessing()}}}
    // A chat attempt belongs to its role, not to the currently visible page.
    fun open(page: String, id: String = "") { if(page=="detail") detailReturn=this.page;if(page=="settings-memory")memorySettingsReturn=this.page;if(page=="processing-role")processingReturn=if(this.page=="detail")"detail" else "processing"; cancelSearch(); this.page=page; activeId=id; error="" }
    fun back() { if(page=="chat" && memoryChatReturn){memoryChatReturn=false;memoryChatRequest.consume();page="processing-role";return}; if(page=="search-detail")page="search" else if(page=="settings-memory")page=memorySettingsReturn else if(page=="role-prompts"||page=="role-learning"||page=="role-relationship"||page=="role-companion")page="detail" else if(page=="processing-role")page=processingReturn else if(page=="detail") page=detailReturn else { cancelSearch(); page="home" }; error="" }
    fun home() {cancelSearch(); page="home"; error="" }
    fun startSearch(){cancelSearch();page="search";error=""}
    fun updateSearchRequirements(value:String){searchRequirements=value.take(2000)}
    fun findAgain(){
        if(searching||busy)return
        val requirements=searchRequirements.trim()
        if(requirements.isBlank()){error="先说说你想认识什么样的角色。";return}
        searchJob?.cancel();val id=gate.begin();syncSearch();error=""
        searchJob=viewModelScope.launch {
            try{val result=generator.generate(requirements);if(page=="search"&&gate.complete(id,result))syncSearch()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(id==gate.generation){gate.cancel();syncSearch();error=if(e is RoleGenerationFailure)e.message.orEmpty() else "生成未完成，请重试；原候选已保留。"}}
        }
    }
    fun cancelSearch(){searchJob?.cancel();gate.cancel();syncSearch()}
    private fun syncSearch(){searching=gate.searching;generatedRoles=gate.candidates;candidates=generatedRoles.map{it.friend}}
    fun openCandidate(id:String){if(searching||generatedRoles.none{it.friend.id==id})return;candidateId=id;page="search-detail";error=""}
    fun addCandidate(){
        if(busy)return
        val role=generatedRoles.firstOrNull{it.friend.id==candidateId}?:return
        if(friends.any{it.id==role.friend.id})return
        save{store.addConfigured(role.friend,RolePrompts(characterDefinition=role.definition,expression=role.expression,opening=role.opening));reload()}
    }
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
    fun cancelProcessing(id:String){memory.cancel(id)}
    fun retryMemory(id:String){
        if(id in store.memorySummary.roles.value||friends.none{it.id==id})return
        error=""
        val job=viewModelScope.launch(start=CoroutineStart.LAZY){try{val result=memory.ensureRoom(id,forceOne=true,manualRetry=true);if(!result.success)error=processingMessage(result.code);reload()}finally{if(manualMemoryJobs[id]===currentCoroutineContext().job)manualMemoryJobs.remove(id)}}
        manualMemoryJobs[id]=job;job.start()
    }
    internal suspend fun previewRoleDeletion(id:String)=backup.previewRoleDeletion(id)
    internal suspend fun deleteRole(plan:BackupRepository.RoleDeletion,confirmed:Boolean){
        require(confirmed){"删除角色需要再次确认"}
        withContext(NonCancellable){
            val id=plan.friendId
            store.memorySummary.requireEditable(id)
            backup.deleteRole(plan,true)
            runner.cancel(id);memory.cancel(id);manualMemoryJobs[id]?.cancel()
            if(activeId==id){activeId="";page="home"}
            reload()
        }
    }
    fun retryChat(turn:ChatTurn){runner.retry(turn)}
    suspend fun rolePrompts(id:String)=RolePromptStore.decode(store.preference(RolePromptStore.key(id),""))
    fun saveRolePrompts(id:String,expression:String,extra:String,initialRelationship:String=""){
        save{store.updateRolePrompts(id){old->old.copy(expression=expression,extra=extra,initialRelationship=initialRelationship)};back()}
    }
    fun saveProfile(name:String){if(name.isBlank()){error="昵称不能为空。";return};savePersona(persona.copy(name=name))}
    fun savePersona(value:UserPersona){if(!value.valid()){error="请检查名字、称呼及自我介绍的长度；名字和称呼不要换行。";return};save{store.savePersona(value);persona=store.persona()}}
    fun createCompanion(name:String,definition:String,expression:String,opening:String,extra:String=""){
        if(busy)return
        if(name.isBlank()||definition.isBlank()){error="请填写名字和角色设定。";return}
        if(name.trim().length>30||listOf(definition,expression,opening,extra).any{it.length>8000}){error="请检查角色资料长度。";return}
        val body=definition.trim();val summary=body.take(500).let{if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1)else it}
        val f=Friend(UUID.randomUUID().toString(),name.trim(),summary,"")
        save{store.addConfigured(f,RolePrompts(expression=expression,extra=extra,characterDefinition=body,opening=opening));reload();open("chat",f.id)}
    }
    fun saveCharacter(id:String,name:String,definition:String,expression:String,opening:String,extra:String,initialRelationship:String){
        if(name.isBlank()||definition.isBlank()){error="请填写名字和角色设定。";return}
        save{store.saveConfigured(id,name){old->old.copy(characterDefinition=definition,expression=expression,opening=opening,extra=extra,initialRelationship=initialRelationship)};reload();back()}
    }
    fun setRoleAvatar(id:String,mime:String,bytes:ByteArray){
        if(friends.none{it.id==id})return
        save{
            val prepared=prepareRoleAvatar(bytes)
            roleAssets.put(id,RoleAssets.Asset("avatar.jpg","avatar",prepared.first,prepared.second))
            avatarCache.put(id,prepared.second)
            roleAvatars=roleAvatars+(id to prepared.second)
        }
    }
    private fun save(block: suspend () -> Unit) { saving=true; error=""; viewModelScope.launch { try { block() } catch(e: Exception) {error="保存未完成，请重试。"} finally {saving=false} } }
}

