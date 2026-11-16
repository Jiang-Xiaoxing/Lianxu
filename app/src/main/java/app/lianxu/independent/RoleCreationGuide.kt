package app.lianxu.independent

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Same light sample, source bounds and stretch as the settled approved door. */
@Composable private fun EncounterWarmLight(modifier:Modifier){
    val resources=LocalContext.current.resources
    val painting=remember(resources){BitmapFactory.decodeResource(resources,R.drawable.first_encounter_approved,BitmapFactory.Options().apply{inScaled=false})}
    val paint=remember{Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)}
    val source=remember{Rect(540,40,1010,630)}
    val target=remember{RectF()}
    Canvas(modifier){drawIntoCanvas{target.set(0f,0f,size.width,size.height);it.nativeCanvas.drawBitmap(painting,source,target,paint)}}
}

@Composable internal fun RoleCreationGuide(
    draft:RoleCreationDraft,onDraft:(RoleCreationDraft)->Unit,busy:Boolean,saved:Boolean=false,
    onExit:()->Unit,onCreate:(RoleCreationDraft)->Unit
){
    val scope=rememberCoroutineScope()
    val reduced=(scope.coroutineContext[MotionDurationScale]?.scaleFactor?:1f)<=0f
    val position=remember{Animatable(if(reduced)0f else -1f)}
    var entering by remember{mutableStateOf(!reduced)}
    var targetStep by remember{mutableStateOf<RoleCreationStep?>(null)}
    var problem by remember{mutableStateOf<String?>(null)}
    var exitDialog by rememberSaveable{mutableStateOf(false)}
    var submitting by remember{mutableStateOf(false)}
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraft by rememberUpdatedState(onDraft)
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val moving=entering||targetStep!=null
    val enabled=!moving&&!busy&&!submitting
    LaunchedEffect(submitting,busy){if(submitting&&!busy)submitting=false}

    LaunchedEffect(targetStep,reduced){
        if(reduced){
            targetStep?.let{currentOnDraft(currentDraft.copy(step=it))}
            position.snapTo(0f);targetStep=null;entering=false
        }else if(targetStep!=null){
            val next=requireNotNull(targetStep)
            position.animateTo(1f,tween(280,easing=CubicBezierEasing(.4f,0f,.8f,1f)))
            currentOnDraft(currentDraft.copy(step=next))
            position.snapTo(-1f)
            targetStep=null // The new effect lowers the new card from above.
        }else{
            position.animateTo(0f,tween(420,easing=CubicBezierEasing(.2f,0f,.2f,1f)))
            entering=false
        }
    }
    fun locked()=entering||targetStep!=null||busy||saved||submitting
    fun canNavigate()=!locked()&&!exitDialog
    fun moveTo(step:RoleCreationStep){
        if(!canNavigate())return
        focus.clearFocus();keyboard?.hide();problem=null;entering=true;targetStep=step
    }
    fun leave(){
        if(!canNavigate())return
        focus.clearFocus();keyboard?.hide()
        if(draft.hasContent)exitDialog=true else onExit()
    }
    fun previous(){if(canNavigate()){draft.previous()?.let{moveTo(it)}?:leave()}}
    BackHandler{previous()}

    if(exitDialog)AlertDialog(
        onDismissRequest={if(!locked())exitDialog=false},title={Text("暂时离开？")},
        text={Column(verticalArrangement=Arrangement.spacedBy(UiSpace.small)){
            Text("可以保留本次草稿，回来继续填写。")
            TextButton(onClick={if(!locked()){currentOnDraft(RoleCreationDraft());exitDialog=false;onExit()}},modifier=Modifier.testTag("create-discard")){Text("放弃草稿并退出")}
        }},
        confirmButton={TextButton(onClick={if(!locked()){exitDialog=false;onExit()}},modifier=Modifier.testTag("create-keep-exit")){Text("保留草稿并退出")}},
        dismissButton={TextButton(onClick={if(!locked())exitDialog=false}){Text("继续填写")}}
    )
    Box(Modifier.fillMaxSize().testTag("role-creation-guide")){
        EncounterWarmLight(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=UiSpace.small),verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={previous()},enabled=enabled&&!saved,modifier=Modifier.size(UiSpace.action).testTag("create-back").semantics{contentDescription="返回"}){ControlIcon("back",Ink)}
                Text("创建角色",Modifier.weight(1f).padding(horizontal=UiSpace.small),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={leave()},enabled=enabled&&!saved,modifier=Modifier.heightIn(min=UiSpace.action).testTag("create-cancel")){Text("取消")}
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal=UiSpace.page,vertical=UiSpace.small).clipToBounds()){
                val travel=with(LocalDensity.current){maxHeight.toPx()*2f}
                Card(
                    modifier=Modifier.align(Alignment.Center).widthIn(max=480.dp).fillMaxWidth().heightIn(max=maxHeight)
                        .graphicsLayer{translationY=position.value*travel}
                        .testTag("create-card").semantics{stateDescription=if(moving)"切换中"else"可填写"},
                    shape=RoundedCornerShape(UiSpace.radius),colors=CardDefaults.cardColors(containerColor=Paper)
                ){
                    key(draft.step){
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpace.group),verticalArrangement=Arrangement.spacedBy(UiSpace.group)){
                            Text("${draft.step.ordinal+1} / ${RoleCreationStep.entries.size}",style=MaterialTheme.typography.bodySmall,color=Muted)
                            Text(when(draft.step){
                                RoleCreationStep.NAME->"你想怎样称呼这位角色？"
                                RoleCreationStep.DEFINITION->"这是一位怎样的角色？"
                                RoleCreationStep.RELATIONSHIP->"你们之间是什么关系？"
                                RoleCreationStep.MORE->"还有想补充的吗？"
                                RoleCreationStep.CONFIRM->"准备好开始相遇了吗？"
                            },Modifier.testTag("create-step-${draft.step.name.lowercase()}").semantics{liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.titleLarge)
                            when(draft.step){
                                RoleCreationStep.NAME->{
                                    Text("从一个名字开始，不需要先选性别。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    AppField(draft.name,{onDraft(draft.copy(name=creationText(it,30)));problem=null},"名字（必填）","friend-name",singleLine=true,enabled=enabled&&!saved)
                                }
                                RoleCreationStep.DEFINITION->{
                                    Text("写下性格、经历，或你希望保留的特点。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    AppField(draft.definition,{onDraft(draft.copy(definition=creationText(it,8000)));problem=null},"角色设定（必填）","friend-bio",minLines=3,enabled=enabled&&!saved)
                                }
                                RoleCreationStep.RELATIONSHIP->{
                                    Text("这是你设定的相处起点，可以留空或跳过。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    AppField(draft.relationship,{onDraft(draft.copy(relationship=creationText(it,4000)))},"初始关系背景（可选）","friend-relationship",minLines=2,enabled=enabled&&!saved)
                                }
                                RoleCreationStep.MORE->{
                                    Text("更多设置均可留空，之后也能在角色设定里修改。",style=MaterialTheme.typography.bodyMedium,color=Muted)
                                    AppField(draft.expression,{onDraft(draft.copy(expression=creationText(it,8000)))},"说话方式（可选）","friend-expression",minLines=2,enabled=enabled&&!saved)
                                    AppField(draft.opening,{onDraft(draft.copy(opening=creationText(it,8000)))},"开场白（可选）","friend-opening",minLines=2,enabled=enabled&&!saved)
                                    AppField(draft.extra,{onDraft(draft.copy(extra=creationText(it,4000)))},"自定义提示词（可选）","friend-prompt",minLines=3,enabled=enabled&&!saved)
                                    Text("模型仍在设置中选择；未配置聊天模型时不会生成演示回复。",style=MaterialTheme.typography.bodySmall,color=Muted)
                                }
                                RoleCreationStep.CONFIRM->{
                                    CreationSummary("名字",draft.name)
                                    CreationSummary("角色设定",draft.definition)
                                    CreationSummary("初始关系背景",draft.relationship.ifBlank{"暂未设置"})
                                    if(draft.expression.isNotBlank())CreationSummary("说话方式",draft.expression)
                                    if(draft.opening.isNotBlank())CreationSummary("开场白",draft.opening)
                                    if(draft.extra.isNotBlank())CreationSummary("自定义提示词",draft.extra)
                                    Text("长内容在此简要显示，完整填写内容会保存。可返回修改。",style=MaterialTheme.typography.bodySmall,color=Muted)
                                }
                            }
                            problem?.let{Text(it,Modifier.testTag("create-validation"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
                            if(draft.step==RoleCreationStep.RELATIONSHIP)TextButton(onClick={if(canNavigate()){onDraft(draft.copy(relationship=""));moveTo(RoleCreationStep.MORE)}},enabled=enabled,modifier=Modifier.testTag("create-skip-relationship")){Text("跳过")}
                            PrimaryAction(if(draft.step==RoleCreationStep.CONFIRM)if(busy)"正在创建…"else if(saved)"打开已创建的角色"else"确认创建"else"下一步",
                                enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(if(draft.step==RoleCreationStep.CONFIRM)"create-friend"else"create-next")){
                                if(!entering&&targetStep==null&&!busy&&!submitting&&!exitDialog){
                                    problem=draft.problem()
                                    if(problem==null){
                                        if(draft.step==RoleCreationStep.CONFIRM){submitting=true;focus.clearFocus();keyboard?.hide();onCreate(draft)}
                                        else draft.next()?.let{moveTo(it)}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CreationSummary(label:String,value:String){
    Column(verticalArrangement=Arrangement.spacedBy(UiSpace.cluster)){
        Text(label,style=MaterialTheme.typography.labelLarge,color=Muted)
        Text(value,style=MaterialTheme.typography.bodyMedium,maxLines=4,overflow=TextOverflow.Ellipsis)
    }
}
