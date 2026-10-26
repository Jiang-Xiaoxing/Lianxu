package app.lianxu.independent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlin.math.*

private val IconStroke=Stroke(1.65f,cap=StrokeCap.Round,join=StrokeJoin.Round)

/** Original regular 24-unit paths: one stroke, no texture or filled silhouettes. */
@Composable internal fun NavigationIcon(index:Int,selected:Boolean) {
    val color=if(selected)GreenText else Muted
    Canvas(Modifier.size(25.dp)) {
        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {
            when(index) {
                0->{
                    val body=Path().apply {
                        moveTo(8f,4f);lineTo(16f,4f);cubicTo(18.76f,4f,21f,6.24f,21f,9f)
                        lineTo(21f,13f);cubicTo(21f,15.76f,18.76f,18f,16f,18f);lineTo(10f,18f)
                        lineTo(6.1f,20.3f);quadraticTo(5.5f,20.65f,5.75f,19.95f);lineTo(6.5f,17.75f)
                        cubicTo(4.4f,17.1f,3f,15.2f,3f,13f);lineTo(3f,9f)
                        cubicTo(3f,6.24f,5.24f,4f,8f,4f);close()
                    }
                    drawPath(body,color,style=IconStroke)
                    for(x in listOf(8f,12f,16f))drawCircle(color,.7f,Offset(x,11f))
                }
                1->{
                    val book=Path().apply {addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(4.5f,3.5f,20.5f,20.5f),3f,3f))}
                    drawPath(book,color,style=IconStroke)
                    for(y in listOf(7.5f,12f,16.5f))drawLine(color,Offset(2.5f,y),Offset(6f,y),IconStroke.width,cap=StrokeCap.Round)
                    drawCircle(color,2.1f,Offset(12.6f,9f),style=IconStroke)
                    drawPath(Path().apply {moveTo(9.2f,16.2f);cubicTo(9.7f,12.7f,15.5f,12.7f,16f,16.2f)},color,style=IconStroke)
                }
                2->{
                    val points=(0 until 24).map {i->val angle=-PI/2+(i-.5)*PI/12;val radius=if(i%4<2)9.4 else 7.5;Offset(12f+(cos(angle)*radius).toFloat(),12f+(sin(angle)*radius).toFloat())}
                    val gear=Path().apply {
                        val start=(points.last()+points.first())/2f;moveTo(start.x,start.y)
                        points.forEachIndexed {i,p->val end=(p+points[(i+1)%points.size])/2f;quadraticTo(p.x,p.y,end.x,end.y)};close()
                    }
                    drawPath(gear,color,style=IconStroke);drawCircle(color,3f,Offset(12f,12f),style=IconStroke)
                }
                else->{
                    drawCircle(color,3.5f,Offset(12f,7f),style=IconStroke)
                    drawPath(Path().apply {moveTo(3.5f,20.5f);cubicTo(4.3f,15.3f,7.7f,13f,12f,13f);cubicTo(16.3f,13f,19.7f,15.3f,20.5f,20.5f)},color,style=IconStroke)
                }
            }
        }
    }
}

@Composable internal fun ControlIcon(kind:String,color:Color=Muted) {
    val iconColor=color
    Canvas(Modifier.size(24.dp)) {
        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {
            when(kind) {
                "plug"->{
                    drawPath(Path().apply {moveTo(7f,7f);lineTo(17f,7f);lineTo(17f,12f);cubicTo(17f,14.76f,14.76f,17f,12f,17f);cubicTo(9.24f,17f,7f,14.76f,7f,12f);close()},iconColor,style=IconStroke)
                    for(x in listOf(9f,15f))drawLine(iconColor,Offset(x,3f),Offset(x,7f),IconStroke.width,cap=StrokeCap.Round)
                    drawPath(Path().apply {moveTo(12f,17f);lineTo(12f,18.5f);cubicTo(12f,22f,6f,22f,6f,18.5f)},iconColor,style=IconStroke)
                }
                "search"->{drawCircle(iconColor,6.5f,Offset(10f,10f),style=IconStroke);drawLine(iconColor,Offset(14.6f,14.6f),Offset(20.5f,20.5f),IconStroke.width,cap=StrokeCap.Round)}
                "model"->{
                    drawPath(Path().apply {addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(6f,6f,18f,18f),2.5f,2.5f))},iconColor,style=IconStroke)
                    for(p in listOf(9f,15f)) {
                        drawLine(iconColor,Offset(p,3f),Offset(p,6f),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(p,18f),Offset(p,21f),IconStroke.width,cap=StrokeCap.Round)
                        drawLine(iconColor,Offset(3f,p),Offset(6f,p),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(18f,p),Offset(21f,p),IconStroke.width,cap=StrokeCap.Round)
                    };drawCircle(iconColor,2.5f,Offset(12f,12f),style=IconStroke)
                }
                "list"->{for(y in listOf(6f,12f,18f)){drawCircle(iconColor,.8f,Offset(4f,y));drawLine(iconColor,Offset(8f,y),Offset(20f,y),IconStroke.width,cap=StrokeCap.Round)}}
                "storage"->{
                    drawPath(Path().apply {addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(5f,8f,19f,21f),2f,2f));addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(3.5f,3.5f,20.5f,8f),1.5f,1.5f))},iconColor,style=IconStroke)
                    drawLine(iconColor,Offset(9.5f,12f),Offset(14.5f,12f),IconStroke.width,cap=StrokeCap.Round)
                }
                "info"->{drawCircle(iconColor,9f,Offset(12f,12f),style=IconStroke);drawCircle(iconColor,.8f,Offset(12f,7.5f));drawLine(iconColor,Offset(12f,11f),Offset(12f,16.5f),IconStroke.width,cap=StrokeCap.Round)}
                "attach"->{drawPath(Path().apply{moveTo(16f,6f);cubicTo(13f,3f,10f,5f,8f,7f);lineTo(5f,10f);cubicTo(1f,14f,4f,20f,8f,20f);cubicTo(10f,20f,12f,18f,13f,17f);lineTo(19f,11f);cubicTo(21f,8f,18f,4f,15f,7f);lineTo(8f,14f);cubicTo(6f,16f,8f,18f,10f,16f);lineTo(15f,11f)},iconColor,style=IconStroke)}
                "send"->{drawPath(Path().apply{moveTo(4.5f,4.5f);lineTo(21f,12f);lineTo(4.5f,19.5f);lineTo(7f,12f);close();moveTo(7f,12f);lineTo(20f,12f)},iconColor,style=IconStroke)}
                "chevron"->{drawPath(Path().apply{moveTo(9f,6f);lineTo(15f,12f);lineTo(9f,18f)},iconColor,style=IconStroke)}
                "up"->{drawPath(Path().apply{moveTo(6f,15f);lineTo(12f,9f);lineTo(18f,15f)},iconColor,style=IconStroke)}
                "back"->drawPath(Path().apply {moveTo(14.5f,5f);lineTo(7.5f,12f);lineTo(14.5f,19f)},iconColor,style=IconStroke)
                else->{drawCircle(iconColor,9f,Offset(12f,12f),style=IconStroke);drawLine(iconColor,Offset(7f,12f),Offset(17f,12f),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(12f,7f),Offset(12f,17f),IconStroke.width,cap=StrokeCap.Round)}
            }
        }
    }
}
