package app.lianxu.independent

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object ChatVoiceDelivery {
    val pending = MutableStateFlow(emptySet<String>())
    val revision = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val tails = mutableMapOf<String, Job>()
    val definition = mapOf("type" to "function","function" to mapOf(
        "name" to "send_short_voice","description" to "只有场景适合时偶尔以一句短语音作为本次回复。单独调用，text 是直接说给对方的原句，不要另发相同文字。",
        "parameters" to mapOf("type" to "object","properties" to mapOf("text" to mapOf("type" to "string","maxLength" to 300)),
            "required" to listOf("text"),"additionalProperties" to false)))
    fun key(role:String,turn:String,attempt:Int,text:String) = stableDigest(listOf(role,turn,attempt.toString(),text).joinToString("\u0000"))
    fun file(context:Context,id:String) = File(File(context.cacheDir,"reply-audio"),"$id.mp3")

    @Synchronized fun submit(context:Context, role:String, id:String, valid:()->Boolean, synthesize:suspend()->ByteArray) {
        if(id in pending.value) return
        val file=file(context,id)
        if(file.isFile) return
        val before=tails[role]
        pending.update { it+id }
        val job=scope.launch(start=CoroutineStart.LAZY) {
            var temp: File?=null
            try {
                before?.join()
                if(!valid()) return@launch
                val bytes=synthesize()
                if(!valid()) return@launch
                val dir=requireNotNull(file.parentFile);dir.mkdirs()
                val f=File.createTempFile("reply-", ".tmp",dir);temp=f;f.writeBytes(bytes)
                VoiceAudio.decode(f)
                if(valid()) check(f.renameTo(file))
            } catch(e:CancellationException){throw e} catch(_:Exception){ /* Existing message is the fallback. */ }
            finally {temp?.delete();pending.update{it-id};revision.update{it+1}}
        }
        tails[role]=job
        job.invokeOnCompletion { synchronized(this){if(tails[role]===job) tails.remove(role)} }
        job.start()
    }
}

