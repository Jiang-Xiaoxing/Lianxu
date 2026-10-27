package app.lianxu.independent

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

private val ConfigBackground:Color @Composable get()=Chrome
private val ConfigInk:Color @Composable get()=Ink
private val ConfigMuted:Color @Composable get()=Muted
private val ConfigGreen:Color @Composable get()=GreenText
private val ConfigLine:Color @Composable get()=Line
private val ConfigErrorColor:Color @Composable get()=MaterialTheme.colorScheme.error
private fun providerName(p:ProviderPreset)=p.label.substringBefore(" · ")
private fun regionName(p:ProviderPreset)=p.label.substringAfter(" · ","")
internal fun providerMatches(p:ProviderPreset,query:String):Boolean {
    val alias=when(p.id){"silicon-cn"->"siliconflow";"bailian-cn"->"aliyun qwen";"ark-cn"->"volcengine doubao";"zhipu-cn"->"glm bigmodel";"mimo"->"xiaomi";else->""}
    return "${p.label} ${p.id} $alias".contains(query.trim(),ignoreCase=true)
}

@Composable fun ModelConfigScreen(onBack:()->Unit,state:ModelConfigState=viewModel()) {
    LaunchedEffect(Unit){state.refresh()}
    var screen by remember {mutableStateOf("overview")}
    var providerReturn by remember {mutableStateOf("overview")}
    var original by remember {mutableStateOf<ModelConfig?>(null)}
    var draftId by remember {mutableStateOf(UUID.randomUUID().toString())}
    var name by remember {mutableStateOf("")};var provider by remember {mutableStateOf("silicon-cn")}
    var base by remember {mutableStateOf("")};var model by remember {mutableStateOf("")}
    var key by remember {mutableStateOf("")};var reveal by remember {mutableStateOf(false)}
    var capacity by remember {mutableStateOf("16384")};var output by remember {mutableStateOf("2048")}
    var query by remember {mutableStateOf("")};var advanced by remember {mutableStateOf(false)}
    var modelQuery by remember {mutableStateOf("")}
    var deleting by remember {mutableStateOf<ModelConfig?>(null)}
    val activity=LocalActivity.current ?: return
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun draft()=ModelConfig(draftId,name.trim(),provider,base.trim().trimEnd('/'),model.trim(),capacity.toIntOrNull()?:0,output.toIntOrNull()?:0)
    fun back(){
        if(state.saving)return
        state.clearResult()
        if(screen=="providers"){screen=providerReturn;return}
        key="";reveal=false
        if(screen=="edit")screen="overview" else onBack()
    }
    fun add(){if(state.saving)return;original=null;draftId=UUID.randomUUID().toString();name="";key="";reveal=false;model="";query="";providerReturn="overview";state.clearResult();screen="providers"}
    fun edit(config:ModelConfig){original=config;draftId=config.id;name=config.name;provider=config.providerId;base=config.baseUrl;model=config.modelId;capacity=config.contextTokens.toString();output=config.outputTokens.toString();key="";reveal=false;advanced=false;state.clearResult();screen="edit"}
    fun changed(){modelQuery="";state.clearResult()}
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP){key="";reveal=false;state.clearResult()}}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);state.clearResult();activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    SideEffect{if(reveal)activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    BackHandler{back()}
    val preset=ProviderPresets.get(provider)
    Surface(Modifier.fillMaxSize(),color=ConfigBackground){Column {
        AppTopBar(
            title=when(screen){"providers"->"选择服务商";"edit"->if(original==null)"添加 ${providerName(preset)}" else "编辑配置";else->"我的模型"},
            onBack={back()},backTag="config-back",enabled=!state.saving,
            extra={when(screen){
                "edit"->SaveAction(!state.busy){reveal=false;state.save(draft(),key){key="";screen="overview"}}
                "overview"->IconButton(onClick={add()},modifier=Modifier.size(UiSpace.action).testTag("config-add-top").semantics{contentDescription="添加服务商"}){ControlIcon("add")}
                else->Spacer(Modifier.width(48.dp))
            }}
        )
        if(screen=="overview"){
            LazyColumn(Modifier.fillMaxSize().testTag("config-overview"),contentPadding=PaddingValues(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
                item{Text("管理你保存的服务与模型。",color=ConfigMuted,style=MaterialTheme.typography.bodyMedium)}
                if(state.status.isNotBlank())item{Notice(state.status,state.statusError)}
                item{PurposeSelection(state)}
                item{Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
                    if(state.configs.isEmpty())Text("还没有保存的服务",Modifier.padding(22.dp),color=ConfigMuted)
                    state.configs.forEachIndexed {index,config->
                        val p=ProviderPresets.get(config.providerId)
                        Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy){edit(config)}.testTag("config-edit-${config.id}").padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                            ProviderMark(p);Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text(config.name,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text("${providerName(p)} · ${config.modelId}",style=MaterialTheme.typography.bodySmall,color=ConfigMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                val result=state.connections[config.id]
                                Text(when(result?.phase){ProbePhase.REACHABLE->"上次测试可达";ProbePhase.FAILED->"上次测试未通过";else->"未测试"},Modifier.testTag("config-probe-${config.id}"),style=MaterialTheme.typography.bodySmall,color=when(result?.phase){ProbePhase.REACHABLE->ConfigGreen;ProbePhase.FAILED->ConfigErrorColor;else->ConfigMuted})
                            }
                            if(config.id==state.defaultId)Text("默认",Modifier.padding(horizontal=8.dp).clip(RoundedCornerShape(8.dp)).background(SelectedBackground).padding(horizontal=8.dp,vertical=4.dp),color=ConfigGreen,style=MaterialTheme.typography.bodySmall)
                            ControlIcon("chevron")
                        }
                        if(index<state.configs.lastIndex)HorizontalDivider(Modifier.padding(start=72.dp,end=18.dp),color=ConfigLine)
                    }
                }}
                item{Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper).clickable{add()}.testTag("config-add").padding(18.dp),verticalAlignment=Alignment.CenterVertically){ControlIcon("add");Spacer(Modifier.width(14.dp));Text("添加服务商",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);ControlIcon("chevron")}}
                item{Text("保存与用途选择不发起请求。文字聊天按发送时调用，搜索AI仍为演示。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
            }
        }else if(screen=="providers"){
            OutlinedTextField(query,{query=it.take(80)},placeholder={Text("搜索服务商")},singleLine=true,leadingIcon={ControlIcon("search")},modifier=Modifier.fillMaxWidth().padding(horizontal=UiSpace.page,vertical=12.dp).testTag("provider-search"),shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=Paper,focusedContainerColor=Paper,unfocusedBorderColor=Color.Transparent))
            val filtered=ProviderPresets.all.filter{providerMatches(it,query)}
            LazyColumn(Modifier.weight(1f).padding(horizontal=UiSpace.page).clip(RoundedCornerShape(18.dp)).background(Paper).testTag("provider-list"),contentPadding=PaddingValues(vertical=4.dp)){
                if(filtered.isEmpty())item{Text("没有找到，可选择自定义兼容服务。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium,color=ConfigMuted)}
                items(filtered,key={it.id}){p->
                    Row(Modifier.fillMaxWidth().clickable{
                        val old=ProviderPresets.get(provider)
                        if(original==null||name.isBlank()||name==providerName(old))name=providerName(p)
                        provider=p.id;base=p.base;model="";key="";reveal=false;advanced=p.base.isBlank();changed();screen="edit"
                    }.testTag("provider-${p.id}").padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                        ProviderMark(p);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(providerName(p),style=MaterialTheme.typography.titleSmall);if(regionName(p).isNotBlank())Text(regionName(p),style=MaterialTheme.typography.labelSmall,color=ConfigMuted,modifier=Modifier.padding(top=3.dp))};ControlIcon("chevron")
                    };HorizontalDivider(Modifier.padding(start=72.dp,end=18.dp),color=ConfigLine)
                }
            }
            Text("仅提供配置预设，不代表已连接模型。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
        }else{
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("config-editor").padding(horizontal=UiSpace.page,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Row(Modifier.fillMaxWidth().clickable(enabled=!state.saving){state.clearResult();modelQuery="";query="";providerReturn="edit";screen="providers"}.testTag("provider-picker").padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){ProviderMark(preset);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(providerName(preset),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium);Text(regionName(preset).ifBlank{"OpenAI 兼容配置"},style=MaterialTheme.typography.bodySmall,color=ConfigMuted)};Text("更换",color=ConfigGreen,style=MaterialTheme.typography.bodySmall)}
                if(state.status.isNotBlank()&&!state.fetching&&state.models.isEmpty())Notice(state.status,state.statusError)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper).padding(horizontal=16.dp,vertical=6.dp)){
                    ConfigField(name,{name=it.take(60);changed()},"配置名称","config-name")
                    val retained=original?.baseUrl==base.trim().trimEnd('/')&&original?.providerId==provider
                    TextField(key,{key=it.take(4096);changed()},label={Text("API Key")},placeholder={Text(if(retained)"留空沿用已保存的密钥" else "输入你的密钥")},singleLine=true,visualTransformation=if(reveal)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={TextButton(onClick={reveal=!reveal},modifier=Modifier.semantics{contentDescription=if(reveal)"隐藏密钥" else "显示当前输入的密钥"}){Text(if(reveal)"隐藏" else "显示",style=MaterialTheme.typography.bodySmall)}},colors=fieldColors(),modifier=Modifier.fillMaxWidth().testTag("config-key"))
                    ConfigField(model,{model=it.take(200);changed()},"模型 ID / Endpoint ID","config-model",placeholder="选择或填写模型")
                    if(preset.modelsPath!=null)Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically){
                        if(state.fetching)TextButton(onClick={state.cancelFetch()},modifier=Modifier.testTag("config-fetch-cancel")){Text("取消",style=MaterialTheme.typography.bodySmall)}
                        TextButton(onClick={reveal=false;modelQuery="";state.request(draft(),key,true)},enabled=!state.busy,modifier=Modifier.testTag("config-fetch")){
                            if(state.fetching){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                            Text(if(state.fetching)"正在获取模型…" else if(state.statusError)"重新获取" else "获取模型",color=if(state.fetching)ConfigMuted else Color.Unspecified,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    else Text(preset.modelsHint,Modifier.padding(vertical=12.dp,horizontal=16.dp).testTag("config-models-unavailable"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                }
                if(state.models.isNotEmpty()){
                    Notice(state.status,false)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper).padding(12.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("选择模型",style=MaterialTheme.typography.labelLarge);Text("点选填入，保存后生效",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                        OutlinedTextField(modelQuery,{modelQuery=it.take(200)},placeholder={Text("搜索模型 ID",style=MaterialTheme.typography.bodyMedium)},singleLine=true,leadingIcon={ControlIcon("search")},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).testTag("model-search"),shape=RoundedCornerShape(12.dp))
                        val filtered=state.models.filter{it.contains(modelQuery,ignoreCase=true)}
                        if(filtered.isEmpty())Text("没有匹配的模型，可继续手填。",Modifier.padding(12.dp).testTag("model-options-empty"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max=220.dp).testTag("model-options")){
                            items(filtered,key={it}){id->val selected=id==model
                                Row(Modifier.fillMaxWidth().heightIn(min=UiSpace.action).clip(RoundedCornerShape(9.dp)).background(if(selected)SelectedBackground else Paper).clickable{model=id;changed()}.semantics{this.selected=selected}.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Text(id,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=if(selected)ConfigGreen else ConfigInk);if(selected)Text("✓ 当前",Modifier.padding(start=8.dp),style=MaterialTheme.typography.bodySmall,color=ConfigGreen)}
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth()){
                    if(preset.testPath!=null)Row(Modifier.heightIn(min=48.dp).clickable(enabled=!state.busy,role=Role.Button){reveal=false;state.request(draft(),key,false)}.testTag("config-test").padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){ControlIcon("plug");Spacer(Modifier.width(8.dp));Text(if(state.probe.phase==ProbePhase.TESTING)"正在测试…" else if(state.probe.phase==ProbePhase.FAILED)"重新测试" else "测试连接",color=ConfigGreen,style=MaterialTheme.typography.bodyMedium);if(state.probe.phase==ProbePhase.TESTING)CircularProgressIndicator(Modifier.padding(start=12.dp).size(18.dp),strokeWidth=2.dp)}
                    else Row(Modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){ControlIcon("plug");Spacer(Modifier.width(8.dp));Text("此服务商暂未适配连接测试",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                    val result=state.probe
                    Text(when(result.phase){ProbePhase.UNTESTED->"未测试";ProbePhase.TESTING->"正在查询目录/认证接口…";else->result.text},Modifier.padding(start=4.dp,top=2.dp).testTag("config-probe-inline"),style=MaterialTheme.typography.bodySmall,color=when(result.phase){ProbePhase.REACHABLE->ConfigGreen;ProbePhase.FAILED->ConfigErrorColor;else->ConfigMuted})
                    Text("仅点击测试或获取模型时发送请求；目录条目不保证都支持聊天。",Modifier.padding(start=4.dp,top=8.dp),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
                    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable{advanced=!advanced}.testTag("config-advanced").padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("高级设置",style=MaterialTheme.typography.labelLarge);Text("服务地址与兼容方式",style=MaterialTheme.typography.bodySmall,color=ConfigMuted,modifier=Modifier.padding(top=4.dp))};ControlIcon(if(advanced)"up" else "chevron")}
                    if(advanced)Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        ConfigField(base,{base=it.take(500);key="";reveal=false;changed()},"服务地址（HTTPS）","config-url",keyboard=KeyboardType.Uri)
                        ConfigField(capacity,{capacity=it.filter(Char::isDigit).take(7);changed()},"上下文容量（按模型文档）","config-context",keyboard=KeyboardType.Number)
                        ConfigField(output,{output=it.filter(Char::isDigit).take(4);changed()},"输出预留 Token","config-output",keyboard=KeyboardType.Number)
                        Text("本地预算使用保守估算；请按所选模型实际容量填写，不会自动裁掉未整理原话。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)

                        Text("OpenAI 兼容 · "+(if(preset.authHeader=="api-key")"api-key 鉴权" else "Bearer 鉴权"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        Text(preset.hint,style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        Text("改变地址或服务商需重新输入密钥。显示密钥时禁止系统截图。",Modifier.padding(bottom=16.dp),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                    }
                }
                original?.let{saved->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={state.makeDefault(saved)},enabled=!state.busy&&state.defaultId!=saved.id,modifier=Modifier.testTag("config-default-${saved.id}")){Text(if(state.defaultId==saved.id)"已为默认" else "设为默认",style=MaterialTheme.typography.bodySmall)};TextButton(onClick={deleting=saved},enabled=!state.busy,modifier=Modifier.testTag("config-delete-${saved.id}")){Text("删除配置",style=MaterialTheme.typography.bodySmall,color=ConfigErrorColor)}}}
                Spacer(Modifier.height(12.dp))
            }
        }
    }}
    deleting?.let{config->AlertDialog(onDismissRequest={deleting=null},title={Text("删除这份配置？")},text={Text("同时清除保存的密钥。聊天记录不受影响。")},confirmButton={TextButton(onClick={state.delete(config);deleting=null;key="";reveal=false;screen="overview"}){Text("确认删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

@Composable private fun Notice(text:String,error:Boolean){Text(text,Modifier.fillMaxWidth().testTag("config-status"),style=MaterialTheme.typography.bodySmall,color=if(error)ConfigErrorColor else ConfigGreen)}
@Composable private fun fieldColors()=TextFieldDefaults.colors(focusedContainerColor=Paper,unfocusedContainerColor=Paper,focusedIndicatorColor=ConfigGreen,unfocusedIndicatorColor=ConfigLine)
@Composable private fun ConfigField(value:String,onChange:(String)->Unit,label:String,tag:String,placeholder:String="",keyboard:KeyboardType=KeyboardType.Text){TextField(value,onChange,label={Text(label)},placeholder={if(placeholder.isNotEmpty())Text(placeholder)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=keyboard),modifier=Modifier.fillMaxWidth().testTag(tag),colors=fieldColors())}
@Composable private fun ProviderMark(p:ProviderPreset){Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),contentAlignment=Alignment.Center){if(p.id=="custom")NavigationIcon(2,false) else Text(providerName(p).take(1),style=MaterialTheme.typography.titleMedium,color=Muted,fontWeight=FontWeight.Medium)}}
@Composable private fun SaveAction(enabled:Boolean,onClick:()->Unit){CompactSaveAction(enabled,onClick)}
