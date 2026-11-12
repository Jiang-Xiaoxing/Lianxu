package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ReplyTextPreviewTest {
    @Test fun onlyTheTopLevelReplyBecomesVisible(){
        val parser=ReplyTextPreview()
        assertEquals("",parser.append("{\"appraisal\":{\"reply\":\"内部字段\"},\"reason\":\"不是 \\\"reply\\\"\",\"reply\":\""))
        assertEquals("你好",parser.append("你好"))
        assertEquals("。",parser.append("。\",\"learning\":null}"))
    }
    @Test fun splitEscapesAndSurrogatePairsKeepTheirText(){
        val parser=ReplyTextPreview()
        assertEquals("",parser.append("{\"reply\":\"\\u4f"))
        assertEquals("你\n",parser.append("60\\n\\uD83D"))
        assertEquals("😀\"好\"",parser.append("\\uDE00\\\"好\\\"\"}"))
    }
    @Test fun arbitraryChunkBoundariesDoNotRepeatOrLeakMetadata(){
        val parser=ReplyTextPreview()
        val raw="{\"choices\":[{\"action\":\"listen\"}],\"reply\":\"  慢慢说\\n我在听。  \",\"selected\":\"listen\"}"
        val visible=raw.map{parser.append(it.toString())}.joinToString("")
        assertEquals("  慢慢说\n我在听。  ",visible)
    }
}
