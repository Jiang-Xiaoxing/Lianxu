package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.FilterOutputStream
import java.io.OutputStream

private class PendingBackup(val archive:BackupRepository.Export?=null,val bytes:ByteArray?=null){
    fun clear(){bytes?.fill(0);archive?.files?.values?.forEach{it.fill(0)}}
    override fun toString()="PendingBackup(redacted)"
}

@Composable internal fun RoleArchiveScreen(vm:AppState){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)};var job by remember{mutableStateOf<Job?>(null)};var lease by remember{mutableStateOf<ChatAttemptLease?>(null)}
    var selected by rememberSaveable{mutableStateOf("")};var detailed by rememberSaveable{mutableStateOf(false)}
    var status by remember{mutableStateOf("")};var error by remember{mutableStateOf("")};var output by remember{mutableStateOf<PendingBackup?>(null)}
    var incoming by remember{mutableStateOf<BackupArchive.Package?>(null)};var mode by remember{mutableStateOf("")};var target by remember{mutableStateOf("")}
    var chooseKeys by remember{mutableStateOf(false)};var chooseMode by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf(false)}
    var preview by remember{mutableStateOf<BackupRepository.Preview?>(null)};var legacyPreview by remember{mutableStateOf<RoleArchiveRepository.Preview?>(null)}
    val impact=preview?.impact?:legacyPreview?.impact
    fun clearPreview(){preview=null;legacyPreview=null;confirm=false}
    fun work(block:suspend(ChatAttemptLease)->Unit){if(busy)return;busy=true;error="";status="";val active=ChatAttemptLease();lease=active
        job=scope.launch(Diagnostics.context("backup.page")){try{block(active)}catch(e:CancellationException){status=if(active.wasCommitted())"导入已完成，资料已刷新；取消到达时事务已经提交。"else"已取消，已保存资料保留。";throw e}catch(e:Exception){Diagnostics.failure("backup.page.failed",e);error=if(e.message?.contains("重新预览")==true)"资料、配置或备份内容已变化，请重新预览。"else"处理未完成，请检查文件格式、文件权限与可用空间后重试。"}finally{busy=false;lease=null}}
    }
    BackHandler(busy){lease?.cancel();job?.cancel()}
    DisposableEffect(Unit){onDispose{lease?.cancel();output?.clear()}}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val pending=output;output=null
        if(uri==null){pending?.clear();status="已取消保存"}else if(pending==null){error="导出资料已过期，请重新导出"}else work{active->
            try{withContext(Dispatchers.IO){check(active.isActive());requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{raw->
                val checked=object:FilterOutputStream(raw){
                    override fun write(b:Int){check(active.isActive()){ "保存已取消" };out.write(b)}
                    override fun write(b:ByteArray,off:Int,len:Int){check(active.isActive()){ "保存已取消" };out.write(b,off,len)}
                }
                pending.archive?.let{BackupArchive.write(it.root,it.files,checked)}?:checked.write(requireNotNull(pending.bytes))
            }};status="已保存。备份和详细日志可能含私密资料，请妥善保管。"}finally{pending.clear()}
        }
    }
    fun export(id:String?,keys:Boolean){work{active->val snapshot=vm.exportBackup(id,keys)
        if(!active.isActive()){snapshot.files.values.forEach{it.fill(0)};return@work};output=PendingBackup(archive=snapshot)
        save.launch(if(id!=null)"恋叙角色完整备份.lianxu.zip"else if(keys)"恋叙全部备份-含密钥请勿分享.lianxu.zip"else"恋叙全部备份.lianxu.zip")
    }}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)work{
        incoming=null;clearPreview();mode="";target=""
        incoming=withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openInputStream(uri)).use(BackupArchive::read)}
        chooseMode=true;status="已读取文件，尚未导入。请先选择新增或覆盖。"
    }}
    fun plan(){val pack=incoming?:return;work{clearPreview();if(pack.legacy!=null)legacyPreview=vm.roleArchive.preview(pack.legacy,if(mode=="REPLACE")target else null)else preview=vm.backup.preview(pack,mode,if(mode=="REPLACE"&&!pack.all)target else null)}}
    fun apply(confirmed:Boolean){val p=preview;val old=legacyPreview;work{active->
        if(p!=null)vm.importBackup(p,confirmed,active)else if(old!=null)vm.importRoleArchive(old,confirmed,active)else error("预览已过期")
        incoming=null;clearPreview();mode="";status=if(confirmed)"已按预览范围恢复，未自动请求模型。"else"已新增包内角色，当前角色和模型设置保留。"
    }}
    Column(Modifier.fillMaxSize()){
        AppTopBar("备份与诊断",{if(!busy)vm.back()})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page).testTag("role-archive-page"),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton({lease?.cancel();job?.cancel()},modifier=Modifier.testTag("backup-cancel")){Text("取消本次处理")}}
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("archive-error"))
            if(status.isNotBlank())Text(status,modifier=Modifier.testTag("archive-status"))
            Text("导出一个角色",style=MaterialTheme.typography.titleMedium)
            Text("带出该角色全部已保存的人设、提示词、聊天、记忆与来源、候选及关联、关系及快照、语气示例和资源；不含其他角色或全局密钥。",style=MaterialTheme.typography.bodySmall,color=Muted)
            vm.friends.forEach{f->TextButton({selected=f.id},enabled=!busy,modifier=Modifier.testTag("archive-export-${f.id}")){Text((if(selected==f.id)"● "else"○ ")+f.name)}}
            Button({export(selected,false)},enabled=!busy&&selected.isNotBlank(),modifier=Modifier.heightIn(min=48.dp).testTag("archive-export")){Text("导出这个角色（全部资料）")}
            HorizontalDivider(color=Line)
            Text("全部备份",style=MaterialTheme.typography.titleMedium)
            Text("备份全部角色、资源和软件设置、API服务及模型配置。点击后再选择是否包含密钥；不设置备份密码。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Button({chooseKeys=true},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("backup-export-all")){Text("导出全部")}
            Text("当前头像为按角色姓名生成的图形。已保存资源可随包保留；本页没有增加头像上传、附件发送或图片理解功能。",style=MaterialTheme.typography.bodySmall,color=Muted)
            HorizontalDivider(color=Line)
            Text("从文件导入",style=MaterialTheme.typography.titleMedium)
            OutlinedButton({open.launch(arrayOf("application/zip","application/octet-stream"))},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("archive-open")){Text("选择备份文件")}
            incoming?.let{pack->
                Text(if(pack.legacy!=null)"旧格式：${pack.legacy.name}。${pack.legacy.summary}"else if(pack.all)"全部备份：${pack.roles.size()} 个角色；${if(pack.includesKeys)"包含密钥，请勿分享"else"不包含密钥"}"else"单角色完整备份")
                Text("导入不自动调用模型，不恢复在途任务。旧格式缺少的资料无法凭空补齐。",style=MaterialTheme.typography.bodySmall,color=Muted)
                TextButton({chooseMode=true},enabled=!busy,modifier=Modifier.testTag("backup-choose-mode")){Text(if(mode.isBlank())"选择新增或覆盖"else if(mode=="ADD")"导入方式：新增"else"导入方式：覆盖")}
                if(mode=="REPLACE"&&!pack.all)vm.friends.forEach{f->TextButton({target=f.id;clearPreview()},enabled=!busy,modifier=Modifier.testTag("archive-target-${f.id}")){Text((if(target==f.id)"● "else"○ ")+"覆盖：${f.name}")}}
                if(mode.isNotBlank())OutlinedButton({plan()},enabled=!busy&&(mode=="ADD"||pack.all||target.isNotBlank()),modifier=Modifier.heightIn(min=48.dp).testTag("archive-preview")){Text("预览影响范围")}
                if(impact!=null){Text(impact,modifier=Modifier.testTag("archive-impact"));Button({if(mode=="REPLACE")confirm=true else apply(false)},enabled=!busy,modifier=Modifier.testTag("archive-import")){Text(if(mode=="REPLACE")"继续确认覆盖"else"确认新增角色")}}
            }
            HorizontalDivider(color=Line)
            Text("诊断日志",style=MaterialTheme.typography.titleMedium)
            Text("仅在本机滚动保留最近7天、最多10 MiB，不自动上传。详细日志可能含聊天、记忆和提示词，请勿随意分享；密钥和令牌始终脱敏。",style=MaterialTheme.typography.bodySmall,color=Muted)
            OutlinedButton({work{output=PendingBackup(bytes=Diagnostics.export(detailed));save.launch("恋叙诊断-${if(detailed)"详细"else"简版"}.jsonl")}},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).testTag("backup-export-diagnostics")){Text("导出诊断日志")}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("开启详细诊断",style=MaterialTheme.typography.titleSmall);Switch(detailed,{detailed=it},enabled=!busy,modifier=Modifier.testTag("backup-detailed"))}
        }
    }
    if(chooseKeys)AlertDialog(onDismissRequest={chooseKeys=false},title={Text("是否包含 API 密钥？")},text={Text("不包含密钥也会备份服务和模型设置。包含后，其他手机导入可直接使用你的 API 密钥；别人拿到文件也能使用并消耗额度，请勿分享。备份没有密码保护，应按敏感文件保管。返回可取消。")},confirmButton={TextButton({chooseKeys=false;export(null,false)},modifier=Modifier.testTag("backup-without-keys")){Text("不包含密钥（推荐）")}},dismissButton={TextButton({chooseKeys=false;export(null,true)},modifier=Modifier.testTag("backup-with-keys")){Text("包含密钥")}})
    if(chooseMode)AlertDialog(onDismissRequest={chooseMode=false},title={Text("选择导入方式")},text={Text("新增只添加包内角色及其资料，保留当前角色和模型设置，不启用包内凭据。覆盖单角色需明确选择目标；覆盖全部备份会替换整个软件的数据和设置，下一步先预览范围。")},confirmButton={TextButton({chooseMode=false;mode="ADD";target="";clearPreview()},modifier=Modifier.testTag("archive-mode-add")){Text("新增")}},dismissButton={TextButton({chooseMode=false;mode="REPLACE";target="";clearPreview()},modifier=Modifier.testTag("archive-mode-replace")){Text("覆盖")}})
    if(confirm&&impact!=null)AlertDialog(onDismissRequest={confirm=false},title={Text("确认按预览范围覆盖？")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(impact);Text("请先保存可用备份。取消不会改动资料；失败时事务回滚，不向任何服务自动发送。")}},confirmButton={TextButton({confirm=false;apply(true)},modifier=Modifier.testTag("archive-confirm-overwrite")){Text("确认覆盖")}},dismissButton={TextButton({confirm=false}){Text("取消")}})
}
