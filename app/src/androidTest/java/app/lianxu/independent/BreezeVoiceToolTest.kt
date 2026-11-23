package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BreezeVoiceToolTest {
    @Test fun optionalDeclarationExecutionAndRoleIsolation()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val name="voice-tools-${System.nanoTime()}.db"
        val store=Store(app,name)
        try {
            store.add(listOf(Friend("a","A","Synthetic"),Friend("b","B","Synthetic")),false)
            val repo=ProcessingRepository(store)
            val config=ModelConfig("test","test","custom","https://example.invalid","test",64000,2048)
            val target=VoiceTarget("local","voc_1",1)
            for(mode in listOf("off","on","disabledDuringCall","ordinary")) {
                var eligible=if(mode=="off") null else target
                val turn=ChatLedger(store).begin("a","本地样例",config.id)
                val tools=MemoryTools(repo,turn,"本地样例",repo.references("a","本地样例",turn.userMessageId),voiceEligibility={VoiceEligibility(eligible, if(eligible==null) VoiceReason.ROLE_DISABLED else null)}){true}
                val request=TextChatRequest(listOf(WireMessage("user","本地样例")))
                val client=object:TextChatClient{
                    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                        val actual=request.tools.any{toolNameOf(it)=="send_short_voice"}
                        assertEquals(mode!="off",actual)
                        if(mode=="off") assertFalse(request.messages.any{it.content.contains("send_short_voice")})
                        if(mode=="disabledDuringCall") eligible=null
                        return if(mode=="ordinary") TextChatResult.Success("普通文字")
                            else TextChatResult.Success("",listOf(ChatToolCall("local","send_short_voice","""{"text":"晚安。"}""")))
                    }
                }
                val result=tools.run(config,"fixture",request,client)
                if(mode=="off") assertTrue(result is TextChatResult.Failure)
                else assertEquals(if(mode=="ordinary")"普通文字" else "晚安。",(result as TextChatResult.Success).text)
                assertEquals(mode=="on",tools.spokenReply!=null)
                ChatLedger(store).cancel(turn.id,turn.attempt)
            }
            val account=BreezeVoiceAccount(app)
            assertFalse(account.enabled("b"));account.enable("a",true)
            assertTrue(account.enabled("a"));assertFalse(account.enabled("b"))
            assertNull(account.target("a"))
            account.enable("a",false)
        } finally {store.close();app.deleteDatabase(name)}
    }
}

