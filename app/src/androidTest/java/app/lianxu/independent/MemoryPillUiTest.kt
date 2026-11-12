package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** 胶囊本身用 Compose 断言核对：文案、居中、2 倍字号不溢出不遮挡，并留下实际渲染截图。 */
class MemoryPillUiTest {
    @get:Rule val ui=createComposeRule()

    private fun render(count:Int,scale:Float,dark:Boolean=false,width:Int?=null){
        ui.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
                LianxuTheme(dark){Box(Modifier.fillMaxSize().background(ChatBackground)){
                    if(width==null){
                        Column(Modifier.fillMaxWidth().padding(top=40.dp)){MemorySummaryPill(MemoryPill("b1","a",1L,count,1L))}
                    }else{
                        Box(Modifier.width(width.dp)){Column(Modifier.fillMaxWidth().padding(top=40.dp)){MemorySummaryPill(MemoryPill("b1","a",1L,count,1L))}}
                    }
                }}
            }
        }
    }
    private fun shot(name:String){
        ui.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"evidence-memory-pill").apply{mkdirs()}
        val bitmap=i.uiAutomation.takeScreenshot()
        File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    /** 语义里带的是实际排版结果：能直接看出有没有被横向/纵向裁掉。 */
    private fun layoutOf(text:String):TextLayoutResult{
        val results=mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }
    private val density get()=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    /** 逐行宽度和文字自己的排版框比，不拿含图标与间距的整块内容区放宽标准。 */
    private fun assertEveryLineFits(layout:TextLayoutResult,text:String){
        val availablePx=layout.size.width.toFloat()
        for(i in 0 until layout.lineCount){
            val width=layout.getLineRight(i)-layout.getLineLeft(i)
            assertTrue("「$text」第 ${i+1} 行宽度 $width 超出文字排版框 $availablePx",width<=availablePx+1f)
        }
    }
    /** 文字排版框还必须真的给得下：内容区减掉 16dp 图标与 6dp 间距。 */
    private fun assertTextFitsInPillContent(layout:TextLayoutResult,contentDp:Float,text:String){
        val textAreaPx=(contentDp-16f-6f)*density
        assertTrue("「$text」文字排版框 ${layout.size.width} 大于可用文字区 $textAreaPx",layout.size.width<=textAreaPx+1f)
    }
    private fun assertCentered(text:String,batch:String){
        val pill=ui.onNodeWithTag("memory-pill-box-$batch").getUnclippedBoundsInRoot()
        val label=ui.onNodeWithText(text).getUnclippedBoundsInRoot()
        val root=ui.onRoot().getUnclippedBoundsInRoot()
        val contentDp=(pill.right-pill.left).value
        assertTrue("胶囊整体（内容 + 左右各 12dp 留白）不能超过 280dp：$pill",contentDp+24f<=280.5f)
        assertTrue("文字必须完整落在胶囊里（四边都要查）：文字 $label / 胶囊 $pill",
            label.left>=pill.left&&label.right<=pill.right&&label.top>=pill.top&&label.bottom<=pill.bottom)
        val leftGap=pill.left.value-root.left.value
        val rightGap=root.right.value-pill.right.value
        assertTrue("左右留白必须对称：$leftGap / $rightGap",abs(leftGap-rightGap)<=1.5f)
        val layout=layoutOf(text)
        // didOverflowWidth 对单行中文字也会为 true（Compose 的固有宽度判定），不能当裁切证据；这里逐行核对宽度与纵向裁切。
        assertFalse("文字不能竖向被裁：$text",layout.didOverflowHeight)
        assertTrue("至少要排出一行：$text",layout.lineCount>=1)
        assertTrue("排版宽度不能超过胶囊可用宽度：${layout.size.width}",layout.size.width<=contentDp*density+1f)
        assertTextFitsInPillContent(layout,contentDp,text)
        assertEveryLineFits(layout,text)
    }

    @Test fun twelveMemoriesAtDoubleFontStayCenteredAndInsideThePill(){
        render(12,2f)
        ui.onNodeWithText("已整理 12 条记忆").assertIsDisplayed()
        assertCentered("已整理 12 条记忆","b1")
        shot("pill-light-2.0x-12.png")
    }

    @Test fun twelveMemoriesAtDoubleFontInDarkThemeAlsoFit(){
        render(12,2f,true)
        ui.onNodeWithText("已整理 12 条记忆").assertIsDisplayed()
        assertCentered("已整理 12 条记忆","b1")
        shot("pill-dark-2.0x-12.png")
    }

    @Test fun aSucceededBatchWithoutChangesUsesTheShortWordingNotZero(){
        render(0,2f)
        ui.onNodeWithText("记忆已整理").assertIsDisplayed()
        ui.onNodeWithText("已整理 0 条记忆").assertDoesNotExist()
        assertCentered("记忆已整理","b1")
        shot("pill-light-2.0x-zero.png")
    }

    @Test fun oneMemoryAtDefaultScaleReadsNaturally(){
        render(1,1f)
        ui.onNodeWithText("已整理 1 条记忆").assertIsDisplayed()
        assertCentered("已整理 1 条记忆","b1")
    }

    @Test fun aNarrowContainerWrapsTheLongWordingInsteadOfClippingIt(){
        render(12,2f,width=200)
        val text="已整理 12 条记忆"
        ui.onNodeWithText(text).assertIsDisplayed()
        val layout=layoutOf(text)
        assertTrue("窄容器里应换行而不是裁掉：lineCount=${layout.lineCount}",layout.lineCount>=2)
        assertFalse(layout.didOverflowHeight)
        val pill=ui.onNodeWithTag("memory-pill-box-b1").getUnclippedBoundsInRoot()
        val label=ui.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue("换行后文字四边都必须在胶囊内：文字 $label / 胶囊 $pill",
            label.left>=pill.left&&label.right<=pill.right&&label.top>=pill.top&&label.bottom<=pill.bottom)
        assertEveryLineFits(layout,text)
        assertTextFitsInPillContent(layout,(pill.right-pill.left).value,text)
    }
}
