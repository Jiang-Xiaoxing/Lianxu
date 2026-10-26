package app.lianxu.independent

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Independent single-call jobs. No completion callback starts another evaluation or retry loop. */
class RelationshipEngine(private val scope:CoroutineScope,private val repo:ProcessingRepository,private val access:ModelAccess,private val client:TextChatClient,private val changed:suspend()->Unit){
    companion object{private val session=UUID.randomUUID().toString()}
    val repository=RelationshipRepository(repo)
    private class Active(val lease:ChatAttemptLease,var job:Job?=null,var work:RelationshipWork?=null)
    private val active=ConcurrentHashMap<String,Active>()
    fun afterChat(friendId:String){evaluate(friendId,false)}
    fun cancel(friendId:String){active.remove(friendId)?.let{a->a.lease.cancel();a.job?.cancel();a.work?.let{w->scope.launch(NonCancellable){repository.fail(w,"CANCELLED");changed()}}}}
    suspend fun recover(){repository.recoverAbandoned(session);repo.store.friends().forEach{evaluate(it.id,false,true)}}
    fun evaluate(friendId:String,manual:Boolean=true,recovery:Boolean=false):Job? {
        val task=Active(ChatAttemptLease());if(active.putIfAbsent(friendId,task)!=null)return null
        val job=scope.launch{
            try{
                val p=repo.policy(friendId).first
                if(!p.relationshipEnabled||p.historyChoice=="UNCONFIRMED"||(!manual&&!p.relationshipAutomatic))return@launch
                val credentials=access.credential(ModelPurpose.RELATIONSHIP)?:return@launch
                val id=UUID.randomUUID().toString()
                withContext(NonCancellable){
                    task.work=if(recovery)repository.resume(friendId,id,credentials.stamp,session) else repository.begin(friendId,id,credentials.stamp,session,manual)
                    if(!task.lease.isActive())task.work?.let{repository.fail(it,"CANCELLED")}
                    changed()
                }
                currentCoroutineContext().ensureActive()
                val work=task.work?:return@launch
                val friend=repo.read{repo.friend(it,friendId)}
                val prompts=RolePromptStore.decode(repo.store.preference(RolePromptStore.key(friendId),""))
                val request=makeRequest(work,friend,prompts,credentials.config.outputTokens)
                val cost=request.messages.sumOf{ConservativeTokens.tokens(it.content)}+request.outputLimit
                if(cost>credentials.config.contextTokens){repository.fail(work,"CAPACITY");changed();return@launch}
                when(val result=client.complete(credentials.config,credentials.key,request)){
                    is TextChatResult.Failure->repository.fail(work,"MODEL_${result.reason.name}")
                    is TextChatResult.Success->{currentCoroutineContext().ensureActive();rejectUnless(repository.complete(work,JSONObject(result.text),{access.current(ModelPurpose.RELATIONSHIP,credentials.stamp)},task.lease),"CANCELLED")}
                }
                changed()
            }catch(e:CancellationException){withContext(NonCancellable){task.work?.let{repository.fail(it,"CANCELLED")};changed()};throw e}
            catch(e:Exception){withContext(NonCancellable){task.work?.let{repository.fail(it,if(e is ProcessingRejected)e.code else "FORMAT")};changed()}}
            finally{task.lease.cancel();if(active[friendId]===task)active.remove(friendId)}
        }
        task.job=job;return job
    }
    private fun makeRequest(work:RelationshipWork,friend:Friend,prompts:RolePrompts,output:Int):TextChatRequest {
        val instructions="""评估本角色与用户的六维关系，只输出完整JSON，不输出思考、Markdown或工具调用。不是记忆总结，不按时间自动衰减，不默认恋爱；依赖和嫉妒并非越高越好。
维度键固定 CLOSENESS(亲近),TRUST(信任),FAMILIARITY(熟悉),DEPENDENCE(依赖),SAFETY(安全感),JEALOUSY(嫉妒)。每个涉及维度提供整数score(0–100)和一句角色视角statement；提出完整新分数，程序执行限幅。已有维度省略或score为null会整体保留旧分数和自述。首次按背景/初始关系给起点，漏填由程序用角色卡起点补齐，缺省50/50/30/30/50/0。
只评估所给启动边界内全部待评完整轮次，不需要旧事件列表。上次感受带观察时间，只是历史，不强行延续为此刻情绪。可选feeling包含mood、intensity、energy短文字或null，intensity不是0–1数值；若本批无观察则省略feeling。不得编造来源或线下共同经历。
返回 {"task_id":"指定任务","character_id":"指定角色","dimensions":{"TRUST":{"score":50,"statement":"角色自述"}},"feeling":{"mood":"短文字","intensity":"短文字","energy":"短文字"},"complete":true}。"""
        val data=JSONObject().put("task_id",work.id).put("character_id",work.friendId).put("background",(friend.bio+"\n"+friend.prompt).take(4000)).put("initial_relationship",prompts.initialRelationship).put("initial_scores",JSONObject().apply{RelationshipDimension.entries.forEach{d->val v=prompts.initialScores[d];put(d.name,JSONObject().put("score",v?.score?:d.initial).put("statement",v?.statement?:"尚未形成明确判断。"))}}).put("previous",work.previous.json()).put("uninitialized",JSONArray().apply{RelationshipDimension.entries.filter{it !in work.previous.values}.forEach{put(it.name)}}).put("pending_dialogue",JSONArray().apply{work.targets.forEach{put(it.json())}})
        return TextChatRequest(listOf(WireMessage("system",instructions),WireMessage("user",data.toString())),output,300000)
    }
}
