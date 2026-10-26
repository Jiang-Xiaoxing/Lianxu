package app.lianxu.independent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun ChatTurnControls(vm:AppState,turn:ChatTurn){
    if(turn.state==TurnState.COMPLETED)return
    Column(Modifier.widthIn(max=292.dp).testTag("turn-state-${turn.id}")){
        Text(when(turn.state){TurnState.PENDING->"正在请求回复…";TurnState.CANCELLED->"已取消，没有自动重发。";TurnState.INTERRUPTED->ChatFailure.INTERRUPTED.display;else->turn.failure?.display?:"回复未完成。"},style=MaterialTheme.typography.bodySmall,color=if(turn.state==TurnState.FAILED)MaterialTheme.colorScheme.error else Muted)
        if(turn.state==TurnState.PENDING)TextButton(onClick={vm.cancelChat(turn.friendId)},modifier=Modifier.heightIn(min=48.dp).testTag("cancel-turn-${turn.id}")){Text("停止")}
        else if(vm.turns.lastOrNull{it.friendId==turn.friendId}?.id==turn.id)TextButton(onClick={vm.retryChat(turn)},enabled=!vm.chatSending,modifier=Modifier.heightIn(min=48.dp).testTag("retry-turn-${turn.id}")){Text("重试")}
    }
}
