package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

/** 思考断点把回执放回原来的位置：思考A、胶囊、思考B；短尾巴或空尾巴都不补造内容。 */
class ChatThinkingPartsTest {
    private fun tool(id:String,title:String="让我想一想")=ChatToolActivity(id,1,title,"success")

    @Test fun areceiptSitsBetweenTheThinkingBeforeAndAfterIt(){
        val parts=chatThinkingParts("先想一段。后想一段。",listOf(ChatThinkingBreak(5,"t1")),listOf(tool("t1")))
        assertNotNull(parts)
        assertEquals(3,parts!!.size)
        assertEquals("先想一段。",(parts[0] as ChatThinkingPart.Text).text)
        assertEquals("t1",(parts[1] as ChatThinkingPart.Tool).item.id)
        assertEquals("后想一段。",(parts[2] as ChatThinkingPart.Text).text)
    }

    @Test fun aToolCalledBeforeAnyThinkingLeadsTheStream(){
        val parts=chatThinkingParts("后面才想的。",listOf(ChatThinkingBreak(0,"t1")),listOf(tool("t1")))
        assertEquals(2,parts!!.size)
        assertTrue(parts[0] is ChatThinkingPart.Tool)
        assertEquals("后面才想的。",(parts[1] as ChatThinkingPart.Text).text)
    }

    @Test fun aVeryShortTailIsKeptButAnEmptyTailAddsNothing(){
        val short=chatThinkingParts("想好了。嗯",listOf(ChatThinkingBreak(4,"t1")),listOf(tool("t1")))
        assertEquals("嗯",(short!!.last() as ChatThinkingPart.Text).text)
        val empty=chatThinkingParts("想好了。",listOf(ChatThinkingBreak(4,"t1")),listOf(tool("t1")))
        assertEquals(2,empty!!.size)
        assertTrue(empty.last() is ChatThinkingPart.Tool)
        assertEquals(0,empty.count{it is ChatThinkingPart.Text&&it.text.isBlank()})
    }

    @Test fun severalReceiptsKeepCallOrderEvenAtTheSameOffset(){
        val tools=listOf(tool("t1"),tool("t2"))
        val parts=chatThinkingParts("A",listOf(ChatThinkingBreak(1,"t1"),ChatThinkingBreak(1,"t2")),tools)
        assertEquals(listOf("A","t1","t2"),parts!!.map{when(it){is ChatThinkingPart.Text->it.text;is ChatThinkingPart.Tool->it.item.id}})
    }

    @Test fun aReceiptMissingFromStorageIsSkippedInsteadOfBreakingTheStream(){
        val parts=chatThinkingParts("AB",listOf(ChatThinkingBreak(1,"gone")),emptyList())
        assertNull("没有可用回执时退回整段思考，不能吞掉文字",parts)
    }

    @Test fun noBreaksMeansOnePlainBlock(){
        assertNull(chatThinkingParts("整段思考",emptyList(),listOf(tool("t1"))))
    }

    @Test fun oldReceiptsWithoutAPositionKeepTheBlockDisplayButARealZeroStillCounts(){
        val old=ChatToolActivity("old",1,"让我想一想","success")
        val zero=ChatToolActivity("zero",1,"让我想一想","success",reasoningAt=0,reasoningKnown=true)
        assertEquals(listOf("zero"),placedReceipts(listOf(old,zero),1).map{it.id})
        assertTrue(placedReceipts(listOf(zero),2).isEmpty())
    }
}
