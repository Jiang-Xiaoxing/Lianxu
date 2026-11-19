package app.lianxu.independent

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class BreezeStudioContractTest {
    @Test fun localImportAndPreviewLifecycleUseIsolatedTransport()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val resolver=app.contentResolver
        val audio=inst.context.assets.open("voice/modulated-tone.mp3").use{it.readBytes()}
        val uri=requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply{
            put(MediaStore.Audio.Media.DISPLAY_NAME,"lianxu-local-contract-${System.nanoTime()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/mpeg")
        }))
        val old=AppServices.voiceConnections
        try {
            requireNotNull(resolver.openOutputStream(uri)).use{it.write(audio)}
            val paths=mutableListOf<String>()
            AppServices.voiceConnections=HttpsConnectionFactory { url->
                check(url.protocol=="https" && url.host=="api.breeze.blue")
                val path=url.path.removePrefix("/v1/")
                paths+=path
                object:HttpsURLConnection(url) {
                    val sent=ByteArrayOutputStream()
                    override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                    override fun getCipherSuite()="fixture"
                    override fun getLocalCertificates():Array<Certificate>?=null
                    override fun getServerCertificates():Array<Certificate> = emptyArray()
                    override fun getOutputStream()=sent
                    override fun getResponseCode()=200
                    override fun getContentType()=if(path.endsWith("/stream"))"audio/mpeg" else "application/json"
                    override fun getInputStream():InputStream {
                        check(!instanceFollowRedirects)
                        check(requestMethod!="PUT" && requestMethod!="PATCH" && requestMethod!="DELETE")
                        return when(path) {
                            "models"->"""[{"model_id":"breeze-tts-2"}]"""
                            "voices"->"""{"voices":[{"voice_id":"original","name":"原音色"}],"has_more":false}"""
                            "voice-previews/design"->"""{"previews":[{"generated_voice_id":"design"}]}"""
                            "voice-previews/clone"->{
                                val multipart=sent.toString("ISO-8859-1")
                                assertTrue(multipart.contains("name=\"files\""))
                                assertTrue(multipart.contains(String(audio,Charsets.ISO_8859_1)))
                                """{"generated_voice_id":"clone","requires_verification":true}"""
                            }
                            "voice-previews/remix"->"""{"generation_job_id":"job","status":"pending"}"""
                            "voice-previews/remix/job"->"""{"status":"completed","generated_voice_id":"remix","language_code":"zh"}"""
                            "voice-previews/remix/stream"->return audio.inputStream()
                            "voice-previews/remix/save"->"""{"voice_id":"new"}"""
                            else->error("unexpected studio path")
                        }.byteInputStream()
                    }
                }
            }
            val account=BreezeVoiceAccount(app)
            val voice=account.refresh("local-studio-test").single()
            val owner=account.accountId()
            val studio=BreezeVoiceStudio(account)
            assertEquals("design",studio.design(owner,"温暖柔和").id)
            ImportedVoiceRecording.open(app,uri).use { local->
                assertTrue(local.durationMs>=3000)
                assertArrayEquals(audio,local.file.readBytes())
                assertTrue(runCatching{studio.clone(owner,local,false)}.isFailure)
                assertFalse("voice-previews/clone" in paths)
                val clone=studio.clone(owner,local,true)
                assertTrue(clone.needsVerification)
                assertTrue(runCatching{studio.saveAs(clone,"新音色")}.isFailure)
            }
            val remix=studio.remix(owner,voice,"更轻柔一点")
            assertArrayEquals(audio,studio.preview(remix))
            assertEquals("new",studio.saveAs(remix,"新音色"))
            assertEquals("original",voice.id)
            account.refresh("different-local-account")
            assertTrue(runCatching{studio.saveAs(remix,"误用旧账户")}.isFailure)
            assertEquals(1,paths.count{it.endsWith("/save")})
        } finally {AppServices.voiceConnections=old;resolver.delete(uri,null,null)}
    }
}

