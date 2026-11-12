package app.lianxu.independent
import org.junit.Assert.*
import org.junit.Test
class MemoryChatJumpTest {
 @Test fun locateAcrossVersions(){val rows=listOf(Message(12,"role","hello",true,0));assertEquals(0,memoryChatIndex(rows,SourceRef("role",12,99)));assertNull(memoryChatIndex(rows,SourceRef("role",13,1)));assertNull(memoryChatIndex(rows,SourceRef("other",12,1)))}
 @Test fun consumeOnlyOnce(){val request=MemoryChatRequest();val source=SourceRef("role",12,1);request.pending=source;assertEquals(source,request.consume());assertNull(request.consume());assertNull(request.pending)}
 private fun dialogue(version:Long,user:String)=SourceDialogue("r$version",SourceRef("role",12,version),Message(12,"role",user,true,0),Message(13,"role","回复",false,0),"Asia/Shanghai")
 private fun evidence(version:Long)=MemoryEvidence(SourceRef("role",12,version),"user","原话","快照",0,"Asia/Shanghai")
 @Test fun sourceCardUsesExactRecordedVersion(){val rows=listOf(dialogue(2,"改过的话"),dialogue(1,"当时的话"));assertEquals("当时的话",memorySourceDialogue(rows,evidence(1))!!.user.text)}
 @Test fun editedTurnFallsBackInsteadOfShowingNewText(){val rows=listOf(dialogue(2,"改过的话"));assertNull(memorySourceDialogue(rows,evidence(1)));assertEquals(0,memoryChatIndex(listOf(Message(12,"role","改过的话",true,0)),evidence(1).source))}
}
