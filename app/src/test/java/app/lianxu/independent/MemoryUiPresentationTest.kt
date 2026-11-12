package app.lianxu.independent
import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.ZoneId
class MemoryUiPresentationTest {
 @Test fun detailClarityLabels(){assertEquals("快忘了",memoryUiClarityLabel(19.0));assertEquals("记得很牢",memoryUiClarityLabel(80.0))}
 @Test fun clarityBoundaries(){listOf(100.0 to 0,80.0 to 0,79.0 to 1,50.0 to 1,49.0 to 2,20.0 to 2,19.0 to 3,0.0 to 3).forEach{assertEquals(it.second,memoryUiLevel(it.first))}}
 @Test fun calendarBoundaries(){val now=java.time.Instant.parse("2026-11-11T04:00:00Z").toEpochMilli();val zone=ZoneId.of("Asia/Shanghai");assertEquals("今天",memoryUiGroup(now,now,zone));assertEquals("这周",memoryUiGroup(now-6*86400000L,now,zone));assertEquals("更早",memoryUiGroup(now-7*86400000L,now,zone))}
 @Test fun timeFormats(){
  fun at(day:Int,hour:Int=10,minute:Int=20)=java.time.LocalDateTime.of(2026,11,day,hour,minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  assertEquals("10:20",memoryUiTime(at(11),at(11)))
  assertEquals("周二",memoryUiTime(at(10),at(11)))
  assertEquals("11月1日",memoryUiTime(at(1),at(11)))
 }
 @Test fun sourceDatePeriodBoundaries(){
  listOf(0 to "上午",11 to "上午",12 to "下午",17 to "下午",18 to "晚上",23 to "晚上").forEach{(hour,period)->
   val at=java.time.LocalDateTime.of(2026,11,11,hour,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
   assertEquals("11月11日"+period,memoryUiSourceDate(at))
  }
 }}
