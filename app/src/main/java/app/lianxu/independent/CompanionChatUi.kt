package app.lianxu.independent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException

private data class CompanionControlsData(val role:String,val generation:Long,val failure:ContactFailure?,val retryable:Int=0)

/** ⓘ 点开后的一句大白话；页面上不常显说明。 */
internal object CompanionHints {
    const val CONTACT="她想你时会自己找你"
    const val NOTIFY="有消息时提醒你"
    const val RETRY="回访卡住时点这里重新安排"
    const val BACKGROUND="为了让她更有机会按约定联系你，建议在手机系统设置中允许恋叙后台运行，并将电池限制设为‘不受限制’。不同手机的名称可能不同，需要你自行设置；系统仍可能延迟，不能保证准点。"
}

/** 角色资料里「陪伴与消息」点进来的独立页：只有两个开关，说明点 ⓘ 才弹出一句。 */
@Composable internal fun CompanionSettingsScreen(vm:AppState){
    val role=vm.activeId
    var detail by remember(vm,role){mutableStateOf<CompanionControlsData?>(null)}
    var loadError by remember(vm,role){mutableStateOf(false)}
    var backgroundHint by remember(vm,role){mutableStateOf(false)}
    val loaded=detail?.role==role&&!loadError
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    fun allowed()=NotificationManagerCompat.from(context).areNotificationsEnabled()
    var notifications by remember{mutableStateOf(allowed())}
    var asked by remember{mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){notifications=allowed()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)notifications=allowed()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    fun changeNotifications(want:Boolean){
        val canAsk=want&&Build.VERSION.SDK_INT>=33&&!asked&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
        // 关掉通知或已经拒过一次：只有系统设置能改，带去本应用的通知设置。
        if(canAsk){asked=true;permission.launch(Manifest.permission.POST_NOTIFICATIONS)}
        else runCatching{context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    }
    LaunchedEffect(vm,role,vm.processingRevision){
        val generation=System.nanoTime()
        val policyBeforeRead=vm.companionPolicy
        try{
            val (policy,found)=Diagnostics.span("companion.controls.load",mapOf("role" to role,"generation" to generation)){
                val policy=vm.companion.policy(role).also{Diagnostics.event("companion.controls.policy_read")}
                policy to (vm.companion.latestContactFailure(role) to vm.companion.retryable(role))
            }
            withContext(Dispatchers.Main.immediate){
                if(vm.activeId==role){if(vm.companionPolicy==policyBeforeRead)vm.companionPolicy=policy;detail=CompanionControlsData(role,generation,found.first,found.second);loadError=false;Diagnostics.event("companion.controls.published",mapOf("role" to role,"generation" to generation))}
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){
            Diagnostics.failure("companion.controls.failed",e,mapOf("role" to role,"generation" to generation))
            withContext(Dispatchers.Main.immediate){if(vm.activeId==role)loadError=true}
        }
    }
    LaunchedEffect(detail?.generation,loadError){
        Diagnostics.event("companion.controls.rendered",mapOf("role" to role,"generation" to detail?.generation,"loaded" to loaded,"failed" to loadError))
    }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("companion-page")){
        Header("陪伴与消息",{vm.back()})
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper)){
                CompanionSwitchRow("允许主动联系",CompanionHints.CONTACT,"companion-enabled",loaded&&vm.companionPolicy=="enabled",loaded){vm.setCompanionEnabled(it){backgroundHint=true}}
                HorizontalDivider(Modifier.padding(horizontal=20.dp),color=Line,thickness=.5.dp)
                CompanionSwitchRow("允许消息通知",CompanionHints.NOTIFY,"companion-notification",notifications,true){changeNotifications(it)}
                val retryable=detail?.retryable?.takeIf{loaded}?:0
                if(retryable>0){
                    HorizontalDivider(Modifier.padding(horizontal=20.dp),color=Line,thickness=.5.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=8.dp,end=16.dp),verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={scope.launch{vm.companion.retryFailed(role);vm.settleCompanion();val count=vm.companion.retryable(role);if(vm.activeId==role)detail=detail?.copy(retryable=count)}},modifier=Modifier.testTag("companion-retry")){Text("重新安排未完成的回访（$retryable）",color=GreenText)}
                        InfoMark("重新安排未完成的回访",CompanionHints.RETRY,"companion-retry")
                    }
                }
            }
            detail?.failure?.takeIf{loaded}?.let{ContactFailureCard(it)}
            if(loadError)Text("读不到本地状态，返回后再试。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
    if(backgroundHint)AlertDialog(onDismissRequest={backgroundHint=false},modifier=Modifier.testTag("companion-background-hint"),title={Text("后台运行提示")},text={Text(CompanionHints.BACKGROUND)},confirmButton={TextButton(onClick={backgroundHint=false},modifier=Modifier.testTag("companion-background-close")){Text("知道了")}})
}

