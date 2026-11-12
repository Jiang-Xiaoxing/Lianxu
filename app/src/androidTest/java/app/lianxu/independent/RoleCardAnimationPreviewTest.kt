package app.lianxu.independent

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import java.io.File
import kotlin.math.*

/** Design candidate only. No production entry point, model call, or launcher resource. */
class RoleCardAnimationPreviewTest {
 @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
 @Test fun recordIndependentCandidate(){
  var previewPhase by mutableFloatStateOf(0f)
  ui.setContent { LianxuTheme { CardWaitingCandidate(previewPhase) } }
  ui.waitForIdle()
  ui.mainClock.autoAdvance=true
  repeat(360) { i ->
   ui.runOnIdle { previewPhase=(i%144)/144f }; ui.waitForIdle()
   android.os.SystemClock.sleep(33)
   if(i==90){
    val ins=InstrumentationRegistry.getInstrumentation()
    val dir=File(ins.targetContext.getExternalFilesDir(null),"card-animation-preview").apply{mkdirs()}
    File(dir,"candidate.png").outputStream().use{ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)}
   }
  }
 }
}

@Composable private fun CardWaitingCandidate(phase:Float){
 val ink=Muted
 Box(Modifier.fillMaxSize().background(Chrome)){
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
   Header("寻找角色",{})
   Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.offset(y=(-50).dp)){
     Canvas(Modifier.size(240.dp,190.dp)){
      val u=size.width/240f
      fun card(index:Int){
       val t=(phase+index*.5f)%1f
       val wave=(.5f-.5f*cos(t*2f*PI.toFloat()))
       val alpha=.18f+.82f*wave
       val x=(if(index==0)50f else 92f)+sin(t*2f*PI.toFloat())*5f
       val y=33f+(1f-wave)*17f
       val c=ink.copy(alpha=alpha*.75f)
       translate(x*u,y*u){
        rotate(if(index==0)-5f else 5f,pivot=Offset(48*u,62*u)){
         drawRoundRect(Color.White.copy(alpha=alpha),size=Size(96*u,124*u),cornerRadius=CornerRadius(15*u))
         drawRoundRect(c.copy(alpha=alpha*.42f),size=Size(96*u,124*u),cornerRadius=CornerRadius(15*u),style=Stroke(1.2f*u))
         drawCircle(c,10*u,Offset(48*u,35*u),style=Stroke(1.5f*u))
         drawArc(c,195f,150f,false,Offset(29*u,46*u),Size(38*u,26*u),style=Stroke(1.5f*u,cap=StrokeCap.Round))
         drawLine(c.copy(alpha=alpha*.48f),Offset(24*u,88*u),Offset(72*u,88*u),2*u,StrokeCap.Round)
         drawLine(c.copy(alpha=alpha*.27f),Offset(32*u,99*u),Offset(64*u,99*u),2*u,StrokeCap.Round)
        }
       }
      }
      if(phase<.5f){card(1);card(0)}else{card(0);card(1)}
     }
     Text("正在构思角色…",style=MaterialTheme.typography.bodyMedium,color=Muted)
    }
   }
   Text("动效候选预览 · 未连接模型",style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=24.dp))
  }
 }
}
