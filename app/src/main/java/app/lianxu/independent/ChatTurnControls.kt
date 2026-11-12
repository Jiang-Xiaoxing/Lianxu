package app.lianxu.independent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable internal fun ChatTurnControls(vm:AppState,turn:ChatTurn){
    if(turn.state in setOf(TurnState.COMPLETED,TurnState.PENDING,TurnState.SUPERSEDED))return
    val reveal=remember(turn.id){BringIntoViewRequester()}
    val latest=vm.turns.lastOrNull{it.friendId==turn.friendId}?.id==turn.id
    LaunchedEffect(turn.id,turn.attempt,turn.state,latest){
        if(latest&&turn.state==TurnState.FAILED){withFrameNanos{};reveal.bringIntoView()}
    }
    Column(Modifier.widthIn(max=292.dp).bringIntoViewRequester(reveal).testTag("turn-state-${turn.id}")){
        var reason by remember(turn.id,turn.attempt){mutableStateOf(false)}
        if(turn.state==TurnState.FAILED)Text("没发出去，点这里看原因",Modifier.heightIn(min=32.dp).clickable{reason=true}.padding(vertical=6.dp).testTag("turn-failure-${turn.id}"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        else Text(when(turn.state){TurnState.PENDING->"正在请求回复…";TurnState.CANCELLED->"已取消，没有自动重发。";TurnState.INTERRUPTED->ChatFailure.INTERRUPTED.display;else->"回复未完成。"},style=MaterialTheme.typography.bodySmall,color=Muted)
        if(reason)ChatFailureDialog(ChatFailureText.full(turn.failure,turn.failureDetail)){reason=false}
        if(vm.turns.lastOrNull{it.friendId==turn.friendId}?.id==turn.id)TextButton(onClick={vm.retryChat(turn)},enabled=!vm.chatSending,modifier=Modifier.heightIn(min=48.dp).testTag("retry-turn-${turn.id}")){Text("重试")}
    }
}

/** The full reason for a failed turn: our one-line explanation, then what the service actually returned. */
internal object ChatFailureText {
    fun full(failure:ChatFailure?,detail:String)=buildString{
        append(failure?.display?:"回复未完成。")
        // Provider text starts with its HTTP status; anything else is our own description of what went wrong.
        if(detail.isNotBlank())append(if(detail.startsWith("HTTP "))"\n\n服务返回的原文：\n" else "\n\n详细原因：\n").append(detail)
    }
}
@Composable internal fun ChatFailureDialog(text:String,dismiss:()->Unit){
    val clipboard=LocalClipboardManager.current
    var copied by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=dismiss,title={Text("没发出去的原因")},
        text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()).testTag("turn-failure-detail")){SelectionContainer{Text(text,style=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace),color=Ink)}}},
        confirmButton={TextButton({clipboard.setText(AnnotatedString(text));copied=true},modifier=Modifier.testTag("turn-failure-copy")){Text(if(copied)"已复制" else "复制",color=GreenText)}},
        dismissButton={TextButton(dismiss){Text("关闭",color=Muted)}})
}
