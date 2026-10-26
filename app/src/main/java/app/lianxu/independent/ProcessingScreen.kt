package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun processingMessage(code:String)=when(code){
    "RELATION_REVIEW_UNAVAILABLE"->"仅支持有完整来源快照的最近一次评估；回滚、导入或缺失快照的历史暂不能重评。"
    "RELATION_HISTORY_MISSING"->"这版关系历史已不可用，请刷新后重新选择。"
    "DISABLED"->"请先启用关系评估。"
    "BATCH_REVIEW_REQUIRED"->"本批已保存的条目发生变化，请先核对并保留当前版本，再重试补齐回执。"
    "CANDIDATE_SOURCE_INVALID"->"候选来源已失效，不能采用；可查看保留的来源快照。"
    ""->"尚未运行";"NO_MEMORY_MODEL"->"请先在大模型配置选择记忆模型。";"NO_RELATIONSHIP_MODEL"->"请先选择关系评估模型。"
    "HISTORY_CONFIRMATION"->"需要先选择如何处理已有聊天。";"SOURCE_CHANGED","STALE_TASK"->"来源或设置已变化，结果没有覆盖当前记录。"
    "CANCELLED"->"已停止，原话与已保存条目保留。";"INTERRUPTED"->"上次任务已中断。";"CAPACITY","TURN_TOO_LARGE","INPUT_TOO_LARGE"->"模型装不下完整整理批次。请选择更大容量的记忆模型，或在记忆规则主动调整每批轮数；本批未缩小、原话未移出。"
    "MANUAL_RETRY"->"上次整理未完成，请检查配置后手动重试。";"BUSY"->"任务正在进行。";"MODEL_UNAUTHORIZED"->"认证失败，请检查密钥与地域。"
    "MODEL_RATE_LIMIT"->"服务限流，请稍后手动重试。";"MODEL_TIMEOUT"->"本次超时，旧状态和待处理进度保留。";"MODEL_TLS"->"HTTPS 证书校验失败。"
    else->"本次未完整完成，旧记录保留。请检查配置后重试。"
}
internal fun taskDescription(s:ProcessingRepository.TaskStatus)=when(s.state){
    "IDLE"->"尚未运行";"RUNNING"->"处理中";"WAITING"->"等待有界重试（第 ${s.attempt} 次）";"COMMITTED","COMPLETED"->"已完整保存";else->processingMessage(s.error)
}
@Composable internal fun ProcessingDirectory(vm:AppState){
    Column(Modifier.fillMaxSize().background(Chrome)){
        AppTopBar("记忆与关系",{vm.back()})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            TextButton({vm.open("settings-memory")},modifier=Modifier.testTag("settings-memory-rules")){Text("记忆规则 · 轮数与条数")}
            Text("每位AI分别保存原话、记忆和关系。模型统一在大模型配置中选择。",style=MaterialTheme.typography.bodySmall,color=Muted)
            vm.friends.forEach{friend->Row(Modifier.fillMaxWidth().clickable{vm.open("processing-role",friend.id)}.padding(vertical=14.dp).testTag("processing-friend-${friend.id}"),verticalAlignment=Alignment.CenterVertically){Avatar(friend);Spacer(Modifier.width(14.dp));Text(friend.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);ControlIcon("chevron")}}
        }
    }
}

