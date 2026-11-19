package app.lianxu.independent

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@Composable internal fun ChatVoiceBubble(file: File, original: String, manage:(()->Unit)?=null) {
    key(file.absolutePath) { VoicePlayerContent(file,original,manage) }
}

@Composable private fun VoicePlayerContent(file:File,original:String,manage:(()->Unit)?) {
        val envelope by produceState<Result<VoiceEnvelope>?>(null,file) {
            value=withContext(Dispatchers.IO){runCatching{VoiceAudio.decode(file)}}
        }
        val decoded=envelope ?: return
        val audio=decoded.getOrNull()
        if(audio==null) { VoiceFallback(original,manage); return }
        var menu by remember { mutableStateOf(false) }
        var transcript by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var playing by remember { mutableStateOf(false) }
        var preparing by remember { mutableStateOf(false) }
        var broken by remember { mutableStateOf(false) }
        var position by remember { mutableIntStateOf(0) }
        var duration by remember { mutableIntStateOf(audio.durationMs.toInt().coerceAtLeast(1)) }
        fun release() { player?.release();player=null;playing=false;preparing=false;position=0 }
        fun toggle() {
            val current=player
            if(preparing) return
            try {
                if(current!=null) {
                    if(playing){current.pause();position=current.currentPosition;playing=false}
                    else {current.start();playing=true}
                } else {
                    val p=MediaPlayer();player=p;preparing=true
                    p.setOnPreparedListener { if(player===it){duration=it.duration.coerceAtLeast(1);preparing=false;it.start();playing=true} }
                    p.setOnCompletionListener { if(player===it) release() }
                    p.setOnErrorListener { _,_,_->release();broken=true;true }
                    p.setDataSource(file.absolutePath);p.prepareAsync()
                }
            } catch(_:Exception) {release();broken=true}
        }
        val owner=LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val observer=LifecycleEventObserver { _,event-> if(event==Lifecycle.Event.ON_STOP) release() }
            owner.lifecycle.addObserver(observer)
            onDispose {owner.lifecycle.removeObserver(observer);release()}
        }
        LaunchedEffect(playing) { while(playing){position=player?.currentPosition?:0;delay(35)} }
        if(broken){ VoiceFallback(original,manage);return }
        val progress=(position.toFloat()/duration).coerceIn(0f,1f)
        val reduced=rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor==0f
        val seconds=((duration+500)/1000).coerceAtLeast(1)
        val playedColor=ChatRoleText
        Box {
            Row(Modifier.widthIn(max=244.dp).heightIn(min=48.dp).clip(RoundedCornerShape(16.dp)).background(ChatRoleBubble)
                .combinedClickable(role=Role.Button,onClickLabel=if(playing)"暂停" else "播放",onLongClickLabel="查看原文",
                    onClick={toggle()},onLongClick={menu=true}).semantics {
                    progressBarRangeInfo=ProgressBarRangeInfo(progress,0f..1f)
                    contentDescription="语音，${seconds}秒"
                    stateDescription=if(playing)"正在播放" else if(position>0)"已暂停" else "待播放"
                }.testTag("chat-voice-bubble").padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(playing)"Ⅱ" else "▶",Modifier.width(30.dp).clearAndSetSemantics{},color=ChatRoleText)
                Canvas(Modifier.width(126.dp).height(29.dp)) {
                    val step=size.width/audio.levels.size
                    val head=progress*audio.levels.size
                    audio.levels.forEachIndexed { i,amp->
                        val lift=if(playing && !reduced) (1f-abs(i+0.5f-head)/1.5f).coerceAtLeast(0f)*amp*3.dp.toPx() else 0f
                        val h=1.4.dp.toPx()+amp*8.dp.toPx()+lift
                        val x=step*(i+0.5f)
                        drawLine(if(i+0.5f<=head) playedColor else Color(0xffad9885),Offset(x,center.y-h),Offset(x,center.y+h),1.7.dp.toPx(),StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(10.dp));Text("${seconds}″",style=MaterialTheme.typography.labelMedium,color=ChatRoleText)
            }
            DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text("查看原文")},onClick={menu=false;transcript=true}); if(manage!=null) DropdownMenuItem(text={Text("消息操作")},onClick={menu=false;manage()})}
        }
        if(transcript) AlertDialog(onDismissRequest={transcript=false},text={Text(original)},confirmButton={TextButton({transcript=false}){Text("关闭")}})
    }

@Composable private fun VoiceFallback(text:String,manage:(()->Unit)?) {
    Text(text,Modifier.clip(RoundedCornerShape(16.dp)).background(ChatRoleBubble)
        .combinedClickable(onClick={},onLongClick=manage).padding(UiSpace.bubble),color=ChatRoleText)
}