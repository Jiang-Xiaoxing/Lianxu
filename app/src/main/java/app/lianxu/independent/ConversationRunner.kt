package app.lianxu.independent

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.UUID

/** User text is durable before draft acknowledgement; only the owning attempt can append a reply. */
class ConversationRunner(private val scope:CoroutineScope,private val store:Store,private val vault:ModelVault,private val client:TextChatClient,private val processing:ProcessingRepository,private val memory:MemoryEngine,private val relationship:RelationshipEngine,private val changed:suspend()->Unit,private val notice:(String)->Unit) {
    private val ledger=ChatLedger(store)
    private val vectors=MemoryVectorIndex(processing,ModelAccess(vault))
    var sending by mutableStateOf(emptySet<String>());private set
    private val jobs=mutableMapOf<String,Job>()
    private val activeTurns=mutableMapOf<String,ChatTurn>()
    private val leases=mutableMapOf<String,ChatAttemptLease>()
    private data class Auth(val config:ModelConfig,val key:String,val epoch:Long)
    private suspend fun auth():Auth?=withContext(Dispatchers.IO){AppMutationGate.read{
        val id=vault.purposeId(ModelPurpose.CHAT)
        vault.list().find{it.id==id}?.let{Auth(it,vault.secret(id),AppMutationGate.epoch())}
    }}
    fun send(friendId:String,input:String,onPersisted:()->Unit):Boolean {
        if(input.isBlank()||input.length>2000||friendId in sending)return false
        launch(friendId,input,null,onPersisted);return true
    }
    fun retry(turn:ChatTurn){if(turn.friendId !in sending)launch(turn.friendId,null,turn.id,{})}
    fun cancel(friendId:String){
        val lease=leases.remove(friendId)?:return;lease.cancel()
        jobs.remove(friendId)?.cancel();sending=sending-friendId
        activeTurns.remove(friendId)?.let{turn->scope.launch(NonCancellable){ledger.cancel(turn.id,turn.attempt);changed()}}
    }
    fun cancelAll(){leases.keys.toList().forEach{cancel(it)}}
    private fun launch(friendId:String,input:String?,retryId:String?,onPersisted:()->Unit){
        val lease=ChatAttemptLease();leases[friendId]=lease;sending=sending+friendId
        jobs[friendId]=scope.launch(start=CoroutineStart.UNDISPATCHED) task@{
            var turn:ChatTurn?=null
            try {
                val selected=withContext(Dispatchers.IO){vault.purposeId(ModelPurpose.CHAT)}
                withContext(NonCancellable){
                    turn=if(retryId==null)ledger.begin(friendId,input!!,selected,UUID.randomUUID().toString()) else ledger.retry(retryId,selected)
                    turn?.let{t->
                        if(leases[friendId]===lease)activeTurns[friendId]=t else ledger.cancel(t.id,t.attempt)
                        onPersisted()
                    }
                    changed()
                }
                ensureActive()
                val current=turn?:run{notice("这一轮已有后续消息或正在处理，未重复发送。");return@task}
                val credentials=auth()
                if(credentials==null){ledger.fail(current.id,current.attempt,ChatFailure.MISSING_CONFIG);changed();return@task}
                processing.maintainCore(friendId)
                var context=ledger.context(current.id);var prompts=RolePromptStore.decode(context.rawPrompts)
                var rules=processing.memoryRules().first
                if(MemoryRules.completeRounds(context.recent)>=rules.recentRounds&&!prompts.examplesClosed){
                    store.setPreference(RolePromptStore.key(friendId),RolePromptStore.encode(prompts.copy(examplesClosed=true)))
                    context=ledger.context(current.id);prompts=RolePromptStore.decode(context.rawPrompts)
                }
                var refs=processing.references(friendId,context.input,current.userMessageId,vectors.scores(friendId,context.input))
                fun build()=CompanionPrompt.build(context.friend,prompts,context.recent,context.input,refs.valid,refs.pending,capacity=credentials.config.contextTokens,outputLimit=credentials.config.outputTokens,recentLimit=rules.recentRounds,relatedLimit=rules.relatedCount)
                var built=build()
                val fixedOnly=CompanionPrompt.build(context.friend,prompts,emptyList(),context.input,refs.valid.filter{it.mandatory},capacity=credentials.config.contextTokens,outputLimit=credentials.config.outputTokens)
                if(fixedOnly is PromptBuild.Blocked&&processing.coreBudget(friendId).overGoal){ledger.fail(current.id,current.attempt,ChatFailure.CORE_CAPACITY);changed();return@task}
                if(built is PromptBuild.Blocked&&built.reason==ChatFailure.CONTEXT_NEEDS_MEMORY&&context.recent.isNotEmpty()){
                    val run=memory.ensureRoom(friendId,context.input)
                    if(!run.success){ledger.fail(current.id,current.attempt,when(run.code){"NO_MEMORY_MODEL"->ChatFailure.MISSING_MEMORY_MODEL;"CAPACITY","TURN_TOO_LARGE"->ChatFailure.MEMORY_CAPACITY;else->ChatFailure.MEMORY_FAILED});changed();return@task}
                    context=ledger.context(current.id);prompts=RolePromptStore.decode(context.rawPrompts);rules=processing.memoryRules().first
                    refs=processing.references(friendId,context.input,current.userMessageId,vectors.scores(friendId,context.input));built=build()
                }
                if(built is PromptBuild.Blocked){ledger.fail(current.id,current.attempt,built.reason);changed();return@task}
                val tools=MemoryTools(processing,current,context.input,refs){lease.isActive()&&AppMutationGate.epoch()==credentials.epoch&&vault.purposeId(ModelPurpose.CHAT)==credentials.config.id&&vault.list().any{it==credentials.config}&&processing.referenceCurrent(friendId,refs.stamp)}
                val result=withContext(Dispatchers.IO){tools.run(credentials.config,credentials.key,(built as PromptBuild.Ready).request,client)}
                ensureActive()
                when(result){
                    is TextChatResult.Success->{
                        val saved=ledger.finishWithMemory(current.id,current.attempt,result.text,lease,context.stamp,{db->tools.commit(db)}){
                            AppMutationGate.epoch()==credentials.epoch&&vault.purposeId(ModelPurpose.CHAT)==credentials.config.id&&vault.list().any{it==credentials.config}&&processing.referenceCurrent(friendId,refs.stamp)
                        }
                        if(!saved)ledger.fail(current.id,current.attempt,ChatFailure.SOURCE_CHANGED)
                        else {memory.afterChat(friendId);relationship.afterChat(friendId)}
                    }
                    is TextChatResult.Failure->ledger.fail(current.id,current.attempt,result.reason)
                }
                changed()
            }catch(e:CancellationException){
                withContext(NonCancellable){turn?.let{ledger.cancel(it.id,it.attempt)};changed()};throw e
            }catch(_:Exception){
                withContext(NonCancellable){turn?.let{ledger.fail(it.id,it.attempt,ChatFailure.NETWORK)};changed()}
                if(leases[friendId]===lease)notice("本次会话未完成，原消息已保留；可检查配置后手动重试。")
            }finally{
                if(leases[friendId]===lease){leases.remove(friendId);jobs.remove(friendId);activeTurns.remove(friendId);sending=sending-friendId}
            }
        }
    }
}
