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
import kotlinx.coroutines.launch

private val ConfigBackground:Color @Composable get()=Chrome
private val ConfigInk:Color @Composable get()=Ink
private val ConfigMuted:Color @Composable get()=Muted
private val ConfigGreen:Color @Composable get()=GreenText
private val ConfigLine:Color @Composable get()=Line
private val ConfigErrorColor:Color @Composable get()=MaterialTheme.colorScheme.error
private fun providerName(p:ProviderPreset)=p.label.substringBefore(" · ")
private fun regionName(p:ProviderPreset)=p.label.substringAfter(" · ","")

@Composable fun ModelConfigScreen(onBack:()->Unit,state:ModelConfigState=viewModel()) {
    LaunchedEffect(Unit){state.refresh()}
    var screen by remember {mutableStateOf("overview")}
    var embeddingEditor by remember {mutableStateOf(false)}
    var allProviders by remember {mutableStateOf(false)}
    var regionGroup by remember {mutableStateOf<ProviderGroup?>(null)}
    var showUnknownModels by remember {mutableStateOf(false)}
    var providerReturn by remember {mutableStateOf("overview")}
    var original by remember {mutableStateOf<ModelConfig?>(null)}
    var draftId by remember {mutableStateOf(UUID.randomUUID().toString())}
    var name by remember {mutableStateOf("")};var provider by remember {mutableStateOf("silicon-cn")}
    var base by remember {mutableStateOf("")};var model by remember {mutableStateOf("")}
    var key by remember {mutableStateOf("")};var reveal by remember {mutableStateOf(false)}
    var prefilledKey by remember {mutableStateOf("")}
    fun enteredKey()=if(key==prefilledKey)"" else key
    val keyDraft=remember {ModelKeyDraft()}
    val editorScope=rememberCoroutineScope()
    var budgets by remember {mutableStateOf(ModelBudgetDraft())}
    var query by remember {mutableStateOf("")};var advanced by remember {mutableStateOf(false)}
    var modelQuery by remember {mutableStateOf("")}
    var deleting by remember {mutableStateOf<ModelConfig?>(null)}
    val activity=LocalActivity.current ?: return
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun draft()=ModelConfig(draftId,name.trim(),provider,base.trim().trimEnd('/'),model.trim(),budgets.context.toIntOrNull()?:0,budgets.chat.toIntOrNull()?:0,budgets.memory.toIntOrNull()?:-1)
    fun back(){
        if(state.saving)return
        keyDraft.invalidate()
        state.clearResult()
        if(screen=="providers"){screen=providerReturn;return}
        key="";reveal=false
        if(screen=="edit")screen="overview" else onBack()
    }
    fun add(embedding:Boolean=false){if(state.saving)return;prefilledKey="";keyDraft.invalidate();embeddingEditor=embedding;original=null;draftId=UUID.randomUUID().toString();name="";key="";reveal=false;model="";budgets=ModelBudgetDraft();query="";allProviders=false;providerReturn="overview";state.clearResult();screen="providers"}
    fun edit(config:ModelConfig){prefilledKey="";embeddingEditor=state.purposes[ModelPurpose.EMBEDDING]==config.id;original=config;draftId=config.id;name=config.name;provider=config.providerId;base=config.baseUrl;model=config.modelId;budgets=ModelBudgetDraft.existing(config);key="";reveal=false;advanced=false;state.clearResult();screen="edit"
        keyDraft.invalidate()
        val ticket=keyDraft.ticket()
        editorScope.launch {
            val saved=state.savedKey(config)
            if(keyDraft.accepts(ticket)&&screen=="edit"&&original==config){prefilledKey=saved;key=saved}
        }
    }
    fun changed(){modelQuery="";state.clearResult()}
    fun chooseModel(id:String){model=id;budgets=budgets.forModel(provider,base,model);changed()}
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP){reveal=false;state.clearResult()}}
        lifecycle.addObserver(observer)
        onDispose{keyDraft.invalidate();lifecycle.removeObserver(observer);state.clearResult();activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    SideEffect{if(reveal)activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    BackHandler{back()}
    val preset=ProviderPresets.get(provider)
    Surface(Modifier.fillMaxSize(),color=ConfigBackground){Column {
        AppTopBar(
            title=when(screen){"providers"->if(embeddingEditor)"选择向量服务商" else "选择聊天服务商";"edit"->if(embeddingEditor)"向量模型配置" else if(original==null)"添加 ${providerName(preset)}" else "编辑配置";else->"大模型配置"},
            onBack={back()},backTag="config-back",enabled=!state.saving,
            extra={when(screen){
                "edit"->SaveAction(!state.busy){reveal=false;state.save(draft(),enteredKey(),if(embeddingEditor)ModelPurpose.EMBEDDING else null){key="";screen="overview"}}
                "overview"->IconButton(onClick={add()},modifier=Modifier.size(UiSpace.action).testTag("config-add-top").semantics{contentDescription="添加服务商"}){ControlIcon("add")}
                else->Spacer(Modifier.width(48.dp))
            }}
        )
        if(screen=="overview"){
            LazyColumn(Modifier.fillMaxSize().testTag("config-overview"),contentPadding=PaddingValues(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
                item{SectionLabel("已保存的服务")}
                if(state.status.isNotBlank())item{Notice(state.status,state.statusError)}
                item{Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
                    if(state.configs.isEmpty())Text("还没有保存的服务",Modifier.padding(22.dp),color=ConfigMuted)
                    state.configs.forEachIndexed {index,config->
                        val p=ProviderPresets.get(config.providerId)
                        Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy){edit(config)}.testTag("config-edit-${config.id}").padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                            ProviderMark(p);Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                Text(config.name,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text("${providerName(p)} · ${config.modelId}",style=MaterialTheme.typography.bodySmall,color=ConfigMuted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                val test=state.tests[config.id];val testLine=ModelStatusText.test(test,config)
                                Text(testLine,Modifier.testTag("config-probe-${config.id}"),style=MaterialTheme.typography.bodySmall,color=when{testLine=="未测试"->ConfigMuted;test?.ok==true->ConfigGreen;else->ConfigErrorColor})
                                val call=state.calls[config.id]
                                Text(ModelStatusText.calls(call),Modifier.testTag("config-calls-${config.id}"),style=MaterialTheme.typography.bodySmall,color=if(call?.ok==false)ConfigErrorColor else ConfigMuted)
                            }
                            if(config.id==state.defaultId)Text("默认",Modifier.padding(horizontal=8.dp).clip(RoundedCornerShape(8.dp)).background(SelectedBackground).padding(horizontal=8.dp,vertical=4.dp),color=ConfigGreen,style=MaterialTheme.typography.bodySmall)
                            ControlIcon("chevron")
                        }
                        if(index<state.configs.lastIndex)HorizontalDivider(Modifier.padding(start=72.dp,end=18.dp),color=ConfigLine)
                    }
                }}
                item{Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper).clickable{add()}.testTag("config-add").padding(18.dp),verticalAlignment=Alignment.CenterVertically){ControlIcon("add");Spacer(Modifier.width(14.dp));Text("添加服务商",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);ControlIcon("chevron")}}
                item{OutlinedButton(onClick={add(true)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("config-add-embedding")){Text("添加向量模型")}}
                item{PurposeSelection(state)}
                item{Text("先添加并保存模型配置，再在下方选择用途；向量模型请选择“记忆向量”。保存与用途选择不发起请求。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
            }
        }else if(screen=="providers"){
            OutlinedTextField(query,{query=it.take(80)},placeholder={Text("搜索服务商")},singleLine=true,leadingIcon={ControlIcon("search")},modifier=Modifier.fillMaxWidth().padding(horizontal=UiSpace.page,vertical=12.dp).testTag("provider-search"),shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=Paper,focusedContainerColor=Paper,unfocusedBorderColor=Color.Transparent))
            val groups=ProviderCatalog.groups(embeddingEditor,query,allProviders)
            fun choose(p:ProviderPreset){
                val old=ProviderPresets.get(provider)
                if(original==null||name.isBlank()||name==providerName(old))name=providerName(p)+(if(embeddingEditor)" · 向量" else "")
                prefilledKey="";keyDraft.invalidate();provider=p.id;base=p.base;model="";budgets=budgets.forModel(provider,base,model);key="";reveal=false;advanced=p.base.isBlank();showUnknownModels=false;changed();screen="edit"
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal=UiSpace.page).clip(RoundedCornerShape(18.dp)).background(Paper).testTag("provider-list"),contentPadding=PaddingValues(vertical=4.dp)){
                if(groups.isEmpty())item{Text("没有找到，可选择自定义兼容服务。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium,color=ConfigMuted);TextButton({choose(ProviderPresets.get("custom"))}){Text("手动填写")}}
                items(groups,key={it.name}){group->val p=group.regions.first()
                    Row(Modifier.fillMaxWidth().clickable{if(group.regions.size==1)choose(p) else regionGroup=group}.testTag("provider-${p.id}").padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                        ProviderMark(p);Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(group.name,style=MaterialTheme.typography.titleSmall);val detail=if(p.id=="custom")"手填地址与模型 ID" else group.regions.map{regionName(it)}.filter{it.isNotBlank()}.joinToString(" / ");if(detail.isNotBlank())Text(detail,style=MaterialTheme.typography.labelSmall,color=ConfigMuted,modifier=Modifier.padding(top=3.dp))};ControlIcon("chevron")
                    };HorizontalDivider(Modifier.padding(start=72.dp,end=18.dp),color=ConfigLine)
                }
                if(!allProviders&&query.isBlank())item{TextButton({allProviders=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("providers-all")){Text("全部服务")}}
            }
            regionGroup?.let{group->AlertDialog(onDismissRequest={regionGroup=null},title={Text("${group.name} · 选择地域")},text={Column{group.regions.forEach{p->TextButton({regionGroup=null;choose(p)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("provider-region-${p.id}")){Text(regionName(p))}}}},confirmButton={TextButton({regionGroup=null}){Text("取消")}})}
            Text(if(embeddingEditor)"默认显示已核对文本向量接口；其他服务可手填。" else "预设不代表已连接；请选择对应地域的密钥。",Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
        }else{
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("config-editor").padding(horizontal=UiSpace.page,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                if(embeddingEditor)Text("用于记忆语义检索，保存后指定为记忆向量；不会用于聊天或总结记忆。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                Row(Modifier.fillMaxWidth().clickable(enabled=!state.saving){keyDraft.invalidate();reveal=false;state.clearResult();modelQuery="";query="";providerReturn="edit";screen="providers"}.testTag("provider-picker").padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){ProviderMark(preset);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(providerName(preset),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium);Text(regionName(preset).ifBlank{"OpenAI 兼容配置"},style=MaterialTheme.typography.bodySmall,color=ConfigMuted)};Text("更换",color=ConfigGreen,style=MaterialTheme.typography.bodySmall)}
                if(state.status.isNotBlank()&&!state.fetching&&state.models.isEmpty())Notice(state.status,state.statusError)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper).padding(horizontal=16.dp,vertical=6.dp)){
                    ConfigField(name,{name=it.take(60);changed()},"配置名称","config-name")
                    val retained=original?.baseUrl==base.trim().trimEnd('/')&&original?.providerId==provider
                    TextField(key,{keyDraft.invalidate();key=it.take(4096);changed()},label={Text("API Key")},placeholder={Text(if(retained)"留空沿用已保存的密钥" else "输入你的密钥")},singleLine=true,visualTransformation=if(reveal)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={IconButton(onClick={reveal=!reveal},modifier=Modifier.testTag("config-key-reveal").semantics{contentDescription=if(reveal)"隐藏密钥" else "显示密钥"}){ControlIcon(if(reveal)"eye" else "eye-off")}},colors=fieldColors(),modifier=Modifier.fillMaxWidth().testTag("config-key"))
                    ConfigField(model,{chooseModel(it.take(200))},if(embeddingEditor)"向量模型 ID" else "模型 ID / Endpoint ID","config-model",placeholder="选择或填写模型")
                    if(preset.modelsPath!=null||preset.modelsUrl!=null){
                        Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically){
                            if(state.fetching)TextButton(onClick={state.cancelFetch()},modifier=Modifier.testTag("config-fetch-cancel")){Text("取消",style=MaterialTheme.typography.bodySmall)}
                            TextButton(onClick={reveal=false;modelQuery="";state.request(draft(),enteredKey(),true)},enabled=!state.busy,modifier=Modifier.testTag("config-fetch")){
                                if(state.fetching){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                                Text(if(state.fetching)"正在获取模型…" else if(state.statusError)"重新获取" else "获取模型",color=if(state.fetching)ConfigMuted else Color.Unspecified,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if(preset.modelsUrl!=null)Text(preset.modelsHint,Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("config-models-note"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                    }
                    else Text(preset.modelsHint,Modifier.padding(vertical=12.dp,horizontal=16.dp).testTag("config-models-unavailable"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                }
                if(state.models.isNotEmpty()){
                    Notice(state.status,false)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Paper).padding(12.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("选择模型（含向量等类型）",style=MaterialTheme.typography.labelLarge);Text("点选填入，保存后生效",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                        OutlinedTextField(modelQuery,{modelQuery=it.take(200)},placeholder={Text("筛选已获取目录（不填入模型）",style=MaterialTheme.typography.bodyMedium)},singleLine=true,leadingIcon={ControlIcon("search")},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp).testTag("model-search"),shape=RoundedCornerShape(12.dp))
                        val typed=state.models.filter{!embeddingEditor||showUnknownModels||ProviderCatalog.modelKind(draft().copy(modelId=it))==ProviderModelKind.TEXT_EMBEDDING}
                        if(embeddingEditor)TextButton({showUnknownModels=!showUnknownModels}){Text(if(showUnknownModels)"仅显示已核对文本向量" else "显示未核实目录项（可手填）")}
                        val filtered=typed.filter{it.contains(modelQuery,ignoreCase=true)}
                        if(filtered.isEmpty())Text("没有匹配的模型，可继续手填。",Modifier.padding(12.dp).testTag("model-options-empty"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max=220.dp).testTag("model-options")){
                            items(filtered,key={it}){id->val selected=id==model
                                Row(Modifier.fillMaxWidth().heightIn(min=UiSpace.action).clip(RoundedCornerShape(9.dp)).background(if(selected)SelectedBackground else Paper).clickable{chooseModel(id)}.semantics{this.selected=selected}.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Text(id,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=if(selected)ConfigGreen else ConfigInk);if(selected)Text("✓ 当前",Modifier.padding(start=8.dp),style=MaterialTheme.typography.bodySmall,color=ConfigGreen)}
                            }
                        }
                    }
                }
                if(embeddingEditor&&ProviderCatalog.modelKind(draft())!=ProviderModelKind.TEXT_EMBEDDING)Text("此 ID 的文本向量兼容性未核实；可手填保存，多模态专用接口尚未适配。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                if(preset.id=="opencode-go")Notice(preset.hint,false)
                ProviderRequestMetadata.compatibilityNotice(draft())?.let{Notice(it,true)}
                if(embeddingEditor)Text("向量维度：自动，以模型实际返回为准。无需填写 4096，不向服务强发 dimensions 参数；不同维度不会混合计算。",Modifier.testTag("embedding-dimensions"),style=MaterialTheme.typography.bodyMedium,color=ConfigMuted)
                Column(Modifier.fillMaxWidth()){
                    if(embeddingEditor){
                        TextButton(onClick={reveal=false;state.testEmbedding(draft(),enteredKey())},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp).testTag("config-test")){Text(if(state.testing)"正在测试向量…" else "测试向量连接（返回维度）")}
                    }else if(preset.testPath!=null)Row(Modifier.heightIn(min=48.dp).clickable(enabled=!state.busy,role=Role.Button){reveal=false;state.request(draft(),enteredKey(),false)}.testTag("config-test").padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){ControlIcon("plug");Spacer(Modifier.width(8.dp));Text(if(state.probe.phase==ProbePhase.TESTING)"正在测试…" else if(state.probe.phase==ProbePhase.FAILED)"重新测试" else "测试连接",color=ConfigGreen,style=MaterialTheme.typography.bodyMedium);if(state.probe.phase==ProbePhase.TESTING)CircularProgressIndicator(Modifier.padding(start=12.dp).size(18.dp),strokeWidth=2.dp)}
                    else Row(Modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){ControlIcon("plug");Spacer(Modifier.width(8.dp));Text("此服务商暂未适配连接测试",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)}
                    val result=state.probe
                    Text(when(result.phase){ProbePhase.UNTESTED->"未测试";ProbePhase.TESTING->"正在查询目录/认证接口…";else->result.text},Modifier.padding(start=4.dp,top=2.dp).testTag("config-probe-inline"),style=MaterialTheme.typography.bodySmall,color=when(result.phase){ProbePhase.REACHABLE->ConfigGreen;ProbePhase.FAILED->ConfigErrorColor;else->ConfigMuted})
                    Text(if(embeddingEditor)"点击测试才向 /embeddings 发送一句合成文本，可能按服务商规则计费；不会发送聊天或记忆。" else "测试连接仅检查目录/认证，不验证聊天或向量生成。目录含不同类型，保存后请为配置选择对应用途。",Modifier.padding(start=4.dp,top=8.dp),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
                    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable{advanced=!advanced}.testTag("config-advanced").padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("高级设置",style=MaterialTheme.typography.labelLarge);Text("服务地址与兼容方式",style=MaterialTheme.typography.bodySmall,color=ConfigMuted,modifier=Modifier.padding(top=4.dp))};ControlIcon(if(advanced)"up" else "chevron")}
                    if(advanced)Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        ConfigField(base,{prefilledKey="";keyDraft.invalidate();base=it.take(500);budgets=budgets.forModel(provider,base,model);key="";reveal=false;changed()},"服务地址（HTTPS）","config-url",keyboard=KeyboardType.Uri)
                        if(!embeddingEditor){
                        ConfigField(budgets.context,{budgets=budgets.manual(context=it.filter(Char::isDigit).take(7));changed()},"上下文容量（按模型文档）","config-context",keyboard=KeyboardType.Number)
                        ConfigField(budgets.chat,{budgets=budgets.manual(chat=it.filter(Char::isDigit).take(6));changed()},"聊天生成上限 Token","config-output",keyboard=KeyboardType.Number)
                        ConfigField(budgets.memory,{budgets=budgets.manual(memory=it.filter(Char::isDigit).take(6));changed()},"总结记忆生成上限 Token","config-memory-output",keyboard=KeyboardType.Number)
                        Text("总结记忆填 0 时沿用聊天上限。生成上限不代表每次都会输出这么长；推理模型的额度还包括推理。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        val known=ModelTokenPolicy.capability(provider,base,model)
                        if(known!=null){
                            Text("已核对：上下文 ${known.context}，生成最多 ${known.maxOutput}。"+(if(known.reasoning)"当前模型默认推理，推理与正文共用额度。"else"当前模型为非推理模型。"),Modifier.testTag("config-budget-known"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                            TextButton(onClick={budgets=budgets.apply(provider,base,model).copy(automatic=false);changed()},enabled=!state.busy,modifier=Modifier.testTag("config-budget-apply")){Text("使用模型建议（仅填入草稿）")}
                        }else Text("尚未核对该模型容量，请按服务文档填写；保留手动输入。",Modifier.testTag("config-budget-unknown"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        Text("本地估算含安全余量，不会自动裁掉未总结原话；顶部保存后才更新配置。",style=MaterialTheme.typography.bodySmall,color=ConfigMuted)

                        }
                        Text("OpenAI 兼容 · "+(if(preset.authHeader=="api-key")"api-key 鉴权" else "Bearer 鉴权"),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        Text(preset.hint,style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
                        Text("切到后台再回来保留本页未保存草稿，密钥仅留在进程内；返回退出或进程被回收后不保证恢复。改变地址或服务商需重新输入密钥。显示密钥时禁止系统截图。",Modifier.padding(bottom=16.dp),style=MaterialTheme.typography.bodySmall,color=ConfigMuted)
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
