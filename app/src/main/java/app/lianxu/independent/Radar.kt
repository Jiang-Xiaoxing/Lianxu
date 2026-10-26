package app.lianxu.independent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.*
@Composable internal fun Radar() {
    val accent=Accent;val glow=SelectedBackground
    val transition=rememberInfiniteTransition(label="visible-search")
    val angle by transition.animateFloat(0f,360f,infiniteRepeatable(tween(2600,easing=LinearEasing)),label="scan")
    Canvas(Modifier.fillMaxWidth().height(248.dp).padding(horizontal=30.dp).testTag("radar")) {
        val center=Offset(size.width/2,size.height*.61f); val radius=size.width*.46f;val ry=radius*.42f
        for(layer in 3 downTo 0) {val scale=1f-layer*.2f;drawOval(accent.copy(alpha=.035f),Offset(center.x-radius*scale,center.y-ry*scale+8),Size(radius*2*scale,ry*2*scale));drawOval(accent.copy(alpha=.25f),Offset(center.x-radius*scale,center.y-ry*scale),Size(radius*2*scale,ry*2*scale),style=Stroke(1.dp.toPx()))}
        val path=Path().apply {moveTo(center.x,center.y);for(i in 0..45) {val rad=(angle-i)*PI/180;lineTo(center.x+cos(rad).toFloat()*radius,center.y+sin(rad).toFloat()*ry)};close()}
        drawPath(path,Brush.radialGradient(listOf(accent.copy(alpha=.24f),accent.copy(alpha=.015f)),center,radius))
        drawOval(accent,Offset(center.x-6,center.y-3),Size(12f,6f))
        for(i in 0..2) {val theta=(i*115+30)*PI/180;val foot=Offset(center.x+cos(theta).toFloat()*radius*.76f,center.y+sin(theta).toFloat()*ry*.76f);val point=foot-Offset(0f,65.dp.toPx());drawLine(accent.copy(alpha=.35f),foot,point,1f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(4f,5f)));drawCircle(Brush.radialGradient(listOf(glow,accent),point-Offset(3f,3f),12.dp.toPx()),7.dp.toPx(),point)}
    }
}
