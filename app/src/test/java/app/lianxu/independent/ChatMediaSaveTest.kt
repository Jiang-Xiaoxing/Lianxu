package app.lianxu.independent

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ChatMediaSaveTest {
    @Test fun namesAndFoldersMatchTheOriginalMimeType() {
        for((mime,extension) in mapOf("image/jpeg" to "jpg","image/png" to "png","video/mp4" to "mp4","video/quicktime" to "mov")) {
            assertEquals("Lianxu-fixture.$extension",ChatMediaSave.mediaName(mime,"fixture"))
            assertEquals(if(mime.startsWith("video/")) "Movies/Lianxu" else "Pictures/Lianxu",ChatMediaSave.folder(mime))
        }
    }
    @Test fun copiesOriginalBytesAcrossMultipleBuffers() {
        val original=ByteArray(150_001) { (it%203).toByte() }
        val saved=ByteArrayOutputStream()
        assertEquals(original.size.toLong(),ChatMediaSave.copy(original.inputStream(),saved))
        assertArrayEquals(original,saved.toByteArray())
    }
}
