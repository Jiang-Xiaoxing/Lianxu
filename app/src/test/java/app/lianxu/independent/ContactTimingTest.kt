package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContactTimingTest {
    private val now=1_800_000_000_000L
    private val ref=SourceRef("a",1,1)
    private val view=RoleContext("a",ref,mapOf(1L to ref),emptyList(),emptyList(),emptyList(),emptyList())
    private fun future(at:Long,legacy:String="")="""{"future":{"kind":"follow_up","reason":"回头聊聊结果","notBefore":$at$legacy,"evidence":[{"source":1,"layer":"fact"}]}}"""
    @Test fun newContactNeedsNoExpiryAndAcceptsNowOrOneSecond(){
        for(at in listOf(now,now+1000)){
            val timing=ImmediateContact.parse(future(at),"过会儿聊",view,now).flow!!.future!!.timing
            assertEquals(at,timing.notBefore);assertEquals(Long.MAX_VALUE,timing.expiresAt)
        }
    }
    @Test fun legacyExpiryNoLongerDecidesWhetherAValidContactIsAccepted(){
        val new=ImmediateContact.parse(future(now+1000),"过会儿聊",view,now).flow!!.future!!
        val old=new.copy(timing=new.timing.copy(expiresAt=now-1000))
        assertTrue(ImmediateContact.same(old,new))
        assertEquals(new,ImmediateContact.parse(future(now+1000,",\"expiresAt\":${now-1000}"),"过会儿聊",view,now).flow!!.future)
        val restored=Gson().fromJson(Gson().toJson(old),RoleFuture::class.java)
        assertEquals(old,restored);assertFalse(ImmediateContact.same(old,new.copy(timing=new.timing.copy(notBefore=now+2000))))
    }
    @Test fun oldExperienceEnvelopeAcceptsShortWaitWithoutExpiry(){
        val raw="""{"reconsider":{"reason":"等会儿聊","notBefore":${now+1000}}}"""
        assertEquals(now+1000,CompanionContracts.experience(raw,"",now,emptySet()).reconsider!!.notBefore)
    }
    @Test fun technicalBackoffIsSeparateFromTheChosenTimestamp(){
        assertEquals(1000,ContactRetry.delay(now+1000,now,false))
        assertEquals(0,ContactRetry.delay(now-1000,now,false))
        assertEquals(ContactRetry.REENTRY_DELAY_MS,ContactRetry.delay(now+1000,now,true))
        assertEquals(60_000,ContactRetry.delay(now+60_000,now,true))
        assertEquals(900_000,ContactRetry.FAILED_RETRY_DELAY_MS)
    }
    @Test fun lateWakeupLetsModelDeferPastLegacyExpiryAndReceivesRecentChat():Unit=runBlocking{
        val opportunity=CompanionOpportunity("followup","a",1,1,0,0,now-5000,now-1000,"到时聊结果","queued",1)
        val snapshot=CompanionSnapshot("a",0,0,"stamp",listOf(ref),"","enabled")
        var request:TextChatRequest?=null
        val next=now+86_400_000
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,value:TextChatRequest):TextChatResult{
            request=value;return TextChatResult.Success("""{"action":"defer","reason":"最新聊天说改明天","text":"","nextAt":$next}""")
        }}
        val decision=CompanionModel(client).reconsider(ModelConfig("fake","fake","custom","https://example.invalid","fake",64000,4096),"fake",opportunity,snapshot,"甲",now,"",recent=listOf(Message(2,"a","改成明天再聊",true,now-500)))
        assertEquals(next,decision.nextAt)
        val prompt=request!!.messages.joinToString("\n"){it.content}
        assertTrue(prompt.contains("改成明天再聊"));assertFalse(prompt.contains("原始expiresAt"));assertFalse(prompt.contains("至少晚于当前15分钟"));assertTrue(prompt.contains("系统可能晚于你选的时间"))
    }
}
