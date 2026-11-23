package app.lianxu.independent

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** 12 seconds of mono, 16-bit PCM generated in the instrumented app's test cache. */
internal fun testVoiceWav(cacheDir: File, name: String): File {
    val rate = 8000
    val seconds = 12
    val oneSecond = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(rate) { i -> oneSecond.putShort((2000 * sin(2 * PI * 440 * i / rate)).toInt().toShort()) }
    val dataSize = oneSecond.capacity() * seconds
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + dataSize)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(rate)
        putInt(rate * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize)
    }
    return File(cacheDir, "$name.wav").apply {
        outputStream().buffered().use { out ->
            out.write(header.array())
            repeat(seconds) { out.write(oneSecond.array()) }
        }
    }
}

internal fun saveVoiceTestScreenshot(name: String) {
    val inst = InstrumentationRegistry.getInstrumentation()
    val screenshot = inst.uiAutomation.takeScreenshot()
    try {
        File(inst.targetContext.cacheDir, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        screenshot.recycle()
    }
}
