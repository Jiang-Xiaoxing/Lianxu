package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ChatTimelineTest {
    @Test fun groupsOnlySameRoleCharacterAndMinute(){
        fun m(id:Long,friend:String,mine:Boolean,time:Long)=Message(id,friend,"合成",mine,time)
        val groups=clusterMessages(listOf(m(1,"a",false,60000),m(2,"a",false,61000),m(3,"a",true,62000),m(4,"b",true,63000),m(5,"b",true,120000)),ZoneId.of("UTC"))
        assertEquals(listOf(2,1,1,1),groups.map{it.messages.size});assertEquals(4,groups.size)
    }
    @Test fun repeatedDaysKeepTimelineKeysUnique(){
        val messages=listOf(Message(1,"a","a",false,0),Message(2,"a","b",true,86400000),Message(3,"a","c",false,0))
        val rows=chatTimeline(messages);assertEquals(rows.size,rows.map{it.key}.distinct().size)
    }
}