/** 放法 B：只在真失败过时出现的浅灰提示卡，点开看完整原始报错。 */
@Composable private fun ContactFailureCard(f:ContactFailure){
    var open by remember(f.at){mutableStateOf(false)}
    val shape=RoundedCornerShape(UiSpace.radius)
    Row(Modifier.fillMaxWidth().clip(shape).background(Chrome).border(.5.dp,Line,shape).clickable{open=true}.testTag("companion-last-failure")){
        Box(Modifier.padding(top=22.dp).width(3.dp).height(22.dp).background(Color(0xFFE6A3A3),RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f).padding(start=17.dp,end=20.dp,top=18.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("上次没发出去",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=Ink)
                Text(ContactFailureText.time(f.at),style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            Text(ContactFailureText.summary(f),Modifier.testTag("companion-last-failure-summary"),style=MaterialTheme.typography.bodyMedium,color=Ink)
            if(f.detail.isNotBlank())Text(ContactFailureText.preview(f.detail),style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("查看完整内容",Modifier.testTag("companion-last-failure-open"),style=MaterialTheme.typography.bodyMedium,color=GreenText)
        }
    }
    if(open)ChatFailureDialog(ContactFailureText.full(f)){open=false}
}

@Composable private fun CompanionSwitchRow(title:String,hint:String,tag:String,checked:Boolean,enabled:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(start=20.dp,end=16.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,style=MaterialTheme.typography.bodyLarge,color=Ink)
        InfoMark(title,hint,tag)
        Spacer(Modifier.weight(1f))
        Switch(checked,onChange,Modifier.testTag(tag),enabled=enabled)
    }
}

/** 灰色小 ⓘ：点一下在下方弹一句解释，点别处收起。 */
@Composable private fun InfoMark(title:String,hint:String,tag:String){
    var open by remember{mutableStateOf(false)}
    Box{
        IconButton(onClick={open=!open},modifier=Modifier.size(40.dp).testTag("$tag-info").semantics{contentDescription="${title}是什么意思"}){ControlIcon("info",Muted,18.dp)}
        if(open){
            val below=with(LocalDensity.current){38.dp.roundToPx()}
            Popup(alignment=Alignment.TopCenter,offset=IntOffset(0,below),onDismissRequest={open=false},properties=PopupProperties(focusable=true)){
                Text(hint,Modifier.shadow(3.dp,RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Paper).border(.5.dp,Line,RoundedCornerShape(10.dp)).padding(horizontal=14.dp,vertical=10.dp).testTag("$tag-hint"),style=MaterialTheme.typography.bodyMedium,color=Ink)
            }
        }
    }
}

@Composable internal fun CompanionReadMarker(vm:AppState,message:Message,bounds:Rect,viewport:Rect,enabled:Boolean,showLabel:Boolean){
    val receipt=vm.companionReceipts.firstOrNull{it.messageId==message.id}?:return
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val focused=LocalWindowInfo.current.isWindowFocused
    val visible=enabled&&MessageVisibility.eligible(bounds.top,bounds.bottom,bounds.left,bounds.right,viewport.top,viewport.bottom,viewport.left,viewport.right,lifecycle.isAtLeast(Lifecycle.State.RESUMED),focused)
    val currentlyVisible=rememberUpdatedState(visible)
    LaunchedEffect(message.id,visible,bounds,viewport,receipt.readAt){
        if(visible&&receipt.readAt==null){delay(600);vm.companionRead(message.friendId,message.id){currentlyVisible.value}}
    }
    // Older messages still become read when visible; only their label is hidden.
    if(showLabel)Text(if(receipt.readAt==null)"未读"else"已读",Modifier.testTag("read-${message.id}"),style=MaterialTheme.typography.labelSmall,color=Muted)
}
