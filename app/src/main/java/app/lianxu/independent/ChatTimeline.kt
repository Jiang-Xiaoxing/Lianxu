package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId

internal data class MessageCluster(val id:Long,val mine:Boolean,val friendId:String,val day:Long,val minute:Long,val messages:List<Message>)
internal sealed interface ChatRow {
    val key:String
    data class Date(val day:Long,val firstId:Long):ChatRow{override val key="date-$day-$firstId"}
    data class Group(val value:MessageCluster,val showTime:Boolean):ChatRow{override val key="group-${value.id}"}
}
internal fun clusterMessages(messages:List<Message>,zone:ZoneId=ZoneId.systemDefault()):List<MessageCluster> = buildList {
    messages.forEach {message->
        val minute=message.created/60000;val day=Instant.ofEpochMilli(message.created).atZone(zone).toLocalDate().toEpochDay()
        val old=lastOrNull()
        if(old!=null&&old.friendId==message.friendId&&old.mine==message.mine&&old.minute==minute&&old.day==day){removeAt(lastIndex);add(old.copy(messages=old.messages+message))}
        else add(MessageCluster(message.id,message.mine,message.friendId,day,minute,listOf(message)))
    }
}
internal fun chatTimeline(messages:List<Message>):List<ChatRow> = buildList {
    val groups=clusterMessages(messages)
    groups.forEachIndexed {index,group->
        val previous=groups.getOrNull(index-1)
        if(previous?.day!=group.day)add(ChatRow.Date(group.day,group.id))
        add(ChatRow.Group(group,previous==null||previous.minute!=group.minute))
    }
}
