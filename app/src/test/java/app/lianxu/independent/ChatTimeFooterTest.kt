package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ChatTimeFooterTest {
    private val now=Instant.parse("2027-01-01T00:05:00Z")
    private val zone=ZoneId.of("Asia/Shanghai")
    private val raw="约好2027年1月3日14:30见；不要改我的原话。"
    private fun build(previous:Instant?,at:Instant=now,tz:ZoneId=zone):PromptBuild.Ready {
        val recent=previous?.let{listOf(Message(1,"a",raw,true,it.toEpochMilli()),Message(2,"a","好的",false,it.toEpochMilli()))}.orEmpty()
        return CompanionPrompt.build(Friend("a","合成","测试"),RolePrompts(),recent,raw,now=at,zone=tz) as PromptBuild.Ready
    }
    private fun tail(previous:Instant?)=build(previous).request.messages.last().content
    @Test fun belowOneMinuteIncludesExactZero(){
        for(ms in listOf(0L,1L,59_999L))assertTrue(tail(now.minusMillis(ms)).contains("不到 1 分钟"))
    }
    @Test fun minuteHourAndDayBoundariesUseExistingUnits(){
        for((ms,expected) in listOf(60_000L to "1 分钟",3_599_999L to "59 分钟",3_600_000L to "1 小时",86_399_999L to "23 小时",86_400_000L to "1 天",259_200_000L to "3 天")){
            val text=tail(now.minusMillis(ms))
            assertTrue(text.contains("距上条原消息约 $expected。"));assertFalse(text.contains("秒"))
        }
    }
    @Test fun fullDateWeekdayAndYearSurviveTheYearBoundary(){
        val at=Instant.parse("2026-12-31T16:05:00Z")
        val m=build(Instant.parse("2026-12-30T16:05:00Z"),at).request.messages
        assertTrue(m.last().content.contains("2027年1月1日 星期五 00:05"))
        assertTrue(m.last().content.contains("约 1 天"))
        assertTrue(m.first{it.sourceMessageId=="1"}.content.startsWith("[昨天 00:05]"))
        assertFalse(m.last().content.contains("+08:00"))
    }
    @Test fun footerAndHistoryUseTheSameExplicitTimezone(){
        for((tz,date,previousLabel) in listOf(Triple(ZoneId.of("Asia/Shanghai"),"2027年1月1日 星期五 08:05","[今天 07:55]"),Triple(ZoneId.of("America/Los_Angeles"),"2026年12月31日 星期四 16:05","[今天 15:55]"))){
            val m=build(now.minusSeconds(600),tz=tz).request.messages
            assertTrue(m.last().content.contains(date));assertTrue(m.last().content.contains("约 10 分钟"))
            assertTrue(m.first{it.sourceMessageId=="1"}.content.startsWith(previousLabel))
        }
    }
    @Test fun futureHistoryKeepsTheWarningRatherThanClampingToJustNow(){
        for(ms in listOf(1L,86_400_000L)){
            val text=tail(now.plusMillis(ms))
            assertTrue(text.contains("历史消息时间晚于当前设备时间，请勿据此臆测事件日期"))
            assertFalse(text.contains("距上条"));assertFalse(text.contains("不到 1 分钟"))
        }
    }
    @Test fun noHistoryHasNoInventedGap(){
        val text=tail(null)
        assertTrue(text.contains("2027年1月1日 星期五 08:05"));assertFalse(text.contains("距上条"))
    }
    @Test fun exactAppointmentAndUserWordsStayUntouched(){
        val m=build(now.minusSeconds(259200)).request.messages
        assertEquals(raw,m.last{it.role=="user"}.content)
        val previous=m.first{it.sourceMessageId=="1"}
        assertEquals(raw,previous.content.substring(previous.sourcePrefix))
        assertEquals(1,m.count{it.turnContext})
    }
}
