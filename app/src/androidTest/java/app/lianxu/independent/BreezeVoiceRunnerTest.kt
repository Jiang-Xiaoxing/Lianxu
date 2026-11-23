package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class BreezeVoiceRunnerTest {
    @Test fun configuredRoleCanSpeakWhileNextTextCompletes()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val name="voice-runner-${System.nanoTime()}"
        val store=Store(app,name)
        val vault=ModelVault(app,name,store)
        val config=ModelConfig("local","本地","custom","https://example.invalid","local",128000,2048)
        val audio=inst.context.assets.open("voice/modulated-tone.mp3").use{it.readBytes()}
        val entered=CompletableDeferred<Unit>()
        val release=CountDownLatch(1)
        val old=AppServices.voiceConnections
        val account=BreezeVoiceAccount(app)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        var badSpeech=false
        var badCatalog=false
        var replies=0
        try {
            AppServices.voiceConnections=HttpsConnectionFactory { url ->
                val listing=url.path=="/v1/voices"
                object:HttpsURLConnection(url){
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy()=false
                    override fun getCipherSuite()="fixture"
                    override fun getLocalCertificates():Array<Certificate>?=null
                    override fun getServerCertificates():Array<Certificate> = emptyArray()
                    override fun getResponseCode()=if((listing&&badCatalog) || (url.path.contains("text-to-speech") && badSpeech))503 else 200
                    override fun getContentType()=if(listing)"application/json" else "audio/mpeg"
                    override fun getOutputStream()=ByteArrayOutputStream()
                    override fun getInputStream():InputStream {
                        if(url.path=="/v1/models")return """[{"model_id":"breeze-tts-2"}]""".byteInputStream()
                        if(listing) return (if(url.query.contains("next_page_token=more"))
                            """{"voices":[{"voice_id":"v2","name":"二"}],"has_more":false}""" else
                            """{"voices":[{"voice_id":"v1","name":"一"}],"has_more":true,"next_page_token":"more"}""").byteInputStream()
                        assertEquals("/v1/text-to-speech/v2",url.path)
                        entered.complete(Unit);check(release.await(12,TimeUnit.SECONDS));return audio.inputStream()
                    }
                }
            }
            assertEquals(VoiceReason.ROLE_DISABLED,account.eligibility(name).reason)
            val list=account.refresh("fixture-account-one")
            assertEquals(listOf("v1","v2"),list.map{it.id})
            account.enable(name,true)
            assertEquals(VoiceReason.NO_VOICE,account.eligibility(name).reason)
            account.bind(name,list.last(),account.accountId());account.enable(name,true)
            badCatalog=true;assertTrue(runCatching{account.refresh()}.isFailure)
            assertEquals("v2",account.selected(name));assertEquals(VoiceReason.SERVICE_NOT_READY,account.eligibility(name).reason)
            badCatalog=false;account.refresh()
            val repo=ProcessingRepository(store)
            store.add(listOf(Friend(name,"合成角色","本地样例")),false)
            vault.save(config,"fixture-chat");vault.setPurpose(ModelPurpose.CHAT,config.id)
            val client=object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                    assertTrue(request.tools.any{toolNameOf(it)=="send_short_voice"})
                    return if(replies++!=1)TextChatResult.Success("",listOf(ChatToolCall("s","send_short_voice","""{"text":"晚安。"}""")))
                        else TextChatResult.Success("普通文字。")
                }
            }
            val runner=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{}, {})
            suspend fun send(text:String){
                withContext(Dispatchers.Main){assertTrue(runner.send(name,text,{}))}
                withTimeout(12000){while(runner.sending.isNotEmpty())delay(20)}
            }
            send("第一轮");withTimeout(4000){entered.await()}
            send("第二轮")
            assertEquals(listOf("晚安。","普通文字。"),store.messagesFor(name).filterNot{it.mine}.map{it.text})
            val id=ChatVoiceDelivery.pending.value.single()
            release.countDown();withTimeout(10000){while(id in ChatVoiceDelivery.pending.value)delay(20)}
            assertArrayEquals(audio,ChatVoiceDelivery.file(app,id).readBytes())
            for (detailed in listOf(false,true)) {
                val ids=store.readableDatabase.rawQuery("SELECT id FROM chat_turns WHERE friend_id=?",arrayOf(name)).use { c ->
                    buildSet { while(c.moveToNext())add(c.getString(0)) }
                }
                val voice=Diagnostics.export(detailed).decodeToString().lineSequence().filter { it.isNotBlank() }
                    .map { org.json.JSONObject(it) }.filter { it.optString("event")=="chat.voice" && it.optString("trace") in ids }
                    .map { it.getJSONObject("data") }.toList()
                assertEquals(2,voice.count { it.optString("stage")=="OFFER" && it.optString("status")=="OFFERED" })
                assertTrue(voice.any { it.optString("status")=="CALLED" })
                assertTrue(voice.any { it.optString("status")=="NOT_CALLED" })
                assertTrue(voice.any { it.optString("stage")=="CACHE" && it.optString("status")=="SUCCEEDED" })
            }
            badSpeech=true
            send("第三轮")
            withTimeout(6000){while(ChatVoiceDelivery.pending.value.isNotEmpty())delay(20)}
            assertEquals(listOf("晚安。","普通文字。","晚安。"),store.messagesFor(name).filterNot{it.mine}.map{it.text})
            assertNull(account.target(name))
            account.refresh("fixture-account-two")
            assertNull(account.selected(name));assertNull(account.target(name))
        }finally{
            release.countDown();scope.coroutineContext.job.cancelAndJoin()
            account.enable(name,false);AppServices.voiceConnections=old
            vault.delete(config.id);store.close();app.deleteDatabase(name)
        }
    }
}

