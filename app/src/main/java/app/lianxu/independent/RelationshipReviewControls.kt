package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun RelationshipReviewControls(vm:AppState,id:String,current:RelationshipState,disabled:Boolean){
    val scope=rememberCoroutineScope();val review=vm.relationship.review
    var busy by remember(id){mutableStateOf(false)}
    var generating by remember(id){mutableStateOf(false)}
    var error by remember(id){mutableStateOf("")}
    var history by remember(id){mutableStateOf<List<RelationshipState>?>(null)}
    var rollback by remember(id){mutableStateOf<RelationshipRollback?>(null)}
    var candidate by remember(id){mutableStateOf<RelationshipCandidate?>(null)}
    var askReassess by remember(id){mutableStateOf(false)}
    fun action(block:suspend()->Unit){if(busy)return;busy=true;error="";scope.launch{try{block();vm.refreshProcessing()}catch(e:CancellationException){throw e}catch(e:Exception){error=processingMessage((e as? ProcessingRejected)?.code?:"FORMAT")}finally{busy=false}}}
    Text("关系历史与重评",style=MaterialTheme.typography.titleMedium)
    if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("relationship-review-error"))
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    TextButton({action{history=vm.relationship.repository.history(id).filter{it.version<current.version}}},enabled=!disabled&&!busy,modifier=Modifier.testTag("relationship-history")){Text("查看可回滚历史")}
    TextButton({askReassess=true},enabled=!disabled&&!busy&&current.version>0,modifier=Modifier.testTag("relationship-reassess")){Text("重新评估最近一次")}
    if(generating)TextButton({vm.relationship.cancel(id)},modifier=Modifier.testTag("relationship-review-cancel")){Text("取消重评")}
    history?.let{items->
        if(items.isEmpty())Text("暂无更早的已保存关系状态。",style=MaterialTheme.typography.bodySmall)
        items.forEach{s->TextButton({action{rollback=review.previewRollback(id,s.version)}},enabled=!busy&&!disabled,modifier=Modifier.testTag("relationship-rollback-${s.version}")){Text("预览恢复到第 ${s.version} 版")}}
    }
    if(askReassess)AlertDialog(onDismissRequest={askReassess=false},title={Text("重新评估最近一次？")},text={Text("将该次评估前的关系状态和原批聊天发送给当前关系模型，服务可能收费。不会重复累计这批变化；结果先预览，采用才保存。只支持有完整快照的最近一次评估，不改写更早历史。")},confirmButton={TextButton({askReassess=false;candidate=null;action{generating=true;try{candidate=vm.relationship.reassess(id)}finally{generating=false}}},modifier=Modifier.testTag("relationship-reassess-confirm")){Text("生成候选")}},dismissButton={TextButton({askReassess=false}){Text("取消")}})
    rollback?.let{p->AlertDialog(onDismissRequest={rollback=null},title={Text("恢复到第 ${p.targetVersion} 版关系？")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("会追加一版恢复记录；聊天、记忆和处理进度保留，不重新累计旧聊天。") ;RelationshipDifferences(p.before,p.after)}},confirmButton={TextButton({rollback=null;action{vm.relationship.cancel(id);check(review.rollback(p));candidate=null;history=null}},modifier=Modifier.testTag("relationship-rollback-confirm")){Text("确认恢复")}},dismissButton={TextButton({rollback=null}){Text("取消")}})}
    candidate?.let{c->AlertDialog(onDismissRequest={candidate=null},title={Text("关系重评候选")},text={Column(Modifier.verticalScroll(rememberScrollState()).testTag("relationship-candidate")){Text("当前保存值 → 候选值。以下是各维简短说明；尚未写入。") ;RelationshipDifferences(c.plan.before,c.after)}},confirmButton={TextButton({candidate=null;action{check(vm.relationship.adopt(c));history=null}},modifier=Modifier.testTag("relationship-adopt")){Text("采用新结果")}},dismissButton={TextButton({candidate=null},modifier=Modifier.testTag("relationship-discard")){Text("保留原结果")}})}
}

@Composable private fun RelationshipDifferences(before:RelationshipState,after:RelationshipState){
    RelationshipDimension.entries.forEach{d->
        val old=before.values[d];val next=after.values[d]
        Column(Modifier.padding(top=12.dp)){
            Text("${d.label}：${old?.score?:"未评估"} → ${next?.score?:"未评估"}",style=MaterialTheme.typography.titleSmall)
            Text(next?.statement?:"恢复到该版未评估状态。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
