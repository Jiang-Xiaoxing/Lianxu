package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@Composable internal fun RoleExamplesInvitation(vm:AppState,id:String){
    var row by remember(id){mutableStateOf<RoleExamples?>(null)}
    LaunchedEffect(id,vm.processingRevision){row=vm.examples.get(id)}
    if(row?.state==ExamplesState.OFFERED)Surface(color=MaterialTheme.colorScheme.surfaceContainer){
        Column(Modifier.fillMaxWidth().padding(horizontal=UiSpace.page,vertical=8.dp)){
            Text("已经聊满 20 轮，可从原话挑选语气参考。",style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton({vm.open("role-prompts",id)},Modifier.heightIn(min=UiSpace.action).testTag("examples-invitation")){Text("查看说明")}
                TextButton({vm.stopExamples(id)},Modifier.heightIn(min=UiSpace.action)){Text("不用了")}
            }
        }
    }
}

@Composable internal fun RoleExamplesSection(vm:AppState,id:String){
    var row by remember(id){mutableStateOf<RoleExamples?>(null)}
    var consent by remember(id){mutableStateOf<ExamplesConsent?>(null)}
    var checking by remember(id){mutableStateOf(false)}
    var message by remember(id){mutableStateOf("")}
    val scope=rememberCoroutineScope()
    LaunchedEffect(id,vm.processingRevision){row=vm.examples.get(id)}
    val current=row
    Column(Modifier.fillMaxWidth().testTag("role-examples"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("旧聊天语气参考",style=MaterialTheme.typography.titleSmall)
        Text(when(current?.state){
            null->"本角色未开启新会话示例流程；不会导入旧数据。"
            ExamplesState.WAITING->"聊满 20 个完整回合后，会邀请你挑选参考原话。"
            ExamplesState.OFFERED->"可从本角色已有聊天挑选 2–3 轮原话，只参考语气，不作为事实或新消息。"
            ExamplesState.SELECTING->"正在挑选原话；你可以取消，不会自动重试。"
            ExamplesState.ACTIVE->"已启用 ${current.selected.size} 轮原话参考；近期原话窗口满后自动停用，记录保留。"
            ExamplesState.DECLINED->"你已拒绝本次邀请，不会自动挑选。"
            ExamplesState.FAILED,ExamplesState.DISABLED->current.reason
        },style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(current?.state in setOf(ExamplesState.OFFERED,ExamplesState.FAILED)){
            Button(onClick={checking=true;message="";scope.launchConsent(vm,{consent=it;checking=false},{message=it;checking=false})},enabled=!checking,modifier=Modifier.heightIn(min=UiSpace.action).testTag("examples-prepare")){Text(if(checking)"读取模型配置…" else "查看调用说明")}
            if(current?.state==ExamplesState.OFFERED)TextButton({vm.stopExamples(id)},Modifier.heightIn(min=UiSpace.action)){Text("不用了")}
        }
        if(current?.state==ExamplesState.SELECTING)TextButton({vm.cancelExamples(id)},Modifier.heightIn(min=UiSpace.action).testTag("examples-cancel")){Text("取消挑选")}
        if(current?.state==ExamplesState.ACTIVE)TextButton({vm.stopExamples(id)},Modifier.heightIn(min=UiSpace.action).testTag("examples-disable")){Text("停用并保留")}
        if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
        current?.selected?.forEachIndexed{index,example->
            HorizontalDivider()
            Text("参考 ${index+1} · 原话",style=MaterialTheme.typography.labelLarge)
            Text("你：${example.pair.user}",style=MaterialTheme.typography.bodyMedium)
            Text("角色：${example.pair.assistant}",style=MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
    }
    consent?.let{selected->AlertDialog(onDismissRequest={consent=null},title={Text("允许挑选旧聊天原话？")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("将使用聊天模型：${selected.modelName}（${selected.modelId}）。")
        Text("会向该模型发送本角色设定和最多 15 轮候选原话，可能产生服务商费用。模型只选 2–3 个编号，程序保留原文；不会发送其他角色聊天。")
        Text("成功后启用语气参考。达到设置的近期原话上限（默认 40 轮）后停用，原记录保留。你也可随时停用。")
    }},confirmButton={TextButton({consent=null;vm.selectExamples(id,selected)},Modifier.heightIn(min=UiSpace.action).testTag("examples-confirm")){Text("同意并挑选")}},dismissButton={TextButton({consent=null},Modifier.heightIn(min=UiSpace.action).testTag("examples-dismiss")){Text("暂不调用")}})}
}

private fun kotlinx.coroutines.CoroutineScope.launchConsent(vm:AppState,success:(ExamplesConsent)->Unit,failure:(String)->Unit)=launch(Dispatchers.Main.immediate) {
    try{val selected=vm.examplesConsent();if(selected==null)failure("请先在设置中选择聊天模型，再回来挑选。") else success(selected)}catch(_:Exception){failure("模型配置读取失败，请重试。")}
}
