package app.lianxu.independent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

internal val LocalChatCapsuleMotion=staticCompositionLocalOf{emptyMap<String,ChatCapsuleMotionEvent>()}
internal val LocalChatCapsuleOwner=staticCompositionLocalOf{""}
internal fun capsuleMotionNow()=System.nanoTime()/1_000_000

/** No delay, callback or business work. Animatable uses Compose's system duration scale. */
@Composable internal fun chatCapsuleAnimation(id:String):Modifier {
    val event=LocalChatCapsuleMotion.current[id]
    val progress=remember(id,event){Animatable(event?.progress(capsuleMotionNow())?:1f)}
    LaunchedEffect(id,event){
        val now=capsuleMotionNow()
        val remaining=event?.remaining(now)?:0
        progress.snapTo(event?.progress(now)?:1f)
        // remaining is real scaled time; Compose applies that scale to the tween once.
        if(remaining>0&&event!=null)progress.animateTo(1f,tween(kotlin.math.ceil(remaining/event.scale.toDouble()).toInt(),easing=LinearEasing))else progress.snapTo(1f)
    }
    return Modifier.graphicsLayer{alpha=.72f+.28f*progress.value}
}
