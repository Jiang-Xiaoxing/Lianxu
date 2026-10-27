package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Lianxu content-first memory browser. Operational metadata lives in details and maintenance. */
@Composable internal fun MemoryExperience(vm:AppState,friend:Friend,onRelationship:()->Unit){
    val repo=vm.processing;val scope=rememberCoroutineScope()
    val summaryRoles by repo.store.memorySummary.roles.collectAsState();val summarizing=friend.id in summaryRoles
    var memories by remember{mutableStateOf(emptyList<StoredMemory>())};var candidates by remember{mutableStateOf(emptyList<ProcessingRepository.Candidate>())}
    var reviews by remember{mutableStateOf(emptyList<MemoryBatchRepository.Review>())};var budget by remember{mutableStateOf<CoreBudgetState?>(null)}
    var task by remember{mutableStateOf(ProcessingRepository.TaskStatus())};var policy by remember{mutableStateOf(ProcessingPolicy())}
    var category by rememberSaveable(friend.id){mutableStateOf<MemoryCategory?>(null)};var about by rememberSaveable(friend.id){mutableStateOf<MemoryAbout?>(null)}
    var query by rememberSaveable(friend.id){mutableStateOf("")};var tag by rememberSaveable(friend.id){mutableStateOf<String?>(null)}
    var detailId by rememberSaveable(friend.id){mutableStateOf<String?>(null)};var mode by rememberSaveable(friend.id){mutableStateOf("browse")}
    var editing by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    var versions by remember{mutableStateOf<List<StoredMemory>?>(null)};var evidence by remember{mutableStateOf<StoredMemory?>(null)}
    var sourceRows by remember{mutableStateOf<List<SourceDialogue>?>(null)};var sourceEdit by remember{mutableStateOf<SourceDialogue?>(null)};var demote by remember{mutableStateOf<StoredMemory?>(null)}
    var coreGoal by rememberSaveable{mutableStateOf("")}
    LaunchedEffect(friend.id,vm.processingRevision){memories=repo.memories(friend.id);candidates=repo.candidates(friend.id);reviews=MemoryBatchRepository(repo).reviews(friend.id);budget=repo.coreBudget(friend.id);task=repo.memoryStatus(friend.id);policy=repo.policy(friend.id).first;if(coreGoal.isEmpty())coreGoal=policy.coreGoal.toString()}
    fun action(block:suspend()->Unit){if(busy)return;busy=true;error="";scope.launch{try{block();vm.refreshProcessing()}catch(e:CancellationException){throw e}catch(e:Exception){error=processingMessage((e as? ProcessingRejected)?.code?:"FORMAT")}finally{busy=false}}}
    fun back(){if(detailId!=null)detailId=null else if(mode!="browse")mode="browse" else vm.back()}
    BackHandler(detailId!=null||mode!="browse"){back()}
    val detail=memories.firstOrNull{it.id==detailId}
    Column(Modifier.fillMaxSize().background(Chrome)){
        AppTopBar(if(detail!=null)"记忆详情" else if(mode=="maintenance")"总结记忆与维护" else if(mode=="candidates")"待确认" else "${friend.name} · 记忆",{back()},extra={if(mode=="browse"&&detail==null)IconButton({editing=true},Modifier.size(UiSpace.action).testTag("memory-add"),enabled=!summarizing){ControlIcon("add")}})
        if(summarizing)Text("正在总结记忆，暂不能手动修改；结束或停止后恢复，未保存草稿会保留。",Modifier.padding(horizontal=UiSpace.page,vertical=8.dp).testTag("memory-summary-guard"),style=MaterialTheme.typography.bodySmall,color=Muted)
        if(error.isNotEmpty())Text(error,Modifier.padding(horizontal=UiSpace.page,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
        if(detail!=null){
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
                Text(MemoryFacets.categoryLabel(detail),style=MaterialTheme.typography.labelLarge,color=GreenText)
                Text(detail.text,style=MaterialTheme.typography.bodyLarge)
                if(detail.tags.isNotEmpty())Text(detail.tags.joinToString("  "){"#$it"},style=MaterialTheme.typography.bodyMedium,color=Muted)
                detail.annualMonthDay?.let{Text("年度生日 $it"+(detail.eventDate?.let{date->" · 已知日期 $date"}?:" · 出生年份未知"),style=MaterialTheme.typography.bodySmall,color=Muted)}
                detail.dueDate?.let{Text("到期提醒 $it · 到期不会自动完成",style=MaterialTheme.typography.bodySmall,color=Muted)}
                Text("${lifeLabel(detail.life)} · "+(if(detail.valid)"来源有效" else "来源已失效"),style=MaterialTheme.typography.bodySmall,color=Muted)
                Row{if(detail.category.modern)TextButton({editing=true},enabled=!summarizing,modifier=Modifier.testTag("memory-edit")){Text("编辑")};TextButton({action{repo.setLock(friend.id,detail.id,!detail.locked)}},enabled=!summarizing,modifier=Modifier.testTag("memory-lock-toggle")){Text(if(detail.locked)"解锁" else "锁定")};if(detail.core)TextButton({demote=detail},enabled=!summarizing,modifier=Modifier.testTag("core-demote-${detail.id}")){Text("改为普通记忆")}}
                HorizontalDivider(color=Line)
                Text("记录信息",style=MaterialTheme.typography.titleSmall)
                Text("版本 ${detail.version} · "+(if(detail.manual)"人工记录" else "总结记忆生成")+" · "+(if(detail.locked)"已锁定" else "未锁定")+" · "+(if(detail.core)"核心记忆" else "普通记忆"),style=MaterialTheme.typography.bodySmall,color=Muted)
                if(!detail.category.modern)Text("原分类标识 ${detail.category.name} 与原文保留，尚未归入当前四分类；来源、版本和核心/锁定状态保留，不自动推断归属或重分类。",style=MaterialTheme.typography.bodySmall,color=Muted)
                TextButton({evidence=detail}){Text("查看来源")};TextButton({action{versions=repo.history(friend.id,detail.id)}}){Text("版本历史")}
            }
        }else if(mode=="maintenance"){
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(18.dp)){
                Text(taskDescription(task),Modifier.testTag("memory-task-status"),style=MaterialTheme.typography.bodyLarge)
                reviews.forEach{r->Text("这条内容在总结记忆后被改动",style=MaterialTheme.typography.titleSmall);Text(r.current.text,style=MaterialTheme.typography.bodyLarge);Text("保留当前版本，不跳过来源检查或完整回执。",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(!r.acknowledged)TextButton({action{MemoryBatchRepository(repo).keepCurrent(friend.id,r.batchId,r.current.id,r.current.version)}},enabled=!summarizing,modifier=Modifier.testTag("batch-keep-${r.current.id}")){Text("保留当前版本，允许本批继续")}else Text("已确认，重试后补齐回执。",color=GreenText,style=MaterialTheme.typography.bodySmall)}
                Row{TextButton({vm.retryMemory(friend.id)},enabled=!summarizing&&!busy&&reviews.none{!it.acknowledged}&&task.state !in setOf("RUNNING","WAITING"),modifier=Modifier.testTag("memory-retry")){Text("总结记忆 / 重试")};TextButton({vm.cancelProcessing(friend.id)}){Text("停止任务")}}
                budget?.let{b->Text("核心 ${b.used} / 维护目标 ${b.goal} Token",Modifier.testTag("core-budget-status"));if(b.overGoal)Text("核心超过维护目标。人工、锁定及进行中的批次保留，请核对后选择；没有静默截断。",Modifier.testTag("core-budget-warning"),style=MaterialTheme.typography.bodySmall,color=Muted)
                    b.entries.forEach{m->Column{Text(m.text,style=MaterialTheme.typography.bodyMedium);TextButton({demote=m},enabled=!summarizing,modifier=Modifier.testTag("core-demote-${m.id}")){Text("改为普通记忆")}}}}
                HorizontalDivider(color=Line)
                TextButton({vm.open("settings-memory",friend.id)},modifier=Modifier.testTag("memory-rules-open")){Text("记忆规则：轮数与条数")}
                AppField(coreGoal,{coreGoal=it.take(5)},"核心维护目标（Token）","memory-core-goal",true)
                TextButton({action{val p=policy.copy(coreGoal=coreGoal.toIntOrNull()?:0);if(!p.valid())throw ProcessingRejected("CAPACITY");repo.savePolicy(friend.id,p);repo.maintainCore(friend.id)}},enabled=!summarizing){Text("保存核心目标")}
                TextButton({action{sourceRows=repo.sources(friend.id)}},modifier=Modifier.testTag("processing-sources")){Text("回查聊天原话")}
            }
        }else if(mode=="candidates"){
            LazyColumn(Modifier.fillMaxSize().padding(horizontal=UiSpace.page),verticalArrangement=Arrangement.spacedBy(20.dp)){
                item{Text("先核对，再成为记忆",style=MaterialTheme.typography.titleLarge)}
                items(candidates,key={it.id}){c->Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text(c.proposed.text,style=MaterialTheme.typography.bodyLarge);Text(c.reason,Modifier.testTag("candidate-state-${c.id}"),style=MaterialTheme.typography.bodySmall,color=Muted)
                    val target=memories.firstOrNull{it.id==c.targetId};target?.let{Text("当前内容：${it.text}",style=MaterialTheme.typography.bodyMedium)}
                    Row{TextButton({action{repo.resolveCandidate(friend.id,c.id,false)}},enabled=!summarizing&&!busy){Text("保留原内容")};TextButton({action{repo.resolveCandidate(friend.id,c.id,true,target?.version)}},enabled=c.valid&&!busy&&!summarizing,modifier=Modifier.testTag("candidate-accept-${c.id}")){Text("采用并人工确认")}}
                    TextButton({evidence=c.proposed}){Text("查看候选来源快照")};HorizontalDivider(color=Line)
                }}
            }
        }else{
            val shown=memories.filter{MemoryFacets.matches(it,category,about,tag,query)}.sortedBy{!it.category.modern}
            Column(Modifier.fillMaxSize()){
                Row(Modifier.padding(horizontal=UiSpace.page),verticalAlignment=Alignment.CenterVertically){Text("值得记住的小事",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onRelationship,Modifier.testTag("processing-relationship")){Text("关系")}}
                Text("只属于你与${friend.name}的这段对话",Modifier.padding(horizontal=UiSpace.page,vertical=6.dp),style=MaterialTheme.typography.bodySmall,color=Muted)
                Box(Modifier.padding(horizontal=UiSpace.page,vertical=4.dp)){AppField(query,{query=it},"搜索内容或主题标签","memory-search",true)}
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=UiSpace.page),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    MemoryChoice(category==null,{category=null},{Text("全部")},modifier=Modifier.heightIn(min=48.dp).testTag("memory-filter-all"))
                    MemoryCategory.entries.filter{it.modern}.forEach{c->MemoryChoice(category==c,{category=c},{Text(c.label)},modifier=Modifier.heightIn(min=48.dp).testTag("memory-filter-${c.name}"))}
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=UiSpace.page),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    MemoryChoice(about==null,{about=null},{Text("关于谁 · 全部")},modifier=Modifier.heightIn(min=48.dp).testTag("memory-about-all"));MemoryAbout.entries.forEach{a->MemoryChoice(about==a,{about=a},{Text(a.label)},modifier=Modifier.heightIn(min=48.dp).testTag("memory-about-${a.name}"))}
                }
                val tags=memories.flatMap{it.tags}.distinct();if(tags.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=UiSpace.page)){tags.forEach{t->TextButton({tag=if(tag==t)null else t},modifier=Modifier.testTag("memory-tag-$t")){Text((if(tag==t)"✓ " else "#")+t)}}}
                Row(Modifier.fillMaxWidth().padding(horizontal=UiSpace.page),verticalAlignment=Alignment.CenterVertically){Text("${shown.size} 条记忆",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=Muted);if(candidates.isNotEmpty())TextButton({mode="candidates"},modifier=Modifier.testTag("memory-candidates")){Text("待核对 ${candidates.count{it.valid}}")};TextButton({mode="maintenance"},modifier=Modifier.testTag("memory-maintenance")){Text(if(reviews.any{!it.acknowledged}||budget?.overGoal==true)"有内容需核对" else "总结记忆")}}
                LazyColumn(Modifier.weight(1f).testTag("memory-list"),contentPadding=PaddingValues(horizontal=UiSpace.page,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    if(shown.isEmpty())item{Text(if(memories.isEmpty())"从一件想记住的小事开始。" else "没有匹配的内容，试试其他筛选。",Modifier.padding(vertical=36.dp),style=MaterialTheme.typography.bodyLarge,color=Muted)}
                    items(shown,key={it.id}){m->Column(Modifier.fillMaxWidth().clickable{detailId=m.id}.padding(vertical=18.dp).testTag("memory-${m.id}"),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(m.text,style=MaterialTheme.typography.bodyLarge,maxLines=3,overflow=TextOverflow.Ellipsis)
                        Text(MemoryFacets.categoryLabel(m)+(if(m.tags.isEmpty())""else" · "+m.tags.take(2).joinToString(" "){"#$it"}),style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        HorizontalDivider(Modifier.padding(top=10.dp),color=Line,thickness=.5.dp)
                    }}
                }
            }
        }
    }
    if(editing)MemoryV2Editor(detail,{editing=false},!summarizing){text,c,a,t,d,l,core,lock,birthday->action{repo.manualSave(friend.id,detail?.id,text,c,core,lock,a,t,d,l,birthday);editing=false}}
    demote?.let{m->AlertDialog({demote=null},title={Text("改为普通记忆？")},text={Text("只取消核心标记，正文、来源、锁定和历史保留。")},confirmButton={TextButton({action{repo.chooseCore(friend.id,m.id,m.version,false);demote=null}},enabled=!summarizing,modifier=Modifier.testTag("core-demote-confirm")){Text("确认改为普通")}},dismissButton={TextButton({demote=null}){Text("取消")}})}
    evidence?.let{m->AlertDialog({evidence=null},title={Text("来源快照")},text={Column(Modifier.verticalScroll(rememberScrollState())){if(m.evidence.isEmpty())Text("人工记录，没有模型原话证据。");m.evidence.forEach{e->Text("${e.source.turnId}@${e.source.version} · ${e.side}\n${e.snapshot}\n")};Text("已编辑或撤回的来源仅作历史快照。")}},confirmButton={TextButton({evidence=null}){Text("关闭")}})}
    versions?.let{rows->AlertDialog({versions=null},title={Text("版本历史")},text={Column(Modifier.verticalScroll(rememberScrollState())){rows.forEach{m->Text("v${m.version} · ${m.text}");TextButton({action{repo.restore(friend.id,m.id,m.version);versions=null}},enabled=!summarizing){Text("恢复为新版本")}}}},confirmButton={TextButton({versions=null}){Text("关闭")}})}
    sourceRows?.let{rows->AlertDialog({sourceRows=null},title={Text("聊天原话")},text={Column(Modifier.verticalScroll(rememberScrollState())){rows.forEach{s->Text("${s.source.turnId}@${s.source.version}\n我：${s.user.text}\n${friend.name}：${s.assistant.text}");TextButton({sourceRows=null;sourceEdit=s}){Text("编辑 / 撤回 / 历史")}}}},confirmButton={TextButton({sourceRows=null}){Text("关闭")}})}
    sourceEdit?.let{s->SourceEditor(repo,s,{sourceEdit=null}){impact->action{SourceChanges(repo).confirm(impact);sourceEdit=null}}}
}

