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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

@Composable internal fun SourceEditor(repo:ProcessingRepository,s:SourceDialogue,dismiss:()->Unit,confirm:(SourceImpact)->Unit){
    val scope=rememberCoroutineScope();var side by rememberSaveable{mutableStateOf("user")};var text by rememberSaveable{mutableStateOf(s.user.text)}
    var impact by remember{mutableStateOf<SourceImpact?>(null)};var error by remember{mutableStateOf("")};var oldVersions by remember{mutableStateOf<List<SourceVersion>>(emptyList())}
    LaunchedEffect(side){oldVersions=SourceChanges(repo).history(s.source.characterId,if(side=="user")s.user.id else s.assistant.id)}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(impact==null)"编辑原话" else "确认影响范围")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        if(impact==null){Row{TextButton({side="user";text=s.user.text}){Text("我的原话")};TextButton({side="assistant";text=s.assistant.text}){Text("角色原话")}};AppField(text,{text=it.take(8000)},"原话内容","source-text",minLines=4)
            TextButton({scope.launch{try{impact=SourceChanges(repo).preview(s.source.characterId,s.source.turnId,side,text,true)}catch(_:Exception){error="来源已变化，请重新打开。"}}}){Text("预览撤回")}
            oldVersions.forEach{v->Text("历史 v${v.revision}"+(if(v.deleted)"（撤回）" else "")+"：${v.body}",style=MaterialTheme.typography.bodySmall,color=Muted)}
        }else{Text("${if(impact!!.retract)"撤回" else "编辑"}会影响 ${impact!!.memories.size} 条记忆、${impact!!.candidates.size} 条候选。原话历史保留，旧证据和候选失效，不能再注入或采用。人工 / 锁定项产生待确认冲突。已保存关系分数保留历史，不作未经定义的逐维回滚。");Text("将保存：${if(impact!!.retract)"【已撤回】" else impact!!.text}");impact!!.memories.forEach{Text(it.text,style=MaterialTheme.typography.bodyMedium)};impact!!.candidates.forEach{Text("候选：${it.proposed.text}",style=MaterialTheme.typography.bodyMedium)}}
    }},confirmButton={TextButton({if(impact!=null)confirm(impact!!) else scope.launch{try{impact=SourceChanges(repo).preview(s.source.characterId,s.source.turnId,side,text,false)}catch(_:Exception){error="来源已变化，请重新打开。"}}},enabled=text.isNotBlank()){Text(if(impact==null)"预览影响" else "确认修改")}},dismissButton={TextButton({if(impact!=null)impact=null else dismiss()}){Text("取消")}})
}
