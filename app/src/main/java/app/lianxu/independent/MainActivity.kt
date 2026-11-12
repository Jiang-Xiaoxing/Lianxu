package app.lianxu.independent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
    private var notificationIntent by mutableStateOf<android.content.Intent?>(null)
    private fun consumeNotification(value:android.content.Intent):android.content.Intent? {
        val request=android.content.Intent(value)
        value.removeExtra(CompanionNotifications.ROLE);value.removeExtra(CompanionNotifications.MESSAGE)
        if(value.data?.scheme=="lianxu")value.data=null
        return request.takeIf{!it.getStringExtra(CompanionNotifications.ROLE).isNullOrBlank()&&it.getLongExtra(CompanionNotifications.MESSAGE,0)>0}
    }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);notificationIntent=consumeNotification(intent);enableEdgeToEdge();setContent{LianxuTheme{
        val vm:AppState=viewModel()
        LaunchedEffect(vm.ready,notificationIntent){if(vm.ready){notificationIntent?.let{i->val role=i.getStringExtra(CompanionNotifications.ROLE);val id=i.getLongExtra(CompanionNotifications.MESSAGE,0);if(role!=null&&id>0)vm.notificationOpen(role,id)};notificationIntent=null}}
        Lianxu(vm)
    }}}
    override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);notificationIntent=consumeNotification(intent)}
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
    // Role generation belongs to the ViewModel; backgrounding only pauses its animation.
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_START)vm.foreground()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    BackHandler(vm.page!="home"){vm.back()}
    CompositionLocalProvider(LocalRoleAvatars provides vm.roleAvatars){Surface(Modifier.fillMaxSize(),color=Chrome){Column(Modifier.safeDrawingPadding().imePadding()){
        if(vm.error.isNotEmpty())Text(vm.error,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onErrorContainer)
        if(!vm.ready)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        else when(vm.page){
            "home"->Home(vm)
            "chat"->chatState.SaveableStateProvider("chat-${vm.activeId}"){Chat(vm)}
            "detail"->Detail(vm)
            "role-prompts"->RolePromptsScreen(vm)
            "role-learning"->LearningRecordsScreen(vm)
            "role-relationship"->RelationshipScreen(vm)
            "role-companion"->CompanionSettingsScreen(vm)
            "create"->Create(vm)
            "role-archive"->RoleArchiveScreen(vm)
            "search"->RoleSearch(vm)
            "search-detail"->GeneratedRoleDetail(vm)
            "models"->ModelConfigScreen({vm.back()})
            "processing"->ProcessingDirectory(vm)
            "processing-role"->ProcessingRole(vm)
            "settings-memory"->MemoryRulesScreen(vm)
            "settings-display"->DisplaySettings(vm)
            "settings-info"->DataAndVersion({vm.back()})
            "settings-updates"->UpdatesScreen({vm.back()})
        }
    }}}
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
        Row(Modifier.fillMaxWidth().heightIn(min=80.dp).background(Chrome).padding(start=24.dp,end=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){
                Text(labels[vm.tab].let{if(vm.tab==0)"恋叙" else it},Modifier.testTag("home-title"),style=BrandStyle)
                if(vm.tab==0){Spacer(Modifier.width(2.dp));CompanionBirds(Modifier.weight(1f,fill=false).widthIn(max=104.dp))}
            }
            if(vm.tab<=1)Box{
                val addBg=if(isSystemInDarkTheme())Paper else GreenText
                val addOn=if(isSystemInDarkTheme())GreenText else MaterialTheme.colorScheme.onPrimary
                FilledTonalIconButton(onClick={menu=true},modifier=Modifier.size(UiSpace.action).testTag("add-menu").semantics{contentDescription="添加角色"},colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=addBg,contentColor=addOn)){ControlIcon("add",addOn)}
                DropdownMenu(menu,{menu=false}){
                    DropdownMenuItem(text={Text("寻找角色")},leadingIcon={ControlIcon("search")},onClick={menu=false;vm.startSearch()})
                    DropdownMenuItem(text={Text("创建角色")},leadingIcon={ControlIcon("add")},onClick={menu=false;vm.open("create")})
                }
            }
        }
        Box(Modifier.weight(1f).background(Paper)){when(vm.tab){
            0->LazyColumn(Modifier.fillMaxSize().testTag("chat-list")){
                item{SectionLabel("最近对话",Modifier.padding(horizontal=UiSpace.page,vertical=8.dp))}
                if(vm.friends.isEmpty())item{EmptyRoles("roles-empty-chat"){vm.open("create")}}
                items(vm.friends,key={it.id}){friend->val message=vm.messages.lastOrNull{it.friendId==friend.id};FriendRow(friend,message?.text?:"开始聊天",message?.let{time.format(Date(it.created))}.orEmpty(),!removing,{askDelete(friend)}){vm.open("chat",friend.id)}}
            }
            1->LazyColumn(Modifier.fillMaxSize().testTag("contacts-list")){
                item{SectionLabel("全部角色 · ${vm.friends.size} 位",Modifier.padding(horizontal=UiSpace.page,vertical=8.dp))}
                if(vm.friends.isEmpty())item{EmptyRoles("roles-empty-contacts"){vm.open("create")}}
                items(vm.friends,key={it.id}){friend->FriendRow(friend,friend.bio,"",!removing,{askDelete(friend)},2){vm.open("detail",friend.id)}}
            }
            2->SettingsDirectory(vm)
            3->Profile(vm)
        }}
        HorizontalDivider(color=Line,thickness=.5.dp)
        NavigationBar(containerColor=Chrome,tonalElevation=0.dp,windowInsets=WindowInsets(0,0,0,0)){labels.forEachIndexed{index,label->
            NavigationBarItem(selected=index==vm.tab,onClick={vm.tab=index},icon={NavigationIcon(index,index==vm.tab)},label={Text(label,style=MaterialTheme.typography.labelMedium,maxLines=1)},modifier=Modifier.testTag("tab-$index"),colors=NavigationBarItemDefaults.colors(selectedIconColor=GreenText,selectedTextColor=GreenText,indicatorColor=SelectedBackground,unselectedIconColor=Muted,unselectedTextColor=Muted))
        }}
    }
}
@Composable private fun EmptyRoles(tag:String,onCreate:()->Unit){
    Column(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=40.dp).testTag(tag),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("从认识一个人开始",style=MaterialTheme.typography.titleLarge)
        Text("创建属于你的角色，聊天、记忆和关系会在这里慢慢留下。",style=MaterialTheme.typography.bodyMedium,color=Muted)
        PrimaryAction("创建角色",onClick=onCreate)
    }
}
@Composable private fun FriendRow(friend:Friend,subtitle:String,meta:String,deleteEnabled:Boolean,onDelete:()->Unit,subtitleLines:Int=1,onClick:()->Unit){
    var actions by remember(friend.id){mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=UiSpace.page,vertical=16.dp),verticalAlignment=Alignment.CenterVertically){
        Avatar(friend,52);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(friend.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis);if(meta.isNotEmpty())Text(meta,style=MaterialTheme.typography.labelSmall,color=Muted)}
            Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=Muted,maxLines=subtitleLines,overflow=TextOverflow.Ellipsis)
        }
        Box{
            IconButton(onClick={actions=true},enabled=deleteEnabled,modifier=Modifier.size(UiSpace.action).testTag("role-actions-${friend.id}").semantics{contentDescription="${friend.name}的更多操作"}){ControlIcon("more")}
            DropdownMenu(actions,{actions=false}){DropdownMenuItem(text={Text("删除角色",color=MaterialTheme.colorScheme.error)},onClick={actions=false;onDelete()},modifier=Modifier.testTag("role-delete-${friend.id}"))}
        }
    };HorizontalDivider(Modifier.padding(start=86.dp,end=UiSpace.page),color=Line,thickness=.5.dp)
}
@Composable private fun Detail(vm:AppState){
    val friend=vm.friends.find{it.id==vm.activeId}?:return
    Column(Modifier.fillMaxSize().background(Chrome)){
        Header("角色资料",{vm.back()})
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
            val hasAvatar=vm.roleAvatars[friend.id]!=null
            val context=androidx.compose.ui.platform.LocalContext.current
            val pickAvatar=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
                if(uri==null)return@rememberLauncherForActivityResult
                val bytes=context.contentResolver.openInputStream(uri)?.use{it.readBytes()}
                if(bytes==null||bytes.isEmpty()){return@rememberLauncherForActivityResult}
                val mime=context.contentResolver.getType(uri)?:"image/jpeg"
                vm.setRoleAvatar(friend.id,mime,bytes)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper).padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                // 点头像本身就能换头像，旁边不再放绿色文字链接。
                Box(Modifier.clickable(role=androidx.compose.ui.semantics.Role.Button){pickAvatar.launch("image/*")}.testTag("role-avatar").semantics{contentDescription=if(hasAvatar)"更换头像"else"给她选张头像"}){Avatar(friend,64)}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Text(friend.name,style=MaterialTheme.typography.titleLarge)
                    Text("AI 角色 · 本地创建",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("角色介绍",style=MaterialTheme.typography.titleSmall)
                Text(friend.bio,style=MaterialTheme.typography.bodyLarge)
                HorizontalDivider(color=Line)
                Text("角色提示词",style=MaterialTheme.typography.titleSmall)
                Text(friend.prompt.ifBlank{"温柔、自然地回应，尊重用户。不要求披露内部思考。"},style=MaterialTheme.typography.bodyMedium,color=Muted)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper)){
                DirectoryRow("edit","编辑角色设定","名字、说话方式与开场白","role-prompts-open",iconColor=ProfileIconColors.edit){vm.open("role-prompts",friend.id)}
                DirectoryRow("memory","记忆","记住的内容与原话来源","role-processing-open",iconColor=ProfileIconColors.memory){vm.open("processing-role",friend.id)}
                DirectoryRow("list","学习记录","看法、兴趣与相处习惯","role-learning-open",iconColor=ProfileIconColors.learning){vm.open("role-learning",friend.id)}
                DirectoryRow("heart","我们的相处","她现在的心情、信任和放在心上的事","role-relationship-open",iconColor=ProfileIconColors.relationship){vm.open("role-relationship",friend.id)}
                DirectoryRow("chat","陪伴与消息","主动联系与通知","role-companion-open",iconColor=ProfileIconColors.companion){vm.open("role-companion",friend.id)}
            }
            PrimaryAction("发消息",modifier=Modifier.fillMaxWidth()){vm.open("chat",friend.id)}
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
@Composable private fun Profile(vm:AppState){
    var name by rememberSaveable(vm.persona){mutableStateOf(vm.persona.name)}
    var address by rememberSaveable(vm.persona){mutableStateOf(vm.persona.address)}
    var introduction by rememberSaveable(vm.persona){mutableStateOf(vm.persona.introduction)}
    Column(Modifier.fillMaxSize().background(Chrome).verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(UiSpace.group)){
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper).padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){Avatar(Friend("self",vm.nickname,""),64);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(vm.nickname,style=MaterialTheme.typography.titleLarge);Text("${vm.friends.size} 位角色 · ${vm.messages.count{it.mine}} 条已发送消息",style=MaterialTheme.typography.bodySmall,color=Muted)}}
        SectionLabel("聊天中的你")
        AppField(name,{name=it.take(80)},"我的名字","nickname",singleLine=true)
        AppField(address,{address=it.take(80)},"希望被怎样称呼","persona-address",singleLine=true)
        AppField(introduction,{introduction=it.take(4000)},"自我介绍","persona-introduction",minLines=4)
        Text("这些资料会用于聊天，让角色更了解你。请只填写愿意发送给所选模型服务的信息。",style=MaterialTheme.typography.bodySmall,color=Muted)
        PrimaryAction("保存资料",enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){vm.savePersona(UserPersona(name,address,introduction))}
        Text("资料保存在本机 · 无需注册",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}
