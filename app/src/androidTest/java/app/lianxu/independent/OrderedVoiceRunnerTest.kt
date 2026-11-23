package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class OrderedVoiceRunnerTest {
    @Test fun oneFailedSegmentLeavesLaterDuplicatePlayableInPersistedOrder()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext
        val role="mixed-"+System.nanoTime()
        val store=Store(app,role)
        val vault=ModelVault(app,role,store)
        val account=BreezeVoiceAccount(app)
        val old=AppServices.voiceConnections
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val audio=inst.context.assets.open("voice/modulated-tone.mp3").use{it.readBytes()}
        val requests=mutableListOf<String>()
        val config=ModelConfig("local","本地","custom","https://example.invalid","local",128000,2048)
        val source=listOf(ReplyPart("一",false),ReplyPart("重复",true),ReplyPart("三",false),ReplyPart("重复",true))
        var sendCount=0
        try {
            AppServices.voiceConnections=HttpsConnectionFactory { url:URL ->
                val data=ByteArrayOutputStream()
                val speech=url.path.contains("text-to-speech")
                object:HttpsURLConnection(url){
                    override fun connect(){}
                    override fun disconnect(){}
                    override fun usingProxy()=false
                    override fun getCipherSuite()="fixture"
                    override fun getLocalCertificates():Array<Certificate>?=null
                    override fun getServerCertificates():Array<Certificate> = emptyArray()
                    override fun getOutputStream()=data
                    override fun getResponseCode():Int {
                        if(!speech)return 200
                        requests+=JSONObject(data.toString("UTF-8")).getString("text")
                        return if(requests.size==1)503 else 200
                    }
                    override fun getContentType()=if(speech)"audio/mpeg" else "application/json"
                    override fun getInputStream()=when(url.path){
                        "/v1/models"->"""[{"model_id":"breeze-tts-2"}]""".byteInputStream()
                        "/v1/voices"->"""{"voices":[{"voice_id":"v","name":"样例"}],"has_more":false}""".byteInputStream()
                        else->audio.inputStream()
                    }
                }
            }
            store.add(listOf(Friend(role,"合成角色","仅供设备测试")),false)
            vault.save(config,"fixture");vault.setPurpose(ModelPurpose.CHAT,config.id)
            val voice=account.refresh("mixed-fixture").single()
            account.enable(role,true);account.bind(role,voice,account.accountId())
            val client=object:TextChatClient {
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                    val offered=request.tools.any{toolNameOf(it)=="send_short_voice"}
                    assertEquals(sendCount<2,offered)
                    return when(sendCount++){
                        0->TextChatResult.Success("不能重复显示",listOf(ChatToolCall("call","send_short_voice",
                            """{"segments":[{"type":"text","text":"一"},{"type":"voice","text":"重复"},{"type":"text","text":"三"},{"type":"voice","text":"重复"}]}""")))
                        else->TextChatResult.Success("一段\n\n二段")
                    }
                }
            }
            val repo=ProcessingRepository(store)
            val runner=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
            suspend fun send(input:String){
                withContext(Dispatchers.Main){assertTrue(runner.send(role,input,{}))}
                withTimeout(20000){while(runner.sending.isNotEmpty())delay(20)}
            }
            send("合成混排")
            withTimeout(15000){ChatVoiceDelivery.pending.first{it.isEmpty()}}
            val persisted=Store(app,role)
            try {
                val turn=ChatLedger(persisted).all().single{it.friendId==role}
                val body=persisted.messagesFor(role).last().text
                assertEquals(TurnState.COMPLETED,turn.state)
                assertEquals(source,OrderedChatReply.decode(body,turn.replyLayout))
                assertEquals(listOf("重复","重复"),requests)
                assertFalse(ChatVoiceDelivery.file(app,OrderedChatReply.audioKey(turn,body,1)).exists())
                assertArrayEquals(audio,ChatVoiceDelivery.file(app,OrderedChatReply.audioKey(turn,body,3)).readBytes())
            } finally {persisted.close()}
            send("我现在不方便听")
            account.enable(role,false)
            send("已关闭语音")
            assertEquals(listOf(OrderedChatReply.body(source),"一段\n\n二段","一段\n\n二段"),store.messagesFor(role).filterNot{it.mine}.map{it.text})
            assertEquals(2,requests.size)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            account.enable(role,false);AppServices.voiceConnections=old
            vault.delete(config.id);store.close();app.deleteDatabase(role)
        }
    }
}
