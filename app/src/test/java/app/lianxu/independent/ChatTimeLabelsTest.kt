package app.lianxu.independent

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** 发给模型的近期聊天：每条前加简短时间，相隔超过半天插「隔了多久」；不改存库原文。 */
class ChatTimeLabelsTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun at(y:Int,mo:Int,d:Int,h:Int,mi:Int)=LocalDateTime.of(y,mo,d,h,mi).atZone(zone).toInstant()
    private val now=at(2026,11,11,9,0)

    @Test fun todayYesterdayAndOlderLabels(){
        assertEquals("[今天 08:05]",ChatTimeLabels.label(at(2026,11,11,8,5).toEpochMilli(),now,zone))
        assertEquals("[昨天 22:10]",ChatTimeLabels.label(at(2026,11,10,22,10).toEpochMilli(),now,zone))
        assertEquals("[11月8日 22:10]",ChatTimeLabels.label(at(2026,11,8,22,10).toEpochMilli(),now,zone))
        assertEquals("[2025年12月31日 23:59]",ChatTimeLabels.label(at(2025,12,31,23,59).toEpochMilli(),now,zone))
    }

    @Test fun gapOnlyOverHalfADay(){
        val base=at(2026,11,1,10,0).toEpochMilli();val h=3_600_000L
        assertNull(ChatTimeLabels.gap(base,base+12*h))
        assertEquals("（隔了 14 小时）",ChatTimeLabels.gap(base,base+14*h+5))
        assertEquals("（隔了 3 天）",ChatTimeLabels.gap(base,base+3*24*h+7*h))
    }

    @Test fun promptHistoryCarriesTimeButStoredTextAndCurrentInputStayRaw(){
        val friend=Friend("a","合成","合成设定")
        val recent=listOf(Message(1,"a","早",true,at(2026,11,8,22,10).toEpochMilli()),Message(2,"a","晚安",false,at(2026,11,8,22,11).toEpochMilli()),
            Message(3,"a","在吗",true,at(2026,11,11,8,5).toEpochMilli()),Message(4,"a","在",false,at(2026,11,11,8,6).toEpochMilli()))
        val ready=CompanionPrompt.build(friend,RolePrompts(),recent,"现在",now=now,zone=zone) as PromptBuild.Ready
        val history=ready.request.messages.filter{it.sourceMessageId.isNotBlank()}
        assertEquals(listOf("[11月8日 22:10] 早","[11月8日 22:11] 晚安","（隔了 2 天）\n[今天 08:05] 在吗","[今天 08:06] 在"),history.map{it.content})
        assertEquals(recent.map{it.text},history.map{it.content.substring(it.sourcePrefix)})
        assertEquals("早",recent[0].text)
        assertTrue(ready.request.messages.any{it.role=="user"&&it.sourceMessageId.isEmpty()&&it.content=="现在"})
    }
}
