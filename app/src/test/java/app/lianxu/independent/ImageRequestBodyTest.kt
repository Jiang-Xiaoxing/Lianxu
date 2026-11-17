package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class ImageRequestBodyTest {
    private val config=ModelConfig("c","合成","custom","https://example.invalid/v1","合成模型",128000,2048)
    @Test fun streamedJsonMatchesCountAndPixelsAcrossBase64Blocks(){
        for(n in listOf(1,2,3,12287,12288,12289,24579)){
            val data=ByteArray(n){(it*17).toByte()}
            val req=TextChatRequest(listOf(WireMessage("user","中文\n\"\\\u0000",images=listOf(WireImage("image/png",data,"来源[0,0,2048,20)，第1/2张")))))
            val body=ImageWire.body(config,req,false);val out=ByteArrayOutputStream();ImageWire.write(body,req,out)
            assertEquals(out.size().toLong(),ImageWire.size(body,req));assertFalse(body.toString().contains(Base64.getEncoder().encodeToString(data)))
            val blocks=JSONObject(out.toString("UTF-8")).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            assertEquals(req.messages[0].content,blocks.getJSONObject(0).getString("text"));assertTrue(blocks.getJSONObject(1).getString("text").contains("第1/2张"))
            assertArrayEquals(data,Base64.getDecoder().decode(blocks.getJSONObject(2).getJSONObject("image_url").getString("url").substringAfter(',')))
        }
    }
    @Test fun singleImageAndEncodedRequestLimitsAreSeparate(){
        val original=ByteArray(20_000_000)
        val one=TextChatRequest(listOf(WireMessage("user","图",images=listOf(WireImage("image/jpeg",original)))))
        assertTrue(ImageWire.fits(one))
        val two=one.copy(messages=listOf(one.messages[0].copy(images=listOf(WireImage("image/jpeg",original),WireImage("image/jpeg",original)))))
        assertFalse(ImageWire.fits(two));assertEquals(2,two.messages.single().images.size)
        val escaping=one.copy(messages=listOf(one.messages[0].copy(content="\u0000".repeat(1_200_000))))
        assertFalse(ImageWire.fits(escaping))
        assertFalse(ImageWire.fits(one.copy(messages=listOf(one.messages[0].copy(images=listOf(WireImage("image/heic",byteArrayOf(1))))))))
    }
    @Test fun toolsAndContinuationRemainInTheStreamedProtocolButNotDiagnostics(){
        val req=TextChatRequest(listOf(WireMessage("assistant","",toolCalls=listOf(ChatToolCall("id","lookup","{\"x\":1}"))),WireMessage("tool","结果",toolCallId="id",name="lookup"),WireMessage("user","继续")),tools=listOf(mapOf("type" to "function","function" to mapOf("name" to "lookup"))))
        val out=ByteArrayOutputStream();val body=ImageWire.body(config,req,true);ImageWire.write(body,req,out)
        val parsed=JSONObject(out.toString("UTF-8"));assertTrue(parsed.getBoolean("stream"));assertEquals("id",parsed.getJSONArray("messages").getJSONObject(1).getString("tool_call_id"));assertEquals("auto",parsed.getString("tool_choice"));assertEquals(out.size().toLong(),ImageWire.size(body,req))
        assertTrue(parsed.getJSONArray("messages").getJSONObject(2).get("content") is String)
    }
}
