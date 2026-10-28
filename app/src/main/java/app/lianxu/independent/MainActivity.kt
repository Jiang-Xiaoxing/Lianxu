package app.lianxu.independent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();setContent{LianxuTheme{Lianxu()}}}
}
@Composable fun Lianxu(vm:AppState=viewModel()){
    LocalUpdateNotice(vm)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val chatState=rememberSaveableStateHolder()
    var rememberedChatIds by rememberSaveable(stateSaver=listSaver<List<String>,String>(save={it},restore={it.toList()})){mutableStateOf(emptyList<String>())}
    LaunchedEffect(vm.ready,vm.friends,vm.page,vm.activeId){if(vm.ready){
        val live=vm.friends.map{it.id}.toSet()
        val known=rememberedChatIds.toSet()+(if(vm.page=="chat")setOf(vm.activeId)else emptySet())
        (known-live).forEach{chatState.removeState("chat-$it")}
        rememberedChatIds=known.intersect(live).toList()
    }}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){if(vm.searching)vm.cancelSearch();vm.cancelChats()}else if(event==Lifecycle.Event.ON_START)vm.foreground()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    BackHandler(vm.page!="home"){vm.back()}
    Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.safeDrawingPadding().imePadding()){
        if(vm.error.isNotEmpty())Text(vm.error,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onErrorContainer)
        if(!vm.ready)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        else when(vm.page){
            "home"->Home(vm)
            "chat"->chatState.SaveableStateProvider("chat-${vm.activeId}"){Chat(vm)}
            "detail"->Detail(vm)
            "role-prompts"->RolePromptsScreen(vm)
            "create"->Create(vm)
            "role-archive"->RoleArchiveScreen(vm)
            "search"->Search(vm)
            "models"->ModelConfigScreen({vm.back()})
            "processing"->ProcessingDirectory(vm)
            "relationships"->ProcessingDirectory(vm,true)
            "processing-role"->ProcessingRole(vm)
            "relationship-role"->ProcessingRole(vm,true)
            "settings-memory"->MemoryRulesScreen(vm)
            "settings-display"->DisplaySettings(vm)
            "settings-info"->DataAndVersion({vm.back()})
            "settings-updates"->UpdatesScreen({vm.back()})
        }
    }}
}
@Composable private fun Home(vm:AppState){
    var menu by remember{mutableStateOf(false)}
    var removal by remember{mutableStateOf<BackupRepository.RoleDeletion?>(null)}
    var removing by remember{mutableStateOf(false)}
    var removalError by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun askDelete(friend:Friend){if(!removing){removing=true;removalError="";scope.launch{try{removal=vm.previewRoleDeletion(friend.id)}catch(_:Exception){removalError="角色资料读取失败，请重试。"}finally{removing=false}}}}
    removal?.let{plan->AlertDialog(
        onDismissRequest={if(!removing)removal=null},
        title={Text("删除「${plan.name}」？")},
        text={Text("将从聊天和通讯录同时移除这个角色，并永久删除其聊天、记忆（含历史与候选）、关系记录、提示词和已保存资源。进行中的回复和后台任务会停止。\n\n无法在应用内撤销，建议先导出备份。其他角色和全局模型配置保留。",Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={if(!removing){removing=true;scope.launch{try{vm.deleteRole(plan,true);removal=null}catch(_:Exception){removal=null;removalError="角色删除未完成，请重新选择后重试。"}finally{removing=false}}}},enabled=!removing,modifier=Modifier.testTag("role-delete-confirm")){Text(if(removing)"正在删除…"else"删除角色",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton(onClick={removal=null},enabled=!removing,modifier=Modifier.testTag("role-delete-cancel")){Text("取消")}}
    )}
    val labels=listOf("聊天","通讯录","设置","我的")
    val time=remember{SimpleDateFormat("HH:mm",Locale.getDefault())}
    Column(Modifier.fillMaxSize()){
        if(removalError.isNotEmpty())Text(removalError,Modifier.padding(UiSpace.page).testTag("role-delete-error"),color=MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().heightIn(min=80.dp).padding(start=20.dp,end=8.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(labels[vm.tab].let{if(vm.tab==0)"恋叙" else it},Modifier.testTag("home-title"),style=if(vm.tab==0)BrandStyle else MaterialTheme.typography.titleLarge)
            }
            if(vm.tab==0)MoonCompanion()
            if(vm.tab<=1)Box{
                IconButton(onClick={menu=true},modifier=Modifier.size(UiSpace.action).testTag("add-menu").semantics{contentDescription="创建角色"}){ControlIcon("add")}
                DropdownMenu(menu,{menu=false}){
                    DropdownMenuItem(text={Text("寻找角色")},leadingIcon={ControlIcon("search")},onClick={menu=false;vm.startSearch()})
                    DropdownMenuItem(text={Text("创建角色")},leadingIcon={ControlIcon("add")},onClick={menu=false;vm.open("create")})
                }
            }
        }
        Box(Modifier.weight(1f)){when(vm.tab){
            0->LazyColumn(Modifier.fillMaxSize().testTag("chat-list")){
                if(vm.friends.isEmpty())item{Text("还没有角色，点右上角＋添加你自己的角色。",Modifier.padding(UiSpace.page).testTag("roles-empty-chat"),style=MaterialTheme.typography.bodyMedium,color=Muted)}
                items(vm.friends,key={it.id}){friend->val message=vm.messages.lastOrNull{it.friendId==friend.id};FriendRow(friend,message?.text?:"开始聊天",vm.compact,message?.let{time.format(Date(it.created))}.orEmpty(),!removing,{askDelete(friend)}){vm.open("chat",friend.id)}}
            }
            1->LazyColumn(Modifier.fillMaxSize().testTag("contacts-list")){
                item{Text("${vm.friends.size} 位角色",Modifier.padding(horizontal=UiSpace.page,vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=Muted)}
                if(vm.friends.isEmpty())item{Text("还没有角色，点右上角＋添加你自己的角色。",Modifier.padding(UiSpace.page).testTag("roles-empty-contacts"),style=MaterialTheme.typography.bodyMedium,color=Muted)}
                items(vm.friends,key={it.id}){friend->FriendRow(friend,friend.bio,vm.compact,"",!removing,{askDelete(friend)}){vm.open("detail",friend.id)}}
            }
            2->SettingsDirectory(vm)
            3->Profile(vm)
        }}
        HorizontalDivider(color=Line,thickness=.5.dp)
        Row(Modifier.fillMaxWidth().height(72.dp).background(Chrome)){labels.forEachIndexed{index,label->
            Column(Modifier.weight(1f).fillMaxHeight().selectable(index==vm.tab,role=Role.Tab,onClick={vm.tab=index}).testTag("tab-$index"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                NavigationIcon(index,index==vm.tab);Text(label,Modifier.padding(top=4.dp),style=MaterialTheme.typography.labelMedium,color=if(index==vm.tab)GreenText else Muted,maxLines=1)
            }
        }}
    }
}
@Composable private fun FriendRow(friend:Friend,subtitle:String,compact:Boolean,meta:String,deleteEnabled:Boolean,onDelete:()->Unit,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=UiSpace.page,vertical=if(compact)12.dp else 16.dp),verticalAlignment=Alignment.CenterVertically){
        Avatar(friend);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(friend.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis);if(meta.isNotEmpty())Text(meta,style=MaterialTheme.typography.labelSmall,color=Muted)}
            Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        TextButton(onClick=onDelete,enabled=deleteEnabled,modifier=Modifier.heightIn(min=UiSpace.action).testTag("role-delete-${friend.id}").semantics{contentDescription="删除角色${friend.name}"}){Text("删除",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    };HorizontalDivider(Modifier.padding(start=76.dp,end=UiSpace.page),color=Line,thickness=.5.dp)
}
@Composable private fun Detail(vm:AppState){
    val friend=vm.friends.find{it.id==vm.activeId}?:return
    Column(Modifier.fillMaxSize().background(Chrome)){
        Header("角色资料",{vm.back()})
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){Avatar(friend,56);Column{Text(friend.name,style=MaterialTheme.typography.titleLarge);Text("AI 角色 · 本地创建",style=MaterialTheme.typography.bodySmall,color=Muted)}}
            Text(friend.bio,style=MaterialTheme.typography.bodyLarge)
            HorizontalDivider(color=Line)
            Text("角色提示词",style=MaterialTheme.typography.titleSmall)
            Text(friend.prompt.ifBlank{"温柔、自然地回应，尊重用户。不要求披露内部思考。"},style=MaterialTheme.typography.bodyMedium,color=Muted)
            Text("文字消息、总结记忆和关系评估分别使用统一设置的用途模型；本角色的原话与状态独立保存。",style=MaterialTheme.typography.bodySmall,color=Muted)
            TextButton(onClick={vm.open("role-prompts",friend.id)},modifier=Modifier.heightIn(min=UiSpace.action).testTag("role-prompts-open")){Text("编辑角色设定")}
            TextButton(onClick={vm.open("processing-role",friend.id)},modifier=Modifier.heightIn(min=UiSpace.action).testTag("role-processing-open")){Text("记忆")}
            TextButton(onClick={vm.open("relationship-role",friend.id)},modifier=Modifier.heightIn(min=UiSpace.action).testTag("role-relationship-open")){Text("关系")}
            PrimaryAction("发消息",modifier=Modifier.align(Alignment.End)){vm.open("chat",friend.id)}
        }
    }
}
@Composable private fun Create(vm:AppState){
    var name by rememberSaveable{mutableStateOf("")};var bio by rememberSaveable{mutableStateOf("")};var prompt by rememberSaveable{mutableStateOf("")};var expression by rememberSaveable{mutableStateOf("")};var opening by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxSize().background(Chrome)){
        Header("创建角色",{vm.back()})
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(UiSpace.group)){
            Text("从一个名字开始",style=MaterialTheme.typography.titleLarge)
            Text("手动创建 AI 角色，只保存在本机。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(name,{name=it.take(30)},"名字（必填）","friend-name",singleLine=true)
            AppField(bio,{bio=it.take(8000)},"角色设定（必填）","friend-bio",minLines=3)
            AppField(expression,{expression=it.take(8000)},"说话方式（可选）","friend-expression",minLines=2)
            AppField(opening,{opening=it.take(8000)},"开场白（可选）","friend-opening",minLines=2)
            AppField(prompt,{prompt=it.take(4000)},"自定义提示词（可选）","friend-prompt",minLines=3)
            Text("自定义内容用于本角色提示段，模型统一在设置中选择。未配置聊天模型时不会生成演示回复。",style=MaterialTheme.typography.bodySmall,color=Muted)
            PrimaryAction("创建角色",enabled=!vm.busy,modifier=Modifier.align(Alignment.End).testTag("create-friend")){vm.createCompanion(name,bio,expression,opening,prompt)}
        }
    }
}
@Composable private fun Search(vm:AppState){
    Column(Modifier.fillMaxSize().background(Chrome)){
        Header("寻找角色",{vm.back()})
        if(vm.searching){
            Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                Radar();Text("正在寻找角色",Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleLarge)
                Text("演示生成器 · 无需定位",Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            TextButton(onClick={vm.home()},modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=16.dp).heightIn(min=UiSpace.action).testTag("search-cancel")){Text("取消")}
        }else if(vm.candidates.isNotEmpty()){
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("认识新角色",style=MaterialTheme.typography.titleLarge)
                Text("演示候选 · 可选择一位或多位",style=MaterialTheme.typography.bodySmall,color=Muted)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={vm.all()},modifier=Modifier.heightIn(min=UiSpace.action)){Text("全选 3 位")};TextButton(onClick={vm.none()},modifier=Modifier.heightIn(min=UiSpace.action)){Text("不选")}}
                vm.candidates.forEach{friend->val selected=friend.id in vm.selected
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(if(selected)SelectedBackground else Paper).border(1.dp,if(selected)GreenText else Line,RoundedCornerShape(UiSpace.radius)).toggleable(selected,role=Role.Checkbox,onValueChange={vm.toggle(friend.id)}).padding(16.dp).testTag("candidate-${friend.id}"),verticalAlignment=Alignment.CenterVertically){
                        Avatar(friend);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(friend.name,style=MaterialTheme.typography.titleSmall)
                            Text(friend.bio,style=MaterialTheme.typography.bodyMedium,color=Muted)
                            if(vm.friends.any{it.id==friend.id})Text("已在通讯录",style=MaterialTheme.typography.labelSmall,color=GreenText)
                        };Checkbox(selected,null,colors=CheckboxDefaults.colors(checkedColor=Accent,checkmarkColor=MaterialTheme.colorScheme.onSecondaryContainer))
                    }
                }
                Text("候选来自本地演示生成器。",style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            Row(Modifier.fillMaxWidth().background(Paper).padding(UiSpace.page),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={vm.findAgain()},enabled=!vm.busy,modifier=Modifier.weight(1f).heightIn(min=UiSpace.action),shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(12.dp)){Text("重新寻找")}
                PrimaryAction("添加已选（${vm.selected.size}）",enabled=vm.selected.isNotEmpty()&&!vm.busy,modifier=Modifier.weight(1f)){vm.addSelected()}
            }
        }else Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
            Text("寻找已停止",style=MaterialTheme.typography.titleLarge)
            Text("可以重新开始认识新角色。",Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.bodyMedium,color=Muted)
            PrimaryAction("重新寻找"){vm.findAgain()}
        }
    }
}
@Composable private fun Profile(vm:AppState){
    var name by rememberSaveable(vm.persona){mutableStateOf(vm.persona.name)}
    var address by rememberSaveable(vm.persona){mutableStateOf(vm.persona.address)}
    var introduction by rememberSaveable(vm.persona){mutableStateOf(vm.persona.introduction)}
    Column(Modifier.fillMaxSize().background(Chrome).verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(UiSpace.group)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){Avatar(Friend("self",vm.nickname,""),56);Column{Text("你好，${vm.nickname}",style=MaterialTheme.typography.titleLarge);Text("这是属于你的本地空间。",style=MaterialTheme.typography.bodySmall,color=Muted)}}
        AppField(name,{name=it.take(80)},"我的名字","nickname",singleLine=true)
        AppField(address,{address=it.take(80)},"希望被怎样称呼","persona-address",singleLine=true)
        AppField(introduction,{introduction=it.take(4000)},"自我介绍","persona-introduction",minLines=4)
        Text("这些资料会用于聊天，让角色更了解你。请只填写愿意发送给所选模型服务的信息。",style=MaterialTheme.typography.bodySmall,color=Muted)
        PrimaryAction("保存资料",enabled=!vm.busy,modifier=Modifier.align(Alignment.End)){vm.savePersona(UserPersona(name,address,introduction))}
        HorizontalDivider(color=Line)
        Text("${vm.friends.size} 位角色，累计 ${vm.messages.count{it.mine}} 条已发送消息",style=MaterialTheme.typography.bodyMedium,color=Muted)
        Text("资料保存在本机 · 无需注册",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}
