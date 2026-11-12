package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private suspend fun LazyListState.showEndOf(index:Int){
    scrollToItem(index)
    val info=layoutInfo
    val item=info.visibleItemsInfo.lastOrNull{it.index==index}?:return
    val overflow=item.offset+item.size-(info.viewportEndOffset-info.afterContentPadding)
    if(overflow>0)scrollBy(overflow.toFloat())
}

/** Centered history state for a committed memory batch; one line, wraps instead of clipping at large font scales. */
@Composable internal fun MemorySummaryPill(pill:MemoryPill){
    val shape=RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().padding(vertical=2.dp).testTag("memory-pill-${pill.batchId}"),horizontalArrangement=Arrangement.Center){
        Row(Modifier.widthIn(max=280.dp).clip(shape).background(Paper).border(.5.dp,Line,shape).padding(horizontal=12.dp,vertical=6.dp).testTag("memory-pill-box-${pill.batchId}"),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            ControlIcon("memory",GreenText,16.dp)
            Text(pill.text,style=MaterialTheme.typography.labelMedium,color=GreenText,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun Chat(vm:AppState){
    val friend=vm.friends.find{it.id==vm.activeId}?:return
    val parameterCards=rememberParameterCards(vm,friend.id)
    // Pending prefixes are durable already, but the attempt preview owns their one visible copy.
    val pendingBodies=vm.turns.filter{it.friendId==friend.id&&it.state==TurnState.PENDING&&vm.chatPreview?.let{p->p.turnId==it.id&&p.attempt==it.attempt}==true}.mapNotNull{it.assistantMessageId}.toSet()
    val messages=vm.messages.filter{it.friendId==friend.id&&it.id !in pendingBodies}
    val pills=vm.memoryPills.filter{it.friendId==friend.id}
    val timeline=remember(messages,pills){chatTimeline(messages,pills)}
    val openTurn=vm.turns.lastOrNull{it.friendId==friend.id}?.takeIf{it.state!=TurnState.COMPLETED}
    val preview=vm.chatPreview?.takeIf{openTurn?.state==TurnState.PENDING&&it.turnId==openTurn.id&&it.attempt==openTurn.attempt}
    val list=rememberLazyListState()
    val jump = remember(friend.id) { vm.memoryChatRequest.consume() }
    var highlighted by remember(friend.id) { mutableStateOf(false) }
    val highlightAlpha by androidx.compose.animation.core.animateFloatAsState(if(highlighted) .22f else 0f, label="memory-highlight")
    val highlightIds = remember(jump,vm.turns) { vm.turns.firstOrNull { it.friendId==friend.id && it.userMessageId==jump?.turnId }?.let { setOf(it.userMessageId,it.assistantMessageId) }.orEmpty() }
    LaunchedEffect(jump){
        if(jump!=null){
            val index=timeline.indexOfFirst{it is ChatRow.Group && it.value.messages.any{m->m.id==jump.turnId}}
            if(index>=0){
                withFrameNanos{}
                list.scrollToItem(index,-(list.layoutInfo.viewportEndOffset/3))
                highlighted=true
                kotlinx.coroutines.delay(1500)
                highlighted=false
            }
        }
    }
    val draftState=rememberSaveable(friend.id,saver=androidx.compose.runtime.saveable.listSaver(
        save={state:ChatDraftState->listOf(state.text.value,state.version.longValue)},
        restore={saved->vm.chatDrafts.getOrPut(friend.id){ChatDraftState(saved[0] as String,saved[1] as Long)}}
    )){vm.chatDrafts.getOrPut(friend.id){ChatDraftState()}}
    var draft by draftState.text
    var draftVersion by draftState.version
    var submittedVersion by draftState.submittedVersion
    var persistedVersion by draftState.persistedVersion
    var submittedAt by draftState.submittedAt
    var lastSeen by rememberSaveable(friend.id){mutableStateOf<Long?>(null)}
    var attachmentInfo by remember{mutableStateOf(false)}
    var controlMessage by remember{mutableStateOf<Message?>(null)}
    var viewport by remember{mutableStateOf(Rect.Zero)}
    var focused by remember{mutableStateOf(false)}
    val colors=MaterialTheme.colorScheme
    // A receipt's opened card reads the stored row as it is now; it never replays tool arguments or the reply.
    val memoryReader: suspend (String) -> StoredMemory? = remember(vm, friend.id) {
        { id -> vm.processing.read { db -> vm.processing.memory(db, friend.id, id) } }
    }
    val time=remember{SimpleDateFormat("HH:mm",Locale.getDefault())}
    // The attempt owns delivered bubbles, so switching pages never releases an unseen tail.
    val pacedTurn=preview?.turnId
    var pacedShown by remember(friend.id,preview?.turnId,preview?.attempt){mutableIntStateOf(preview?.visible?:0)}
    LaunchedEffect(pacedTurn,preview?.attempt,preview?.bubbles?.size,preview?.visible,pacedShown){
        val p=preview?:return@LaunchedEffect
        if(pacedShown<p.visible)pacedShown=p.visible
        if(pacedShown<p.bubbles.size&&pacedShown<=p.visible){
            kotlinx.coroutines.delay(ReplyPacing.waitBefore(pacedShown,p.bubbles))
            pacedShown++
        }
    }
    val typing=vm.chatSending
    fun send(sent:String=draft,version:Long=draftVersion){if(sent.isNotBlank()&&vm.chatInputAvailable&&
        (version>submittedVersion||version==submittedVersion&&!vm.chatSending&&version>persistedVersion)){
        val previous=submittedVersion
        submittedVersion=version
        if(vm.send(sent){persistedVersion=maxOf(persistedVersion,version);if(draftVersion==version)draft=""})submittedAt=android.os.SystemClock.uptimeMillis()
        else submittedVersion=previous
    }}
    LaunchedEffect(messages.lastOrNull()?.id,vm.notificationTarget){
        if(jump!=null && lastSeen==null){lastSeen=messages.lastOrNull()?.id;return@LaunchedEffect}
        val id=messages.lastOrNull()?.id
        val target=vm.notificationTarget?:if(lastSeen==null)vm.companionReceipts.firstOrNull{r->r.readAt==null&&messages.any{it.id==r.messageId}}?.messageId else null
        if(target!=null){val index=timeline.indexOfFirst{it is ChatRow.Group&&it.value.messages.any{m->m.id==target}};if(index>=0){list.scrollToItem(index);vm.notificationShown();lastSeen=id}}
        else if(id!=null&&id!=lastSeen&&timeline.isNotEmpty()){list.scrollToItem(timeline.lastIndex);lastSeen=id}
    }
    Column(Modifier.fillMaxSize().background(ChatPageBackground)){
        Row(Modifier.fillMaxWidth().heightIn(min=68.dp).background(ChatBarBackground).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={vm.back()},modifier=Modifier.size(UiSpace.action).testTag("back").semantics{contentDescription="返回"}){ControlIcon("back",Ink)}
            Text(if(typing)"对方正在输入…" else friend.name,Modifier.weight(1f).padding(horizontal=8.dp,vertical=8.dp).testTag("chat-title"),style=PageTitleStyle,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            IconButton(onClick={vm.open("detail",friend.id)},modifier=Modifier.size(UiSpace.action).testTag("chat-role-detail").semantics{contentDescription="角色资料"}){ControlIcon("more")}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned{val p=it.localToWindow(Offset.Zero);viewport=Rect(p,it.size.toSize())}){
            val width=minOf(320.dp,maxWidth*.78f-32.dp)
            val historyHeight=maxHeight
            // The keyboard can finish opening after the turn state changes. Lazy items outside the shrunken viewport are disposed,
            // so the list itself keeps the latest unfinished turn's status and retry/cancel control at the bottom edge.
            LaunchedEffect(historyHeight,openTurn?.id,openTurn?.state,openTurn?.attempt,timeline.size){
                if(jump==null&&openTurn!=null&&timeline.isNotEmpty()){withFrameNanos{};list.showEndOf(timeline.lastIndex)}
            }
            LaunchedEffect(preview?.text?.length,preview?.phase,preview?.tools){
                if(preview!=null&&!list.isScrollInProgress&&list.layoutInfo.visibleItemsInfo.lastOrNull()?.index==timeline.lastIndex){
                    withFrameNanos{};list.showEndOf(timeline.lastIndex)
                }
            }
            LaunchedEffect(pacedShown){
                if(pacedTurn!=null&&!list.isScrollInProgress&&list.layoutInfo.visibleItemsInfo.lastOrNull()?.index==timeline.lastIndex&&timeline.isNotEmpty()){
                    withFrameNanos{};list.showEndOf(timeline.lastIndex)
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("chat-history"),state=list,contentPadding=PaddingValues(horizontal=UiSpace.page,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                items(timeline,key={it.key}){row->when(row){
                    is ChatRow.Date->{val date=LocalDate.ofEpochDay(row.day);Text(if(date==LocalDate.now())"今天" else date.format(DateTimeFormatter.ofPattern("M月d日")),Modifier.fillMaxWidth().padding(bottom=8.dp),style=MaterialTheme.typography.labelSmall,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                    is ChatRow.Pill->MemorySummaryPill(row.value)
                    is ChatRow.Group->{val group=row.value
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(group.mine)Arrangement.End else Arrangement.Start,verticalAlignment=Alignment.Top){
                            if(!group.mine){Avatar(friend,32);Spacer(Modifier.width(8.dp))}
                            Column(horizontalAlignment=if(group.mine)Alignment.End else Alignment.Start,verticalArrangement=Arrangement.spacedBy(4.dp)){
                                if(!group.mine)Text(friend.name,Modifier.padding(start=4.dp).testTag("sender-name-${group.messages.first().id}"),style=MaterialTheme.typography.labelMedium,color=Muted,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                group.messages.forEach{message->
                                    var bounds by remember(message.id){mutableStateOf(Rect.Zero)}
                                    val shape=RoundedCornerShape(topStart=if(!group.mine)6.dp else 18.dp,topEnd=if(group.mine)6.dp else 18.dp,bottomStart=18.dp,bottomEnd=18.dp)
                                    if(!message.mine){
                                        val turn=vm.turns.firstOrNull{it.assistantMessageId==message.id}
                                        val activities=mergeRecordPills(turn?.toolActivities.orEmpty())
                                        // Receipts from the final attempt keep the thinking offset they were
                                        // first shown at; the stored reply carries the same accumulated text,
                                        // so the finished message shows thinking A, receipt, thinking B.
                                        val placed=placedReceipts(activities,turn?.attempt)
                                        val interleaved=placed.isNotEmpty()&&message.displayReasoning.isNotBlank()
                                        val outside=if(interleaved)activities.filterNot{it in placed} else activities
                                        ChatToolActivities(outside,Modifier.width(width),loadMemory=memoryReader)
                                        if(interleaved)ChatThinkingStream("message-${message.id}",message.displayReasoning,false,
                                            placed.sortedBy{it.reasoningAt}.map{ChatThinkingBreak(it.reasoningAt,it.id)},activities,Modifier.width(width),loadMemory=memoryReader)
                                        else ChatReasoningPanel("message-${message.id}",message.displayReasoning,false,Modifier.width(width))
                                    }
                                    Column(Modifier.widthIn(max=width).onGloballyPositioned{val p=it.localToWindow(Offset.Zero);bounds=Rect(p,it.size.toSize())}.testTag("message-${message.id}").semantics(mergeDescendants=true){text=androidx.compose.ui.text.AnnotatedString(message.text)}.combinedClickable(onClick={},onLongClick={controlMessage=message}),verticalArrangement=Arrangement.spacedBy(5.dp)){
                                        val segments=ChatBubbleSegments.forMessage(message.text,message.mine)
                                        segments.forEachIndexed{index,segment->
                                            Text(segment,Modifier.widthIn(max=width).clip(shape).background(if(group.mine)ChatUserBubble else ChatRoleBubble).background(colors.primary.copy(alpha=if(message.id in highlightIds)highlightAlpha else 0f)).padding(UiSpace.bubble).clearAndSetSemantics{testTag=if(highlighted && message.id in highlightIds)"chat-turn-highlight" else "bubble-${message.id}-$index"},style=MaterialTheme.typography.bodyLarge,color=if(group.mine)ChatUserText else ChatRoleText)
                                        }
                                    }
                                    if(message.mine)RelationshipParameterCard(vm,friend,message,parameterCards)
                                    if(!message.mine)CompanionReadMarker(vm,message,bounds,viewport,!attachmentInfo&&controlMessage==null)
                                }
                                if(row.showTime)Text(time.format(Date(group.messages.last().created)),Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.labelSmall,color=Muted)
                            }
                        }
                        group.messages.mapNotNull{message->vm.turns.firstOrNull{it.userMessageId==message.id&&it.state !in setOf(TurnState.COMPLETED,TurnState.SUPERSEDED)}}.forEach{turn->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){
                                Avatar(friend,32);Spacer(Modifier.width(8.dp))
                                if(turn.state==TurnState.PENDING)ChatReplyDraft(preview,turn,Modifier.width(width),if(pacedTurn==turn.id)pacedShown else 0,viewport){count->vm.replyShown(friend.id,turn.id,turn.attempt,count)}
                                else Column(Modifier.width(width),verticalArrangement=Arrangement.spacedBy(8.dp)){if(turn.assistantMessageId==null)ChatToolActivities(mergeRecordPills(turn.toolActivities));ChatTurnControls(vm,turn)}
                            }
                        }
                        }
                    }
                }}
            }
        }
        Surface(color=ChatBarBackground,shadowElevation=0.dp){Column{
            HorizontalDivider(color=ChatInputBorder,thickness=.5.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                IconButton(onClick={attachmentInfo=true},modifier=Modifier.padding(bottom=4.dp).size(UiSpace.action).testTag("chat-attach").semantics{contentDescription="附件，暂未接入"}){ControlIcon("attach")}
                val shape=RoundedCornerShape(6.dp)
                TextField(draft,{draft=it.take(2000);draftVersion++},modifier=Modifier.weight(1f).onFocusChanged{focused=it.hasFocus}.border(if(focused)1.dp else .5.dp,ChatInputBorder,shape).testTag("message-input"),placeholder={Text("说点什么…",style=MaterialTheme.typography.bodyLarge)},textStyle=MaterialTheme.typography.bodyLarge,maxLines=4,shape=shape,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),colors=TextFieldDefaults.colors(focusedContainerColor=Color.White,unfocusedContainerColor=Color.White,disabledContainerColor=Color.White,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,cursorColor=ChatSendButton))
                val stopping=vm.chatSending&&draft.isBlank()
                val enabled=stopping||draft.isNotBlank()&&vm.chatInputAvailable
                Box(Modifier.padding(bottom=4.dp).size(width=60.dp,height=48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled=enabled,role=Role.Button){if(stopping){
                        // The second tap of Send must not become Stop when persistence clears the
                        // field. Only this newly converted Stop is guarded; a new draft sends now.
                        if(android.os.SystemClock.uptimeMillis()-submittedAt>android.view.ViewConfiguration.getDoubleTapTimeout())vm.stopReply(friend.id)
                    }else send()}
                    .testTag(if(stopping)"cancel-turn-${openTurn?.id.orEmpty()}"else"send-message")
                    .semantics{contentDescription=if(stopping)"停止回复"else"发送"},contentAlignment=Alignment.Center){
                    Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(ChatSendButton.copy(alpha=if(enabled).18f else .07f)),contentAlignment=Alignment.Center){
                        Text(if(stopping)"停止"else"发送",style=MaterialTheme.typography.labelLarge,color=ChatSendButton.copy(alpha=if(enabled)1f else .38f))
                    }
                }
            }
        }}
    }
    controlMessage?.let{m->AlertDialog(onDismissRequest={controlMessage=null},title={Text("这条消息的使用方式")},text={Text("停止提醒会保留记忆。不再使用会停止相关原话、记忆与判断的AI使用，并取消回访；原聊天保留供你查看。")},
        confirmButton={TextButton(onClick={vm.sourceControl(m.id,true);controlMessage=null},modifier=Modifier.testTag("companion-forget")){Text("不再用于记忆和判断")}},
        dismissButton={Row{TextButton(onClick={vm.sourceControl(m.id,false);controlMessage=null},modifier=Modifier.testTag("companion-stop-reminder")){Text("只停关联提醒")};TextButton(onClick={controlMessage=null}){Text("取消")}}})}
    if(attachmentInfo)ModalBottomSheet(onDismissRequest={attachmentInfo=false},containerColor=Paper){Column(Modifier.padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("附件暂未接入",style=MaterialTheme.typography.titleMedium)
        Text("当前版本仅支持文字消息，不会读取或发送图片与文件。",style=MaterialTheme.typography.bodyMedium,color=Muted)
        TextButton(onClick={attachmentInfo=false},modifier=Modifier.heightIn(min=UiSpace.action).testTag("close-attachment-info")){Text("知道了")}
    }}
}
