package app.lianxu.independent

internal const val CHAT_CAPSULE_TRANSITION_MS=1500
internal fun toolCapsuleKey(item:ChatToolActivity,owner:String="")="tool:$owner:${item.attempt}:${item.id}"
internal fun memoryCapsuleKey(pill:MemoryPill)="batch:${pill.friendId}:${pill.batchId}"

/** All actual chat capsules, using existing projections and labels without changing the journal. */
internal fun chatCapsuleFrames(raw:List<ChatToolActivity>,pills:List<MemoryPill>,owner:String=""):Map<String,String> {
    val items=mergeRecordPills(raw.associateBy{it.id}.values.toList()).filter{it.state!=ChatToolActivity.SUPERSEDED}
    val groups=recallReceiptGroups(items)
    return buildMap{
        items.forEach{item->
            val group=groups[item.id]
            if(group==null)put(toolCapsuleKey(item,owner),"${item.state}:${item.label()}")
            else if(group.id==item.id)put(toolCapsuleKey(item,owner),group.items.joinToString("|"){"${it.id}:${it.state}:${it.recallOutcome()}"})
        }
        pills.forEach{put(memoryCapsuleKey(it),it.text)}
    }
}

internal data class ChatCapsuleMotionEvent(val serial:Long,val startedAt:Long,val scale:Float=1f) {
    private val duration=(CHAT_CAPSULE_TRANSITION_MS*scale).toLong().coerceAtLeast(0)
    fun remaining(now:Long)=(duration-(now-startedAt).coerceAtLeast(0)).coerceIn(0,duration).toInt()
    fun progress(now:Long)=if(duration==0L)1f else 1f-remaining(now).toFloat()/duration
}

/** Page-local display bookkeeping only. Existing history is the baseline, never an arrival. */
internal data class ChatCapsuleMotionUpdate(val seen:Map<String,String>,val events:Map<String,ChatCapsuleMotionEvent>,val serial:Long,val scale:Float)
internal class ChatCapsuleMotionTracker(initial:Map<String,String>) {
    private var seen=initial
    private var serial=0L
    private var scale=1f
    private var events=emptyMap<String,ChatCapsuleMotionEvent>()
    fun prepare(frames:Map<String,String>,now:Long,durationScale:Float=1f):ChatCapsuleMotionUpdate {
        val next=events.filter{(id,event)->scale==durationScale&&id in frames&&event.remaining(now)>0}.toMutableMap()
        val nextSeen=seen.toMutableMap();var nextSerial=serial
        frames.forEach{(id,state)->
            if(seen[id]!=state){nextSeen[id]=state;if(durationScale>0f)next[id]=ChatCapsuleMotionEvent(++nextSerial,now,durationScale)}
        }
        // A lazy row or live→saved handoff returning in the same state is not a new result.
        return ChatCapsuleMotionUpdate(nextSeen.toMap(),next.toMap(),nextSerial,durationScale)
    }
    fun commit(update:ChatCapsuleMotionUpdate){
        seen=update.seen;events=update.events;serial=update.serial;scale=update.scale
    }
}
