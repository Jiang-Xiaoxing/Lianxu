package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

@Composable internal fun RoleArchiveScreen(vm:AppState){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    var selected by rememberSaveable{mutableStateOf("")};var records by rememberSaveable{mutableStateOf(false)}
    var incoming by remember{mutableStateOf<RoleArchive.Package?>(null)}
    var target by rememberSaveable{mutableStateOf("")}
    var preview by remember{mutableStateOf<RoleArchiveRepository.Preview?>(null)}
    var confirm by remember{mutableStateOf(false)}
    var output by remember{mutableStateOf<ByteArray?>(null)}
    fun work(block:suspend()->Unit){if(busy)return;busy=true;error="";status="";scope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error=when(e){is IllegalArgumentException,is IllegalStateException->e.message?:"文件不符合要求，请重新选择";else->"读取或保存未完成，请检查文件权限并重试"}}finally{busy=false}}}
    BackHandler(busy){ /* Keep a transaction's completion visible. */ }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->
        val bytes=output;output=null
        if(uri==null){status="已取消保存"}else if(bytes==null){error="导出资料已过期，请重新导出"}else work{
            withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")){"无法写入所选文件"}.use{it.write(bytes)}}
            status="已导出。含记录的包可能包含私人对话，请自行妥善保存。"
        }
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)work{incoming=null;preview=null;confirm=false;target=""
            incoming=withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openInputStream(uri)){"无法读取所选文件"}.use(RoleArchive::read)}
            status="已读取角色包，尚未导入。请选择新增或覆盖，再预览。"
        }
    }
    Column(Modifier.fillMaxSize()){
        AppTopBar("角色导入与导出",{if(!busy)vm.back()})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page).testTag("role-archive-page"),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("只处理选中的角色，不包含模型配置和密钥。仅支持恋叙角色包，不迁入星伴或其他旧包。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("archive-error"))
            if(status.isNotBlank())Text(status,modifier=Modifier.testTag("archive-status"))
            Text("导出角色",style=MaterialTheme.typography.titleMedium)
            if(vm.friends.isEmpty())Text("还没有角色，可以先导入或手动创建。",color=Muted)
            vm.friends.forEach{f->ArchiveChoice(f.name,selected==f.id,!busy,"archive-export-${f.id}"){selected=f.id}}
            ArchiveChoice("仅角色设定与表达提示",!records,!busy,"archive-role-only"){records=false}
            ArchiveChoice("角色及聊天、记忆、关系记录",records,!busy,"archive-with-records"){records=true}
            Text("不含运行任务、模型服务设置、派生缓存与附件。仅角色设定不含历史语气示例。对话中自行输入的私人信息仍会随聊天导出。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Button(enabled=!busy&&selected.isNotBlank(),onClick={work{
                val root=vm.roleArchive.export(selected,records)
                output=withContext(Dispatchers.IO){ByteArrayOutputStream().also{RoleArchive.write(root,it)}.toByteArray()}
                save.launch("恋叙角色.${RoleArchive.EXTENSION}")
            }},modifier=Modifier.heightIn(min=48.dp).testTag("archive-export")){Text("选择位置并导出")}
            HorizontalDivider(color=Line)
            Text("导入角色",style=MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled=!busy,onClick={open.launch(arrayOf("application/zip","application/octet-stream"))},modifier=Modifier.heightIn(min=48.dp).testTag("archive-open")){Text("选择角色包")}
            incoming?.let{pkg->
                Text(pkg.name,style=MaterialTheme.typography.titleSmall);Text(pkg.summary)
                ArchiveChoice("新增独立角色（推荐）",target.isEmpty(),!busy,"archive-new"){target="";preview=null}
                vm.friends.forEach{f->ArchiveChoice("覆盖：${f.name}",target==f.id,!busy,"archive-target-${f.id}"){target=f.id;preview=null}}
                Text("导入不会调用模型。历史语气示例保留文字但停用，关系评估保持关闭；请自行检查内容，再按需开启。",style=MaterialTheme.typography.bodySmall,color=Muted)
                OutlinedButton(enabled=!busy,onClick={work{preview=vm.roleArchive.preview(pkg,target.ifEmpty{null})}},modifier=Modifier.heightIn(min=48.dp).testTag("archive-preview")){Text("预览影响范围")}
                preview?.let{p->
                    Text(p.impact,modifier=Modifier.testTag("archive-impact"))
                    Button(enabled=!busy,onClick={if(p.target!=null)confirm=true else work{vm.importRoleArchive(p,false);incoming=null;preview=null;status="已新增角色。可到通讯录查看并开始聊天。"}},modifier=Modifier.heightIn(min=48.dp).testTag("archive-import")){Text(if(p.target==null)"确认新增角色" else "继续确认覆盖")}
                }
            }
        }
    }
    if(confirm)preview?.let{p->AlertDialog(onDismissRequest={confirm=false},title={Text("确认覆盖这个角色？")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(p.impact);Text("只影响上面指定的角色。取消不会改动资料。")}},confirmButton={TextButton(onClick={confirm=false;work{vm.importRoleArchive(p,true);incoming=null;preview=null;status="已覆盖所选范围，其他角色未改动。"}},modifier=Modifier.testTag("archive-confirm-overwrite")){Text("确认覆盖")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})}
}
@Composable private fun ArchiveChoice(label:String,selected:Boolean,enabled:Boolean,tag:String,onSelect:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected,enabled=enabled,role=Role.RadioButton,onClick=onSelect).testTag(tag),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        RadioButton(selected,onClick=null,enabled=enabled);Text(label,Modifier.padding(vertical=12.dp),color=if(enabled)Ink else Muted)
    }
}
