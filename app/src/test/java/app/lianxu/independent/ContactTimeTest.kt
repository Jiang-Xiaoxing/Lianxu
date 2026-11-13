package app.lianxu.independent

import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ContactTimeTest {
    private val now=Instant.parse("2026-11-13T10:00:00Z").toEpochMilli()
    private val zone=ZoneId.of("Asia/Shanghai")
    @Test fun relativeDurationsHaveNoFifteenMinuteOrExpiryLimit(){
        mapOf("现在" to 0L,"1秒后" to 1000L,"半小时后" to 1800000L,"三天后" to 259200000L,"两周后" to 1209600000L,"二十一分钟后" to 1260000L).forEach{(text,delay)->assertEquals(text,now+delay,ContactTime.parse(text,now,zone))}
    }
    @Test fun explicitLocalTimesUseTheSuppliedZone(){
        assertEquals(now+86400000,ContactTime.parse("明天 18:00",now,zone))
        assertEquals(now+1800000,ContactTime.parse("2026-11-13 18:30",now,zone))
        assertEquals(now+172800000,ContactTime.parse("后天 18:00",now,zone))
    }
    @Test fun ambiguousInvalidPastAndOverflowNeverProduceGuessedDates(){
        for(text in listOf("晚点","一会儿","18:30","今天 17:00","2026-02-30 18:00","2026-11-14 25:00","0分钟后","-1小时后","999999999999999999999天后","一百天后"))assertTrue(text,runCatching{ContactTime.parse(text,now,zone)}.isFailure)
        val before=Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        for(text in listOf("2026-03-08 02:30","2026-11-01 01:30"))assertTrue(runCatching{ContactTime.parse(text,before,ZoneId.of("America/New_York"))}.isFailure)
    }
    @Test fun initialPlanAndDeferralUseTheSameClockAndGrammar(){
        val ref=SourceRef("a",1,1);val view=RoleContext("a",ref,mapOf(1L to ref),emptyList(),emptyList(),emptyList(),emptyList())
        val raw="""{"future":{"kind":"follow_up","reason":"回头聊","when":"半小时后","evidence":[{"source":1,"layer":"fact"}]}}"""
        val plan=ImmediateContact.parse(raw,"半小时后聊",view,now).flow!!.future!!.timing
        val deferred=CompanionContracts.decision("""{"action":"defer","reason":"再等一下","nextWhen":"半小时后"}""",now,0)
        assertEquals(now+1800000,plan.notBefore);assertEquals(plan.notBefore,deferred.nextAt)
        assertFalse(ImmediateContact.definition(1,now).toString().contains("Unix毫秒"))
    }
    @Test fun conflictingFieldsAndInvalidDeferralCannotSend(){
        val json=JsonParser.parseString("""{"when":"1分钟后","notBefore":$now}""").asJsonObject
        assertTrue(runCatching{ContactTime.field(json,"when","notBefore",now,zone)}.isFailure)
        assertEquals("silent",CompanionContracts.decisionForReply("""{"action":"defer","reason":"晚点","nextWhen":"有空再说"}""",now,0).action)
    }
    @Test fun initialContactKeepsRequestZoneEvenIfThePhoneChangesZone(){
        val original=java.util.TimeZone.getDefault()
        try{
            val ref=SourceRef("a",1,1);val view=RoleContext("a",ref,mapOf(1L to ref),emptyList(),emptyList(),emptyList(),emptyList())
            val guide=ImmediateContact.definition(1,now,zone).toString();assertTrue(guide.contains("Asia/Shanghai"))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            val raw="""{"future":{"kind":"follow_up","reason":"回头聊","when":"明天 18:30","evidence":[{"source":1,"layer":"fact"}]}}"""
            assertEquals(now+86400000+1800000,ImmediateContact.parse(raw,"明天聊",view,now,zone).flow!!.future!!.timing.notBefore)
        }finally{java.util.TimeZone.setDefault(original)}
    }
}
