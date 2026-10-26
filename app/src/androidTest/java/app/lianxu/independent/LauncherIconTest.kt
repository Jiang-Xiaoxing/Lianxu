package app.lianxu.independent

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File

@android.annotation.TargetApi(33)
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class LauncherIconTest {
    @Test fun finalPackageAdaptiveLegacyAndMonochrome(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        assertEquals("0.14-memory-counts-test",info.versionName)
        assertEquals("恋叙",context.applicationInfo.loadLabel(context.packageManager).toString())
        val icon=context.applicationInfo.loadIcon(context.packageManager)
        assertTrue(icon is AdaptiveIconDrawable);icon as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
        val image=Bitmap.createBitmap(768,288,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);canvas.drawColor(Color.WHITE)
        val labels=listOf("Circle mask","Rounded square","Monochrome")
        for(i in 0..2){
            canvas.save();canvas.translate(i*256f,0f)
            val path=Path().apply{if(i==0)addCircle(128f,128f,112f,Path.Direction.CW) else addRoundRect(16f,16f,240f,240f,56f,56f,Path.Direction.CW)}
            canvas.clipPath(path)
            if(i==2){canvas.drawColor(Color.rgb(222,242,224));val mono=icon.monochrome!!.constantState!!.newDrawable().mutate();mono.setTint(Color.rgb(31,90,49));mono.setBounds(-40,-40,296,296);mono.draw(canvas)}
            else{icon.background.setBounds(-40,-40,296,296);icon.background.draw(canvas);icon.foreground.setBounds(-40,-40,296,296);icon.foreground.draw(canvas)}
            canvas.restore();canvas.drawText(labels[i],i*256f+24,276f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=20f})
        }
        val dir=File(context.getExternalFilesDir(null),"evidence-silk-icon/launcher").apply{mkdirs()}
        File(dir,"launcher-mask-checks.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
    }
}
