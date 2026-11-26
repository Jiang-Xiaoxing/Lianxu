package app.lianxu.independent

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal typealias ChatMediaSaver = suspend (Context, ChatImageRef, ImageSource) -> Unit
internal val LocalChatMediaSaver = staticCompositionLocalOf<ChatMediaSaver> { ChatMediaSave::save }

internal object ChatMediaSave {
    fun mediaName(mime:String, id:String):String {
        val extension=when(mime.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/3gpp2" -> "3g2"
            "video/x-matroska" -> "mkv"
            "video/mp2ts" -> "ts"
            "video/mpeg" -> "mpeg"
            else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?: return "Lianxu-$id"
        }
        return "Lianxu-$id.$extension"
    }
    fun folder(mime:String) = if(mime.startsWith("video/")) "Movies/Lianxu" else "Pictures/Lianxu"
    fun copy(input:InputStream, output:OutputStream) = input.copyTo(output)
    suspend fun save(context:Context,ref:ChatImageRef,source:ImageSource):Unit=withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val table=if(ref.mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,mediaName(ref.mime,UUID.randomUUID().toString()))
            put(MediaStore.MediaColumns.MIME_TYPE,ref.mime)
            if(Build.VERSION.SDK_INT>=29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH,folder(ref.mime))
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }
        }
        val uri=checkNotNull(resolver.insert(table,values))
        try {
            source.input().use { input -> checkNotNull(resolver.openOutputStream(uri)).use { copy(input,it) } }
            if(Build.VERSION.SDK_INT>=29) check(resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)==1)
        } catch(e:Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
