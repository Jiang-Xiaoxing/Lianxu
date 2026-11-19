package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*

/** Stop with the pointer down, then reverse the same gesture and release on the first page. */
internal fun ComposeTestRule.capturePagerDrag(group:String){
    val root=onRoot()
    val first=onNodeWithTag("tab-panel-0",useUnmergedTree=true)
    val bounds=first.fetchSemanticsNode().boundsInRoot
    val origin=root.fetchSemanticsNode().boundsInRoot.topLeft
    val start=Offset(bounds.left+bounds.width*.80f,bounds.top+bounds.height*.65f)-origin
    root.performTouchInput{
        down(start)
        for(i in 1..8)moveTo(start-Offset(bounds.width*.055f*i,0f),32)
    }
    waitForIdle()
    val outgoing=first.fetchSemanticsNode().boundsInRoot
    val incoming=onNodeWithTag("tab-panel-1",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
    assertTrue("out=$outgoing in=$incoming initial=$bounds",outgoing.width in bounds.width*.25f..bounds.width*.85f && incoming.width in bounds.width*.15f..bounds.width*.75f)
    assertEquals(outgoing.right,incoming.left,2f)
    val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"pager-mid").apply{mkdirs()}
    val bitmap=root.captureToImage().asAndroidBitmap()
    File(dir,"$group.png").outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    File(dir,"$group.txt").writeText("pointer=down\nviewport=$bounds\noutgoing=$outgoing\nincoming=$incoming")
    root.performTouchInput{
        for(i in 7 downTo 0)moveTo(start-Offset(bounds.width*.055f*i,0f),32)
        advanceEventTime(180);up()
    }
    waitForIdle()
    val settled=first.fetchSemanticsNode().boundsInRoot
    assertEquals(bounds.left,settled.left,0f)
    assertEquals(bounds.right,settled.right,0f)
    val neighbours=onAllNodesWithTag("tab-panel-1",useUnmergedTree=true).fetchSemanticsNodes()
    for(neighbour in neighbours){
        assertFalse("停稳后邻页露边",neighbour.boundsInRoot.overlaps(bounds))
        assertTrue("屏外页仍有可访问的子控件",neighbour.children.isEmpty())
    }
    File(dir,"$group.txt").appendText("\nsettled=$settled\noffscreenControls=0")
}
