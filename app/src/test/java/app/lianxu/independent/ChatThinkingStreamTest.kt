package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ChatThinkingStreamTest {
    private fun tool(id:String)=ChatToolActivity(id,1,"让我想一想","running")
    @Test fun receiptsSplitThinkingInTheOrderTheyWereCalled(){
        val parts=chatThinkingParts("先想A再想B",listOf(ChatThinkingBreak(3,"a"),ChatThinkingBreak(6,"b")),listOf(tool("a"),tool("b")))!!
        assertEquals(listOf("先想A","再想B"),parts.filterIsInstance<ChatThinkingPart.Text>().map{it.text})
        assertEquals(listOf("a","b"),parts.filterIsInstance<ChatThinkingPart.Tool>().map{it.item.id})
        assertTrue(parts[1] is ChatThinkingPart.Tool);assertTrue(parts[3] is ChatThinkingPart.Tool)
    }
    @Test fun noReceiptYetKeepsOnePlainBlock(){
        assertNull(chatThinkingParts("只是想着",emptyList(),emptyList()))
        assertNull(chatThinkingParts("只是想着",listOf(ChatThinkingBreak(0,"missing")),emptyList()))
    }
    @Test fun aReceiptAtTheEndAppendsAfterTheWholeThought(){
        val parts=chatThinkingParts("想完了",listOf(ChatThinkingBreak(3,"a")),listOf(tool("a")))!!
        assertEquals(2,parts.size);assertTrue(parts[0] is ChatThinkingPart.Text);assertTrue(parts[1] is ChatThinkingPart.Tool)
    }
    @Test fun continuationTextAfterAReceiptStaysInTheSameStream(){
        val parts=chatThinkingParts("第一段第二段",listOf(ChatThinkingBreak(3,"a")),listOf(tool("a")))!!
        assertEquals("第一段",(parts[0] as ChatThinkingPart.Text).text)
        assertEquals("第二段",(parts[2] as ChatThinkingPart.Text).text)
    }
}
