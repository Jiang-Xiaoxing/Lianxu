package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Exports only test-created PNGs through the app's own scoped-storage access. */
class AcceptanceEvidenceExportTest {
    @Test fun copySyntheticScreenshotsToDebuggableInternalStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = requireNotNull(context.getExternalFilesDir(null))
        val destination = File(context.filesDir, "acceptance-screenshots").apply { mkdirs() }
        var copied = 0
        source.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("evidence-") }.forEach { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "png" }.forEach { image ->
                val target = File(destination, image.relativeTo(source).path)
                target.parentFile?.mkdirs()
                image.copyTo(target, overwrite = true)
                copied++
            }
        }
        assertTrue("No synthetic screenshots were produced", copied > 0)
    }
}