internal fun lifeLabel(life:MemoryLife)=when(life){MemoryLife.ACTIVE->"进行中";MemoryLife.COMPLETED->"已完成";MemoryLife.CANCELLED->"已取消";MemoryLife.RETRACTED->"已撤回"}
@Composable private fun MemoryV2Editor(old:StoredMemory?,dismiss:()->Unit,editable:Boolean,save:(String,MemoryCategory,MemoryAbout,List<String>,String,MemoryLife,Boolean,Boolean,String?)->Unit){
    var text by rememberSaveable{mutableStateOf(old?.text.orEmpty())};var category by rememberSaveable{mutableStateOf(old?.category?:MemoryCategory.STATE_FACT)};var about by rememberSaveable{mutableStateOf(old?.about?:MemoryAbout.USER)}
    var tags by rememberSaveable{mutableStateOf(old?.tags?.joinToString("，").orEmpty())};var due by rememberSaveable{mutableStateOf(old?.dueDate.orEmpty())};var life by rememberSaveable{mutableStateOf(old?.life?:MemoryLife.ACTIVE)}
    var birthday by rememberSaveable{mutableStateOf(old?.annualMonthDay.orEmpty())}
    var core by rememberSaveable{mutableStateOf(old?.core?:false)};var lock by rememberSaveable{mutableStateOf(old?.locked?:true)};var menu by remember{mutableStateOf(false)};var lifeMenu by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    AlertDialog(dismiss,title={Text(if(old==null)"记下一件小事" else "编辑记忆")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        AppField(text,{text=it.take(8000)},"记忆内容","memory-text",minLines=3)
        Box{TextButton({menu=true},modifier=Modifier.testTag("memory-category-picker")){Text(category.label)};DropdownMenu(menu,{menu=false}){MemoryCategory.entries.filter{it.modern}.forEach{c->DropdownMenuItem({Text(c.label)},{category=c;menu=false},modifier=Modifier.testTag("category-option-${c.name}"))}}}
        Text("关于谁",style=MaterialTheme.typography.labelLarge);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MemoryAbout.entries.forEach{a->MemoryChoice(about==a,{about=a},{Text(a.label)},modifier=Modifier.testTag("about-${a.name}").heightIn(min=48.dp))}}
        AppField(tags,{tags=it.take(400)},"主题标签（逗号分隔）","memory-tags",true)
        AppField(birthday,{birthday=it.take(5)},"年度生日 MM-DD（可空，不猜年份）","memory-birthday",true)
        AppField(due,{due=it.take(10)},"到期提醒 YYYY-MM-DD（可空）","memory-due",true)
        Text("到期只提醒，不会自动完成。双方也只属于当前AI。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Box{TextButton({lifeMenu=true}){Text("状态："+lifeLabel(life))};DropdownMenu(lifeMenu,{lifeMenu=false}){MemoryLife.entries.forEach{l->DropdownMenuItem({Text(lifeLabel(l))},{life=l;lifeMenu=false},modifier=Modifier.testTag("life-${l.name}"))}}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("核心",Modifier.weight(1f));Switch(core,{core=it},Modifier.testTag("memory-core"));Spacer(Modifier.width(12.dp));Text("锁定");Switch(lock,{lock=it},Modifier.testTag("memory-lock"))}
        if(!editable)Text("正在总结记忆，结束或停止后可保存；草稿保留。",style=MaterialTheme.typography.bodySmall,color=Muted)
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }},modifier=Modifier.imePadding(),confirmButton={TextButton({try{val parsed=MemoryFacets.parseTags(tags);MemoryFacets.dueDate(due);if(birthday.isNotBlank())MemoryDates.monthDay(birthday);save(text,category,about,parsed,due,life,core,lock,birthday.takeUnless{it==old?.annualMonthDay.orEmpty()})}catch(_:Exception){error="请检查标签数量、长度和日期格式。"}},enabled=editable&&text.isNotBlank(),modifier=Modifier.testTag("memory-save")){Text("保存")}},dismissButton={TextButton(dismiss){Text("取消")}})
}

@Composable private fun MemoryChoice(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit,modifier:Modifier=Modifier){
    FilterChip(selected,onClick,label,modifier=modifier.padding(vertical=2.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),border=BorderStroke(.5.dp,if(selected)GreenText.copy(alpha=.3f) else Line),colors=FilterChipDefaults.filterChipColors(selectedContainerColor=SelectedBackground,selectedLabelColor=GreenText))
}
