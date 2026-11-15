package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Approved native flat role rows; synthetic pictures only, no export or model calls. */
class BackupRoleDialogUiTest {
    @get:Rule val ui=createComposeRule()
    private fun shot(name:String){ui.mainClock.advanceTimeBy(600);ui.waitForIdle();android.os.SystemClock.sleep(350);val i=InstrumentationRegistry.getInstrumentation();val b=i.uiAutomation.takeScreenshot();val dir=File(i.targetContext.getExternalFilesDir(null),"evidence-role-picker").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun picture(color:Int,size:Int=48):ByteArray{val b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply{eraseColor(color)};return try{ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.JPEG,90,it)}.toByteArray()}finally{b.recycle()}}
    @Test fun twoRowsShowPhotosAndOnlyOneSelectedRole(){
        var selected by mutableStateOf("");var exported=""
        val friends=listOf(Friend("picker-a","林间","合成"),Friend("picker-b","时安","合成"))
        val photos=mapOf("picker-a" to picture(Color.rgb(100,130,110)),"picker-b" to picture(Color.rgb(160,120,90)))
        ui.setContent{LianxuTheme(false){CompositionLocalProvider(LocalRoleAvatars provides photos){Box(Modifier.fillMaxSize().background(Chrome)){BackupRoleDialog(friends,selected,{selected=it},{},{exported=selected})}}}}
        ui.onNodeWithTag("archive-export").assertIsNotEnabled()
        ui.onNodeWithTag("archive-export-picker-a").assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithTag("archive-export-picker-a").assertIsSelected()
        ui.onNodeWithTag("archive-export-picker-b").assertIsNotSelected().performClick()
        ui.onNodeWithTag("archive-export-picker-a").assertIsNotSelected()
        ui.onNodeWithTag("archive-export-picker-b").assertIsSelected()
        shot("flat-rows-light")
        ui.onNodeWithTag("archive-export").performClick();assertEquals("picker-b",exported)
    }
    @Test fun emptyListCannotExportAndCancelCloses(){
        var open by mutableStateOf(true);var exports=0
        ui.setContent{LianxuTheme(false){if(open)BackupRoleDialog(emptyList(),"",{},{open=false},{exports++})}}
        ui.onNodeWithText("还没有可备份的角色。").assertIsDisplayed()
        ui.onNodeWithTag("archive-export").assertIsNotEnabled();shot("empty-light")
        ui.onNodeWithText("取消").performClick();ui.onNodeWithTag("archive-export").assertDoesNotExist();assertEquals(0,exports)
    }
    @Test fun longNamesAndLongListRemainSelectableAtLargeFontInDark(){
        var selected by mutableStateOf("");val friends=(0..99).map{Friend("picker-$it","合成角色 $it · 一段用于检查大字换行的长名字","合成")}
        val photo=picture(Color.rgb(100,130,110),512);val photos=friends.associate{it.id to photo}
        ui.setContent{val d=LocalDensity.current;CompositionLocalProvider(LocalRoleAvatars provides photos,LocalDensity provides Density(d.density,1.6f)){LianxuTheme(true){Box(Modifier.fillMaxSize().background(Chrome)){BackupRoleDialog(friends,selected,{selected=it},{},{})}}}}
        val rows=SemanticsMatcher("composed role rows"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.startsWith("archive-export-picker-")}
        assertTrue("不能一次创建100张照片行",ui.onAllNodes(rows).fetchSemanticsNodes().size<20)
        ui.onNodeWithTag("backup-role-list").performScrollToNode(hasTestTag("archive-export-picker-99"))
        ui.onNodeWithTag("archive-export-picker-99").assertIsDisplayed().performClick()
        ui.onNodeWithTag("archive-export-picker-99").assertIsSelected().assertHeightIsAtLeast(48.dp)
        assertTrue(ui.onAllNodes(rows).fetchSemanticsNodes().size<20)
        ui.onNodeWithTag("archive-export").assertIsDisplayed().assertIsEnabled();ui.onNodeWithText("取消").assertIsDisplayed()
        shot("long-list-dark-large")
    }
}
