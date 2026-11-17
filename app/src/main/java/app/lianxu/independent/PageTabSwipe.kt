package app.lianxu.independent

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Switch the existing panel on a deliberate horizontal swipe; keep its state and layout intact. */
internal fun Modifier.swipePageTabs(selected:Int,count:Int,enabled:Boolean=true,onSelect:(Int)->Unit):Modifier=composed{
    val density=LocalDensity.current;val direction=LocalLayoutDirection.current
    val system=WindowInsets.systemGestures
    val edge=with(density){24.dp.toPx()};val distance=with(density){56.dp.toPx()}
    val left=maxOf(edge,system.getLeft(density,direction).toFloat())
    val right=maxOf(edge,system.getRight(density,direction).toFloat())
    val select by rememberUpdatedState(onSelect)
    pointerInput(selected,count,enabled,left,right,distance){
        if(!enabled||count<2)return@pointerInput
        awaitEachGesture{
            val down=awaitFirstDown(requireUnconsumed=false)
            // Never claim an edge-start gesture; Android retains its Back gesture area.
            if(down.position.x<=left||down.position.x>=size.width-right)return@awaitEachGesture
            var delta=Offset.Zero;var horizontal=false
            val slop=viewConfiguration.touchSlop
            val threshold=maxOf(slop*2,minOf(distance,size.width*.2f))
            while(true){
                val event=awaitPointerEvent()
                if(event.changes.count{it.pressed}>1)break
                val change=event.changes.firstOrNull{it.id==down.id}?:break
                if(change.isConsumed)break
                delta+=change.positionChange()
                // Let vertical scroll win, including a scroll that started on a clickable row.
                if(!horizontal&&abs(delta.y)>slop&&abs(delta.y)>=abs(delta.x))break
                if(!horizontal&&abs(delta.x)>slop&&abs(delta.x)>abs(delta.y))horizontal=true
                if(horizontal)change.consume()
                if(!change.pressed){
                    if(horizontal&&abs(delta.x)>=threshold){
                        val next=(selected+if(delta.x<0)1 else -1).coerceIn(0,count-1)
                        if(next!=selected)select(next)
                    }
                    break
                }
            }
        }
    }
}
