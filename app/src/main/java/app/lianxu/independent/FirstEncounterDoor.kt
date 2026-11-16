package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val FirstEncounterPaper=Color(0xFFFFEED6)
internal val FirstEncounterSaver=listSaver<FirstEncounterState,String>(
    save={listOf(it.phase.name,it.destination?.name.orEmpty())},
    restore={values->runCatching{FirstEncounterState(FirstEncounterPhase.valueOf(values[0]),values[1].takeIf{it.isNotEmpty()}?.let(FirstEncounterDestination::valueOf)).restored()}.getOrNull()}
)

/** Uses the two approved paintings unchanged; only their drawing transforms are animated. */
@Composable internal fun FirstEncounterDoor(
    state:FirstEncounterState,
    onState:(FirstEncounterState)->Unit,
    onChoose:(FirstEncounterDestination)->Unit
){
    val resources=LocalContext.current.resources
    val paintings=remember(resources){
        val options=BitmapFactory.Options().apply{inScaled=false}
        BitmapFactory.decodeResource(resources,R.drawable.first_encounter_closed,options) to
            BitmapFactory.decodeResource(resources,R.drawable.first_encounter_approved,options)
    }
    val scope=rememberCoroutineScope()
    val reduced=(scope.coroutineContext[MotionDurationScale]?.scaleFactor?:1f)<=0f
    val open=remember{Animatable(if(state.phase>=FirstEncounterPhase.ENTERING||reduced)1f else 0f)}
    val push=remember{Animatable(if(state.phase==FirstEncounterPhase.READY||reduced)1f else 0f)}
    val reveal=remember{Animatable(if(state.phase==FirstEncounterPhase.READY||reduced)1f else 0f)}
    val currentOnState by rememberUpdatedState(onState)
    LaunchedEffect(state.phase,reduced){
        if(reduced){open.snapTo(1f);push.snapTo(1f);reveal.snapTo(1f);currentOnState(state.withoutMotion())}
        else when(state.phase){
            FirstEncounterPhase.CLOSED->Unit
            FirstEncounterPhase.OPENING->{open.animateTo(1f,tween(1800,easing=CubicBezierEasing(.3f,0f,.2f,1f)));currentOnState(state.doorOpened())}
            FirstEncounterPhase.ENTERING->{open.snapTo(1f);push.animateTo(1f,tween(1700,easing=CubicBezierEasing(.45f,0f,.2f,1f)));currentOnState(state.entered())}
            FirstEncounterPhase.READY->{open.snapTo(1f);push.snapTo(1f);reveal.animateTo(1f,tween(400))}
        }
    }
    val invitation=if(state.phase==FirstEncounterPhase.CLOSED)Modifier.semantics{contentDescription="轻触，开始相遇"}.clickable(
        role=Role.Button,onClickLabel="轻触，开始相遇"
    ){onState(state.start())}else Modifier
    Box(Modifier.fillMaxSize().testTag("first-encounter").then(invitation)){
        val opening=if(reduced)1f else open.value;val entering=if(reduced)1f else push.value
        Canvas(Modifier.fillMaxSize()){
            drawIntoCanvas{drawDoorPainting(it.nativeCanvas,paintings.first,paintings.second,size.width,size.height,opening,entering)}
        }
        if(state.phase==FirstEncounterPhase.READY){
            val actionsEnabled=state.canChoose&&(reduced||reveal.value>=1f)
            Column(Modifier.align(Alignment.Center).widthIn(max=360.dp).fillMaxWidth(.70f).alpha(if(reduced)1f else reveal.value).testTag("first-encounter-actions"),horizontalAlignment=Alignment.CenterHorizontally){
                Button({onChoose(FirstEncounterDestination.CREATE)},enabled=actionsEnabled,
                    shape=RoundedCornerShape(28.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFF0CD99),contentColor=Color(0xFF835323),disabledContainerColor=Color(0xFFF0CD99),disabledContentColor=Color(0xFF835323)),
                    modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("first-encounter-create")){
                    Text("创建角色",style=MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(20.dp))
                TextButton({onChoose(FirstEncounterDestination.RESTORE)},enabled=actionsEnabled,
                    colors=ButtonDefaults.textButtonColors(contentColor=Color(0xFF956B3C),disabledContentColor=Color(0xFF956B3C)),
                    modifier=Modifier.fillMaxWidth().heightIn(min=UiSpace.action).testTag("first-encounter-restore")){
                    Text("恢复已有数据",style=MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun drawDoorPainting(canvas:android.graphics.Canvas,closed:Bitmap,approved:Bitmap,width:Float,height:Float,open:Float,push:Float){
    val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    // A blank section of the approved warm-light panel, excluding its painted button labels.
    canvas.drawBitmap(approved,Rect(540,40,1010,630),RectF(0f,0f,width,height),paint)
    if(push>=1f)return
    // Fade the advancing painting into this same background before the camera settles.
    val blend=((push-.5f)*2f).coerceIn(0f,1f)
    canvas.saveLayerAlpha(0f,0f,width,height,((1f-blend)*255).toInt())
    val fit=minOf(width/closed.width,height/closed.height)
    canvas.save()
    canvas.translate((width-closed.width*fit)/2f,(height-closed.height*fit)/2f)
    canvas.scale(fit,fit)
    // Advance into the opening, centring the view on light instead of revealing any room.
    canvas.translate((closed.width/2f-590f)*push,(closed.height/2f-810f)*push)
    canvas.scale(1f+5.5f*push,1f+5.5f*push,590f,810f)
    // Keep the complete original paper and its painted invitation; semantics supply accessibility.
    // Extend the painting's own edge paper into wide windows, without a pasted-image seam.
    paint.shader=BitmapShader(closed,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
    canvas.drawPaint(paint)
    paint.shader=null
    if(open>0f){
    canvas.drawBitmap(approved,Rect(540,40,1010,630),RectF(207f,283f,718f,1310f),paint)
    val freeX=718f-300f*open
    val top=284f-150f*open
    val bottom=1307f+150f*open
    // The thick free edge is sampled directly from the approved open door, not the frame.
    val thickness=1f+30f*open
    drawProjected(canvas,approved,Rect(225,235,244,1195),floatArrayOf(freeX,top,freeX+thickness,top+6f*open,freeX+thickness,bottom-6f*open,freeX,bottom),paint)
    drawProjected(canvas,closed,Rect(208,284,718,1307),floatArrayOf(208f,284f,freeX,top,freeX,bottom,208f,1307f),paint)
    }
    canvas.restore()
    canvas.restore()
}

private fun drawProjected(canvas:android.graphics.Canvas,bitmap:Bitmap,source:Rect,points:FloatArray,paint:Paint){
    val w=source.width().toFloat();val h=source.height().toFloat()
    val matrix=Matrix()
    matrix.setPolyToPoly(floatArrayOf(0f,0f,w,0f,w,h,0f,h),0,points,0,4)
    canvas.save();canvas.concat(matrix)
    canvas.drawBitmap(bitmap,source,RectF(0f,0f,w,h),paint)
    canvas.restore()
}
