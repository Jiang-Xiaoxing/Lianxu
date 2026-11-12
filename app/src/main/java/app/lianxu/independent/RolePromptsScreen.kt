package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun RolePromptsScreen(vm:AppState){
    val friend=vm.friends.firstOrNull{it.id==vm.activeId}?:return
    var loaded by rememberSaveable(friend.id){mutableStateOf(false)}
    var name by rememberSaveable(friend.id){mutableStateOf(friend.name)}
    var definition by rememberSaveable(friend.id){mutableStateOf("")}
    var opening by rememberSaveable(friend.id){mutableStateOf("")}
    var expression by rememberSaveable(friend.id){mutableStateOf("")}
    var extra by rememberSaveable(friend.id){mutableStateOf("")}
    var initialRelationship by rememberSaveable(friend.id){mutableStateOf("")}
    var advanced by rememberSaveable(friend.id){mutableStateOf(false)}
    LaunchedEffect(friend.id){if(!loaded){val p=vm.rolePrompts(friend.id);definition=p.definition(friend);opening=p.opening;expression=p.expression;extra=p.extra;initialRelationship=p.initialRelationship;loaded=true}}
    Column(Modifier.fillMaxSize()){
        AppTopBar("${friend.name} · 角色设定",{vm.back()},extra={CompactSaveAction(loaded&&!vm.busy){vm.saveCharacter(friend.id,name,definition,expression,opening,extra,initialRelationship)}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            AppField(name,{name=it.take(30)},"名字","role-name",singleLine=true)
            AppField(definition,{definition=it.take(8000)},"角色设定","role-definition",minLines=5)
            AppField(expression,{expression=it.take(8000)},"说话方式（留空跟随默认）","role-expression",minLines=3)
            AppField(opening,{opening=it.take(8000)},"开场白（可选）","role-opening",minLines=2)
            Text("只修改本角色的对应提示段；模型统一在设置中选择。",style=MaterialTheme.typography.bodySmall,color=Muted)
            TextButton({advanced=!advanced},modifier=Modifier.testTag("role-advanced")){Text(if(advanced)"收起高级设置" else "高级设置：补充提示与关系背景")}
            if(advanced){
            AppField(extra,{extra=it.take(8000)},"附加提示","role-extra",minLines=3)
            AppField(initialRelationship,{initialRelationship=it.take(4000)},"初始关系背景（可选）","role-initial-relationship",minLines=2)
            Text("自定义内容不会因默认文案更新而被覆盖。{{character}} 只在发送副本中替换为角色名；文字不能授予程序尚未开放的工具权限。",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
    }
}
