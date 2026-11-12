package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the installed APK only; no database, account, network, or phone data. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class LauncherIconAcceptanceTest {
    @Test fun installedIconKeepsEveryMarkInsideAdaptiveSafeArea() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(BuildConfig.VERSION_NAME, info.versionName)
        assertEquals(BuildConfig.VERSION_CODE, info.versionCode)
        assertEquals("恋叙", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val icon = context.applicationInfo.loadIcon(context.packageManager)
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        val foreground = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.foreground.setBounds(0, 0, 432, 432)
        icon.foreground.draw(Canvas(foreground))
        var paintedPixels = 0
        for (y in 0 until 432) for (x in 0 until 432) {
            if (Color.alpha(foreground.getPixel(x, y)) > 16) {
                paintedPixels++
                val dx = x - 215.5
                val dy = y - 215.5
                assertTrue("Artwork exceeds the 66dp safe circle", dx * dx + dy * dy <= 132.0 * 132.0)
            }
        }
        assertTrue("Foreground must contain visible artwork", paintedPixels > 1000)
        val mono = BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_moon_stars_monochrome)
        assertEquals(432, mono.width)
        assertEquals(432, mono.height)
        if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon.monochrome)
        val image = Bitmap.createBitmap(768, 430, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image).apply { drawColor(Color.rgb(235, 232, 226)) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 18f }
        fun drawMask(left: Int, top: Int, size: Int, circle: Boolean) {
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            val shape = Path().apply {
                if (circle) addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
                else addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * .24f, size * .24f, Path.Direction.CW)
            }
            canvas.clipPath(shape)
            val bleed = size / 4
            for (layer in listOf(icon.background, icon.foreground)) {
                layer.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                layer.draw(canvas)
            }
            canvas.restore()
        }
        for (column in 0..1) {
            drawMask(column * 384 + 64, 20, 256, column == 0)
            canvas.drawText(if (column == 0) "APK drawable - circle" else "APK drawable - rounded", column * 384 + 40f, 306f, text)
            for ((index, size) in listOf(24, 36, 48).withIndex()) {
                val left = column * 384 + 56 + index * 100
                drawMask(left, 334, size, column == 0)
                canvas.drawText(size.toString() + "px", left.toFloat(), 408f, text)
            }
        }
        val directory = File(context.filesDir, "evidence-icon-private").apply { mkdirs() }
        File(directory, "apk-drawable-mask-checks.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
