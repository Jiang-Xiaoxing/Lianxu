package app.lianxu.independent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** One piece of the live thinking stream: model text, or the receipt of a tool called inside it. */
internal sealed interface ChatThinkingPart {
    data class Text(val text:String):ChatThinkingPart
    data class Tool(val item:ChatToolActivity):ChatThinkingPart
}
/** Splits accumulated thinking at the recorded receipts. Null means "no receipt yet": one text block. */
internal fun chatThinkingParts(text:String,breaks:List<ChatThinkingBreak>,tools:List<ChatToolActivity>):List<ChatThinkingPart>? {
    if(breaks.isEmpty())return null
    val byId=tools.associateBy{it.id}
    val out=mutableListOf<ChatThinkingPart>();var start=0
    for(b in breaks.sortedBy{it.at}){
        // A superseded pill is not shown, so it does not split the thinking either.
        if(byId[b.toolId]?.state==ChatToolActivity.SUPERSEDED)continue
        val at=b.at.coerceIn(start,text.length)
        if(at>start)out+=ChatThinkingPart.Text(text.substring(start,at))
        byId[b.toolId]?.let{out+=ChatThinkingPart.Tool(it)}
        start=at
    }
    if(start<text.length)out+=ChatThinkingPart.Text(text.substring(start))
    return out.takeIf{parts->parts.any{it is ChatThinkingPart.Tool}}
}

/** Plain message thinking without tool receipts: one card, same as before. */
@Composable internal fun ChatReasoningPanel(owner:String,text:String,inProgress:Boolean,modifier:Modifier=Modifier,defaultExpanded:Boolean=false){
    if(text.isBlank())return
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
        ChatThinkingCard(owner,text,inProgress,Modifier.fillMaxWidth(),defaultExpanded)
    }
}

/**
 * One independent thinking card per real model segment. A tool receipt is a pill in the chat flow
 * between cards, never inside a card and never inside a wrapping container. When the model returns
 * no text after a call, no second card is invented.
 */
