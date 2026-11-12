package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MessageReloadTest {
    private fun fp(friend:String,count:Int,maxId:Long,deleted:Long=0,revision:Long=0,reasoning:Long=0,body:Long=0)=
        MessageFingerprint(friend,count,maxId,deleted,revision,reasoning,body)
    private fun msg(id:Long,friend:String,text:String="t$id")=Message(id,friend,text,false,id*1000)

    @Test fun unchangedChatsAreNotReloaded(){
        val prints=mapOf("a" to fp("a",2,2),"b" to fp("b",1,3))
        assertTrue(MessageReload.friendsToReload(prints,prints).isEmpty())
        val previous=listOf(msg(1,"a"),msg(2,"a"),msg(3,"b"))
        val merged=MessageReload.merge(previous,emptyMap(),prints)
        assertSame(previous[0],merged[0])
        assertSame(previous[2],merged[2])
        assertEquals(previous,merged)
    }

    @Test fun onlyTheChangedChatIsReloaded(){
        val cached=mapOf("a" to fp("a",2,2),"b" to fp("b",1,3))
        val current=mapOf("a" to fp("a",3,4),"b" to fp("b",1,3))
        assertEquals(setOf("a"),MessageReload.friendsToReload(cached,current))
        val previous=listOf(msg(1,"a"),msg(2,"a"),msg(3,"b"))
        val reloaded=mapOf("a" to listOf(msg(1,"a"),msg(2,"a"),msg(4,"a","new")))
        val merged=MessageReload.merge(previous,reloaded,current)
        assertEquals(listOf(1L,2L,3L,4L),merged.map{it.id})
        assertSame(previous[2],merged[2])
        assertEquals("new",merged[3].text)
        assertEquals("b",merged[2].friendId)
    }

    @Test fun deletedChatMessagesAreDropped(){
        val previous=listOf(msg(1,"a"),msg(2,"b"))
        val prints=mapOf("a" to fp("a",1,1))
        val merged=MessageReload.merge(previous,emptyMap(),prints)
        assertEquals(listOf(1L),merged.map{it.id})
        assertEquals("a",merged.single().friendId)
    }

    @Test fun newChatIsLoadedAndOrderStaysById(){
        val cached=mapOf("a" to fp("a",1,2))
        val current=mapOf("a" to fp("a",1,2),"b" to fp("b",1,1))
        assertEquals(setOf("b"),MessageReload.friendsToReload(cached,current))
        val previous=listOf(msg(2,"a"))
        val merged=MessageReload.merge(previous,mapOf("b" to listOf(msg(1,"b"))),current)
        assertEquals(listOf(1L,2L),merged.map{it.id})
        assertEquals("b",merged[0].friendId)
        assertEquals("a",merged[1].friendId)
    }

    @Test fun revisionOrWithdrawnChangeReloadsThatChat(){
        val cached=mapOf("a" to fp("a",1,1,deleted=0,revision=1))
        assertEquals(setOf("a"),MessageReload.friendsToReload(cached,mapOf("a" to fp("a",1,1,deleted=1,revision=2))))
        assertEquals(setOf("a"),MessageReload.friendsToReload(cached,mapOf("a" to fp("a",1,1,deleted=0,revision=2,reasoning=8))))
    }
}
