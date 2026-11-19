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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** 语音条：16 根圆头短柱，高矮由 sin 包络叠加以消息路径为种子的固定伪随机决定。 */
private const val VoiceBarCount = 16
private val VoiceBarWidth = 3.dp
private val VoiceBarGap = 12.dp
private val VoiceBarMin = 4.dp
private val VoiceBarMax = 18.dp
private val VoiceWaveWidth = 184.dp
private val VoiceBubbleHeight = 48.dp
private val VoicePlayed = Color(0xFFA07050)
private val VoiceUnplayed = Color(0xFFDACEC2)
private val VoiceHead = Color(0xFFC0885F)
private val VoiceDurationText = Color(0xFF968C7D)

/** 同一条语音每次画出来必须一样，所以用整数混淆而不是 Random。 */
private fun voiceBarNoise(seed:Int,index:Int):Float {
    var x=seed*1664525+index*1013904223+12345
    x=x xor (x ushr 15)
    x*=2246822519.toInt()
    x=x xor (x ushr 13)
    return ((x ushr 8) and 0xFFFF).toFloat()/65535f
}

/** 返回 0..1 的高低因子：两端矮、中间高，再叠一层固定噪声让每条语音形状不同。 */
private fun voiceBarFactors(seed:Int):FloatArray {
    val out=FloatArray(VoiceBarCount)
    for(i in 0 until VoiceBarCount){
        val envelope=sin(Math.PI*(i+0.5)/VoiceBarCount).toFloat()
        out[i]=(envelope*(0.5f+0.5f*voiceBarNoise(seed,i))).coerceIn(0f,1f)
    }
    return out
}

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
        val barSeed=remember(file.absolutePath){file.absolutePath.hashCode()}
        val barFactors=remember(barSeed){voiceBarFactors(barSeed)}
        // 只在播放时起伏；暂停或未播放时这一项不参与绘制，柱子完全静止。
        val pulse by rememberInfiniteTransition(label="voice-bars").animateFloat(
            0f,1f,infiniteRepeatable(tween(760,easing=LinearEasing),RepeatMode.Reverse),label="voice-bars-pulse")
        Box {
            Row(Modifier.widthIn(max=244.dp).height(VoiceBubbleHeight).clip(RoundedCornerShape(16.dp)).background(ChatRoleBubble)
                .combinedClickable(role=Role.Button,onClickLabel=if(playing)"暂停" else "播放",onLongClickLabel="查看原文",
                    onClick={toggle()},onLongClick={menu=true}).semantics {
                    progressBarRangeInfo=ProgressBarRangeInfo(progress,0f..1f)
                    contentDescription="语音，${seconds}秒"
                    stateDescription=if(playing)"正在播放" else if(position>0)"已暂停" else "待播放"
                }.testTag("chat-voice-bubble").padding(start=16.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Canvas(Modifier.width(VoiceWaveWidth).height(VoiceBubbleHeight)) {
                    val barW=VoiceBarWidth.toPx()
                    val step=VoiceBarGap.toPx()
                    val minH=VoiceBarMin.toPx()
                    val maxH=VoiceBarMax.toPx()
                    val head=progress*VoiceBarCount
                    barFactors.forEachIndexed { i,factor->
                        val base=minH+(maxH-minH)*factor
                        val near=(1f-abs(i+0.5f-head)/2.5f).coerceAtLeast(0f)
                        val lift=if(playing && !reduced) near*base*0.15f*pulse else 0f
                        val h=(base+lift).coerceAtMost(maxH*1.15f)
                        val x=barW/2f+i*step
                        drawRoundRect(
                            color=if(i+0.5f<=head) VoicePlayed else VoiceUnplayed,
                            topLeft=Offset(x-barW/2f,center.y-h/2f),
                            size=Size(barW,h),
                            cornerRadius=CornerRadius(barW/2f)
                        )
                    }
                    if(playing || position>0){
                        val hx=barW/2f+progress*(VoiceBarCount-1)*step
                        drawCircle(VoiceHead,radius=2.dp.toPx(),center=Offset(hx,center.y))
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text("${seconds}″",style=MaterialTheme.typography.labelMedium.copy(fontSize=14.sp),color=VoiceDurationText)
            }
            DropdownMenu(menu,{menu=false}){DropdownMenuItem(text={Text("查看原文")},onClick={menu=false;transcript=true}); if(manage!=null) DropdownMenuItem(text={Text("消息操作")},onClick={menu=false;manage()})}
        }
        if(transcript) AlertDialog(onDismissRequest={transcript=false},text={Text(original)},confirmButton={TextButton({transcript=false}){Text("关闭")}})
    }

@Composable private fun VoiceFallback(text:String,manage:(()->Unit)?) {
    Text(text,Modifier.clip(RoundedCornerShape(16.dp)).background(ChatRoleBubble)
        .combinedClickable(onClick={},onLongClick=manage).padding(UiSpace.bubble),color=ChatRoleText)
}