@Composable internal fun ChatThinkingStream(owner:String,text:String,inProgress:Boolean,breaks:List<ChatThinkingBreak>,tools:List<ChatToolActivity>,modifier:Modifier=Modifier,loadMemory:(suspend(String)->StoredMemory?)?=null){
    val parts=chatThinkingParts(text,breaks,tools)
    if(parts==null){ChatReasoningPanel(owner,text,inProgress,modifier);return}
    var textIndex=0
    Column(modifier.testTag("thinking-stream-$owner"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        parts.forEachIndexed{index,part->
            when(part){
                is ChatThinkingPart.Text->if(part.text.isNotBlank()){
                    ChatThinkingCard("$owner-$textIndex",part.text,inProgress&&index==parts.lastIndex,defaultExpanded=true)
                    textIndex++
                }
                is ChatThinkingPart.Tool->ChatToolReceipt(part.item,Modifier.padding(horizontal=14.dp),loadMemory)
            }
        }
    }
}

/** One collapsible piece of native model text. Never creates a message, a memory or a source. */
@Composable private fun ChatThinkingCard(owner:String,text:String,inProgress:Boolean,modifier:Modifier=Modifier,defaultExpanded:Boolean=false){
    if(text.isBlank())return
    // Interleaved cards stay expanded across the live→done switch; a user's collapse is kept.
    // The legacy single card keeps its old behavior (collapses again when the reply finishes).
    var expanded by rememberSaveable(owner,if(defaultExpanded)0 else inProgress,defaultExpanded){mutableStateOf(inProgress||defaultExpanded)}
    val faint=Muted.copy(alpha=.82f)
    Column(modifier.testTag("thinking-card-$owner"),verticalArrangement=Arrangement.spacedBy(4.dp)){
        if(!expanded){
            Text("想了想 ›",Modifier.clickable(role=Role.Button){expanded=true}.testTag("thinking-toggle-$owner").semantics{contentDescription="展开思考过程";stateDescription="已收起"}.padding(vertical=2.dp),style=MaterialTheme.typography.labelSmall,color=faint)
        }else{
            Box(Modifier.fillMaxWidth().heightIn(max=220.dp).testTag("thinking-scroll-$owner")){
                SelectionContainer{
                    Text(text,Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("thinking-text-$owner"),style=MaterialTheme.typography.bodySmall,color=faint)
                }
            }
            Text("收起",Modifier.clickable(role=Role.Button){expanded=false}.testTag("thinking-toggle-$owner").semantics{contentDescription="收起思考过程";stateDescription="已展开"}.padding(vertical=2.dp),style=MaterialTheme.typography.labelSmall,color=faint)
        }
    }
}

/** [visible]: how many reply bubbles have been let out so far; the rest wait their turn (she is still typing). */
@Composable internal fun ChatReplyDraft(preview:ChatReplyPreview?,turn:ChatTurn,modifier:Modifier=Modifier,visible:Int=Int.MAX_VALUE,viewport:androidx.compose.ui.geometry.Rect=androidx.compose.ui.geometry.Rect.Zero,onShown:(Int)->Unit={}){
    // Never display an old attempt after retry, cancellation, or a role/page switch.
    val current=preview?.takeIf{turn.state==TurnState.PENDING&&it.turnId==turn.id&&it.attempt==turn.attempt}
    val owner="${turn.id}-${turn.attempt}"
    val tools=current?.tools?.let(::mergeRecordPills)
    val items=tools?:mergeRecordPills(turn.toolActivities)
    // Once there is thinking to cut into, receipts move out of the card and into the chat flow.
    val interleaved=current?.let{it.reasoning.isNotBlank()&&chatThinkingParts(it.reasoning,it.thinkingBreaks,tools.orEmpty())!=null}==true
    Column(modifier.testTag("reply-preview-$owner"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        ChatToolActivities(items,Modifier.fillMaxWidth(),capsules=!interleaved)
        current?.let{
            if(interleaved)ChatThinkingStream(owner,it.reasoning,it.phase!=ChatReplyPhase.REPLYING,it.thinkingBreaks,tools.orEmpty(),Modifier.fillMaxWidth())
            else ChatReasoningPanel(owner,it.reasoning,it.phase!=ChatReplyPhase.REPLYING,Modifier.fillMaxWidth())
        }
        val shown=current?.bubbles?.take(visible).orEmpty()
        if(shown.isNotEmpty()){
            Column(Modifier.testTag("reply-draft-$owner").semantics(mergeDescendants=true){text=androidx.compose.ui.text.AnnotatedString(shown.joinToString("\n\n"))},verticalArrangement=Arrangement.spacedBy(5.dp)){
                shown.forEachIndexed{index,segment->
                    Surface(modifier=Modifier.testTag("bubble-draft-$owner-$index").onGloballyPositioned{
                        val bounds=androidx.compose.ui.geometry.Rect(it.localToWindow(androidx.compose.ui.geometry.Offset.Zero),androidx.compose.ui.geometry.Size(it.size.width.toFloat(),it.size.height.toFloat()))
                        if(viewport!=androidx.compose.ui.geometry.Rect.Zero&&bounds.overlaps(viewport))onShown(index+1)
                    },shape=RoundedCornerShape(topStart=6.dp,topEnd=18.dp,bottomStart=18.dp,bottomEnd=18.dp),color=ChatRoleBubble){
                        Text(segment,Modifier.padding(UiSpace.bubble).clearAndSetSemantics{},style=MaterialTheme.typography.bodyLarge,color=ChatRoleText)
                    }
                }
            }
        }
        Text(when(current?.phase){
            ChatReplyPhase.REPLYING->"正在回复…"
            ChatReplyPhase.THINKING->"正在接收回复…"
            else->"正在请求回复…"
        },Modifier.testTag("turn-state-${turn.id}"),style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}

/** The small receipt pill. Display only; it never becomes dialogue, a memory or a relationship event.
 *  One pill from start to finish: on failure its text is replaced, not extended with a second line. */
@Composable internal fun ChatToolReceipt(item:ChatToolActivity,modifier:Modifier=Modifier,loadMemory:(suspend(String)->StoredMemory?)?=null){
    if(item.state==ChatToolActivity.SUPERSEDED)return
    // Only a committed write linked to a stored row may open; staged/failed/candidate receipts stay shut.
    val canOpen=item.canOpenMemory&&loadMemory!=null
    // A collapse default must survive a page rebuild: plain remember drops the state on recreation.
    var expanded by remember(item.id){mutableStateOf(false) }
    var peek by remember(item.id){mutableStateOf<ChatMemoryPeek?>(null)}
    LaunchedEffect(expanded,item.memoryId,canOpen){
        if(!expanded||!canOpen)return@LaunchedEffect
        val id=item.memoryId?:return@LaunchedEffect
        peek=ChatMemoryPeek.Loading
        peek=try{
            // Always read the row as it is now: an edited memory shows its new text, a removed one says so.
            memoryPeekOf(loadMemory?.invoke(id))
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){ChatMemoryPeek.Unreadable}
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
        Surface(shape=RoundedCornerShape(50),color=Paper,border=BorderStroke(.5.dp,Line),modifier=Modifier.testTag("tool-result-${item.id}")){
            // The click target is inside the pill so the outer test tag stays stable whether or not it opens.
            Row(Modifier.then(if(canOpen)Modifier
                    .clickable(role=Role.Button){if(!expanded)peek=null;expanded=!expanded}
                    .testTag("tool-receipt-toggle-${item.id}")
                    .semantics{contentDescription=if(expanded)"收起这条记忆" else "查看这条记忆";stateDescription=if(expanded)"已展开" else "已收起"} else Modifier)
                .padding(start=12.dp,end=if(canOpen)8.dp else 12.dp,top=6.dp,bottom=6.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                Text(item.label(),style=MaterialTheme.typography.labelMedium,color=when(item.state){"failed"->MaterialTheme.colorScheme.error;"unknown"->Muted;else->GreenText})
                if(canOpen)Box(Modifier.rotate(if(expanded)0f else 180f)){ControlIcon("up",GreenText,14.dp)}
            }
        }
        if(item.detail.isNotBlank())Text(item.detail,style=MaterialTheme.typography.bodySmall,color=Muted)
        if(expanded&&canOpen){
            Surface(Modifier.widthIn(max=280.dp).testTag("memory-peek-${item.id}"),shape=RoundedCornerShape(14.dp),color=Paper,border=BorderStroke(.5.dp,Line)){
                Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    memoryPeekHeader(peek)?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=Muted)}
                    when(val shown=peek){
                        null,ChatMemoryPeek.Loading->Text("正在读取…",style=MaterialTheme.typography.bodySmall,color=Muted)
                        ChatMemoryPeek.Deleted->Text("这条记忆已删除",style=MaterialTheme.typography.bodyMedium,color=Muted)
                        ChatMemoryPeek.Unreadable->Text("暂时读不到这条记忆",style=MaterialTheme.typography.bodyMedium,color=Muted)
                        is ChatMemoryPeek.Saved->Text(shown.text,style=MaterialTheme.typography.bodyMedium,color=Ink)
                    }
                }
            }
        }
    }
}

/** What one receipt's stored row looks like right now. Read from the database, never from tool arguments. */
internal sealed interface ChatMemoryPeek {
    data object Loading:ChatMemoryPeek
    data class Saved(val text:String):ChatMemoryPeek
    data object Deleted:ChatMemoryPeek
    data object Unreadable:ChatMemoryPeek
}
/** 已删的只显示「这条记忆已删除」，不带「已保存」抬头（11-09 定的规则）。 */
internal fun memoryPeekHeader(peek:ChatMemoryPeek?):String? = if(peek==ChatMemoryPeek.Deleted) null else "已保存"
/** A missing row is honestly "deleted"; the card never falls back to the tool arguments or the reply. */
internal fun memoryPeekOf(saved:StoredMemory?):ChatMemoryPeek = when{
    saved==null||saved.userDeleted->ChatMemoryPeek.Deleted
    saved.text.isBlank()->ChatMemoryPeek.Unreadable
    else->ChatMemoryPeek.Saved(saved.text)
}

@Composable internal fun ChatToolActivities(items:List<ChatToolActivity>,modifier:Modifier=Modifier,capsules:Boolean=true,loadMemory:(suspend(String)->StoredMemory?)?=null){
    if(items.isEmpty())return
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){
        items.forEach{item->
            if(item.before.isNotBlank())Surface(shape=RoundedCornerShape(topStart=6.dp,topEnd=18.dp,bottomStart=18.dp,bottomEnd=18.dp),color=ChatRoleBubble){
                Text(item.before,Modifier.padding(UiSpace.bubble).testTag("tool-before-${item.id}"),style=MaterialTheme.typography.bodyLarge,color=ChatRoleText)
            }
            if(capsules)ChatToolReceipt(item,loadMemory=loadMemory)
        }
    }
}
