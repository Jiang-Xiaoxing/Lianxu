package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Chat(vm:AppState){
    val friend=vm.friends.find{it.id==vm.activeId}?:return
    val messages=vm.messages.filter{it.friendId==friend.id}
    val timeline=remember(messages){chatTimeline(messages)}
    val list=rememberLazyListState()
    var draft by rememberSaveable(friend.id){mutableStateOf("")}
    var lastSeen by rememberSaveable(friend.id){mutableStateOf<Long?>(null)}
    var attachmentInfo by remember{mutableStateOf(false)}
    var focused by remember{mutableStateOf(false)}
    val colors=MaterialTheme.colorScheme
    val time=remember{SimpleDateFormat("HH:mm",Locale.getDefault())}
    fun send(){if(draft.isNotBlank()&&!vm.busy){val sent=draft;vm.send(sent){if(draft==sent)draft=""}}}
    LaunchedEffect(messages.lastOrNull()?.id){
        val id=messages.lastOrNull()?.id
        if(id!=null&&id!=lastSeen&&timeline.isNotEmpty()){list.scrollToItem(timeline.lastIndex);lastSeen=id}
    }
    Column(Modifier.fillMaxSize().background(ChatBackground)){
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).background(Paper).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={vm.back()},modifier=Modifier.size(UiSpace.action).testTag("back").semantics{contentDescription="返回"}){ControlIcon("back",Ink)}
            Row(Modifier.weight(1f).padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Avatar(friend,36);Column{Text(friend.name,style=MaterialTheme.typography.titleMedium);Text("AI 朋友 · 独立会话",style=MaterialTheme.typography.labelSmall,color=Muted)}
            }
            TextButton(onClick={vm.open("detail",friend.id)},modifier=Modifier.heightIn(min=UiSpace.action)){Text("资料")}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
            val width=minOf(292.dp,maxWidth*.76f)
            LazyColumn(Modifier.fillMaxSize().testTag("chat-history"),state=list,contentPadding=PaddingValues(horizontal=UiSpace.page,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                items(timeline,key={it.key}){row->when(row){
                    is ChatRow.Date->{val date=LocalDate.ofEpochDay(row.day);Text(if(date==LocalDate.now())"今天" else date.format(DateTimeFormatter.ofPattern("M月d日")),Modifier.fillMaxWidth().padding(bottom=8.dp),style=MaterialTheme.typography.labelSmall,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                    is ChatRow.Group->{val group=row.value
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(group.mine)Arrangement.End else Arrangement.Start,verticalAlignment=Alignment.Top){
                            if(!group.mine){Avatar(friend,32);Spacer(Modifier.width(8.dp))}
                            Column(horizontalAlignment=if(group.mine)Alignment.End else Alignment.Start,verticalArrangement=Arrangement.spacedBy(4.dp)){
                                group.messages.forEachIndexed{index,message->
                                    val shape=RoundedCornerShape(topStart=if(!group.mine&&index>0)6.dp else 18.dp,topEnd=18.dp,bottomStart=if(!group.mine&&index<group.messages.lastIndex)6.dp else 18.dp,bottomEnd=if(group.mine)8.dp else 18.dp)
                                    Text(message.text,Modifier.widthIn(max=width).clip(shape).background(if(group.mine)SentBubble else Paper).padding(UiSpace.bubble).testTag("message-${message.id}"),style=MaterialTheme.typography.bodyLarge,color=if(group.mine)colors.onPrimaryContainer else Ink)
                                    vm.turns.firstOrNull{it.userMessageId==message.id}?.let{ChatTurnControls(vm,it)}
                                }
                                if(row.showTime)Text(time.format(Date(group.messages.last().created)),Modifier.padding(horizontal=4.dp),style=MaterialTheme.typography.labelSmall,color=Muted)
                            }
                        }
                    }
                }}
            }
        }
        Surface(color=Paper){Column{
            HorizontalDivider(color=Line,thickness=.5.dp)
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                IconButton(onClick={attachmentInfo=true},modifier=Modifier.padding(bottom=4.dp).size(UiSpace.action).testTag("chat-attach").semantics{contentDescription="附件，暂未接入"}){ControlIcon("attach")}
                val shape=RoundedCornerShape(22.dp)
                TextField(draft,{draft=it.take(2000)},modifier=Modifier.weight(1f).onFocusChanged{focused=it.hasFocus}.border(if(focused)1.dp else 0.dp,if(focused)GreenText else Color.Transparent,shape).testTag("message-input"),placeholder={Text("和${friend.name}说说话…",style=MaterialTheme.typography.bodyLarge)},textStyle=MaterialTheme.typography.bodyLarge,maxLines=4,shape=shape,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),colors=TextFieldDefaults.colors(focusedContainerColor=Chrome,unfocusedContainerColor=Chrome,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
                FilledIconButton(onClick={send()},enabled=draft.isNotBlank()&&!vm.busy,modifier=Modifier.padding(bottom=4.dp).size(UiSpace.action).testTag("send-message").semantics{contentDescription="发送"},shape=RoundedCornerShape(16.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Accent,contentColor=colors.onSecondaryContainer)){
                    if(vm.busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=LocalContentColor.current) else ControlIcon("send",LocalContentColor.current)
                }
            }
        }}
    }
    if(attachmentInfo)ModalBottomSheet(onDismissRequest={attachmentInfo=false},containerColor=Paper){Column(Modifier.padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("附件暂未接入",style=MaterialTheme.typography.titleMedium)
        Text("当前版本仅支持文字消息，不会读取或发送图片与文件。",style=MaterialTheme.typography.bodyMedium,color=Muted)
        TextButton(onClick={attachmentInfo=false},modifier=Modifier.heightIn(min=UiSpace.action).testTag("close-attachment-info")){Text("知道了")}
    }}
}