private data class RelationView(val policy:ProcessingPolicy,val relation:RelationshipState,val relationTask:ProcessingRepository.TaskStatus,val pendingRelation:Int)
@Composable internal fun ProcessingRole(vm:AppState){
    val friend=vm.friends.firstOrNull{it.id==vm.activeId}?:return
    var section by rememberSaveable(friend.id){mutableIntStateOf(0)}
    if(section==0){MemoryExperience(vm,friend){section=1};return}
    val repo=vm.processing;val relations=vm.relationship.repository;val scope=rememberCoroutineScope()
    var view by remember{mutableStateOf<RelationView?>(null)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    var interval by rememberSaveable{mutableStateOf("")};var historyChoice by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(friend.id,vm.processingRevision){val p=repo.policy(friend.id).first;val r=relations.state(friend.id);view=RelationView(p,r,repo.relationshipStatus(friend.id),repo.sources(friend.id,r.cursor).size);if(interval.isEmpty())interval=p.relationshipInterval.toString()}
    fun action(block:suspend()->Unit){if(busy)return;busy=true;error="";scope.launch{try{block();vm.refreshProcessing()}catch(e:CancellationException){throw e}catch(e:Exception){error=processingMessage((e as? ProcessingRejected)?.code?:"FORMAT")}finally{busy=false}}}
    fun enable(choice:String?){action{relations.setEnabled(friend.id,true,choice,vm.modelAccess.credential(ModelPurpose.RELATIONSHIP)!=null)}}
    fun automatic(choice:String?){action{relations.setAutomatic(friend.id,true,vm.modelAccess.credential(ModelPurpose.RELATIONSHIP)!=null,choice);vm.relationship.afterChat(friend.id)}}
    Column(Modifier.fillMaxSize().background(Chrome)){
        AppTopBar(friend.name+" - \u5173\u7cfb",{vm.back()})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Row{TextButton({section=0},modifier=Modifier.testTag("processing-memory")){Text("\u8bb0\u5fc6")};TextButton({},modifier=Modifier.testTag("processing-relationship")){Text("\u5173\u7cfb")}}
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
            val v=view
            if(v==null)CircularProgressIndicator() else {
                Text("六维关系参考",style=MaterialTheme.typography.titleMedium)
                Text("不是好感等级，也不按时间衰减。依赖、嫉妒并非越高越好；与记忆锁定分别运作。",style=MaterialTheme.typography.bodySmall,color=Muted)
                ToggleRow("启用关系评估",v.policy.relationshipEnabled,"relationship-enable",!busy){on->if(on){if(v.pendingRelation>0)historyChoice="enable" else enable(null)}else action{vm.relationship.cancel(friend.id);relations.setEnabled(friend.id,false,null,false)}}
                ToggleRow("自动评估",v.policy.relationshipAutomatic,"relationship-auto",!busy&&v.policy.relationshipEnabled){on->if(on){if(v.pendingRelation>0)historyChoice="auto" else automatic(null)}else action{vm.relationship.cancel(friend.id);relations.setAutomatic(friend.id,false,false)}}
                AppField(interval,{interval=it.take(3)},"每几轮成功回复后评估（1—100）","relationship-interval",singleLine=true)
                TextButton({action{val n=interval.toIntOrNull()?:0;if(n !in 1..100)throw ProcessingRejected("FORMAT");vm.relationship.cancel(friend.id);repo.savePolicy(friend.id,v.policy.copy(relationshipInterval=n))}},enabled=!busy){Text("保存间隔")}
                Text("待评 ${v.pendingRelation} 轮 · ${taskDescription(v.relationTask)}",Modifier.testTag("relationship-task-status"),style=MaterialTheme.typography.bodyMedium)
                Row{TextButton({vm.relationship.evaluate(friend.id)},enabled=v.policy.relationshipEnabled&&!busy,modifier=Modifier.testTag("relationship-evaluate")){Text("评估待处理")};TextButton({vm.cancelProcessing(friend.id)}){Text("停止任务")}}
                v.relation.values.forEach{(dim,value)->Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text("${dim.label}  ${value.score} / 100",style=MaterialTheme.typography.titleSmall);Text(value.statement,style=MaterialTheme.typography.bodyMedium,color=Muted)}}
                v.relation.feeling?.let{f->Text("历史观察 ${Instant.ofEpochMilli(f.observedAt)}：${f.mood.orEmpty()} · ${f.intensity.orEmpty()} · ${f.energy.orEmpty()}。不是对当前情绪的判断。",style=MaterialTheme.typography.bodySmall,color=Muted)}
                RelationshipReviewControls(vm,friend.id,v.relation,busy)
                Text("本次读取启动边界内全部待评完整轮次，单次最长 300 秒；失败保留旧状态和游标。回滚只恢复关系值并保留处理进度；重评只支持最近一次有完整快照的评估，确认采用才保存。",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
    }
    if(historyChoice!=null)AlertDialog(onDismissRequest={historyChoice=null},title={Text("如何处理已有聊天？")},text={Text("存在待评完整轮次。补评会在主动评估或启用自动评估后请求所选模型；从现在继续会跳过这些历史轮次。")},confirmButton={TextButton({val mode=historyChoice;historyChoice=null;if(mode=="auto")automatic("BACKFILL") else enable("BACKFILL")}){Text("补评历史")}},dismissButton={TextButton({val mode=historyChoice;historyChoice=null;if(mode=="auto")automatic("FROM_NOW") else enable("FROM_NOW")}){Text("从现在继续")}})
}
@Composable private fun ToggleRow(label:String,value:Boolean,tag:String,enabled:Boolean=true,changed:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Switch(value,changed,Modifier.testTag(tag).semantics{contentDescription=label},enabled=enabled)}}
