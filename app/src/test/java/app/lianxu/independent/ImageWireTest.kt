package app.lianxu.independent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ImageWireTest {
    @Test fun textOnlyProtocolRemainsAString(){assertEquals("原话",ImageWire.content(WireMessage("user","原话")))}
    @Test fun imageOnlyAndCaptionEncodeExactOwnedBytes(){
        val bytes=byteArrayOf(1,2,3,4,5)
        for(caption in listOf("","这是什么花？")){
            val content=ImageWire.content(WireMessage("user",caption,images=listOf(WireImage("image/jpeg",bytes)))) as JSONArray
            val image=content.getJSONObject(content.length()-1)
            assertArrayEquals(bytes,Base64.getDecoder().decode(image.getJSONObject("image_url").getString("url").substringAfter(',')))
            assertEquals(if(caption.isEmpty())1 else 2,content.length())
        }
    }
    @Test fun diagnosticsAndToStringNeverContainPixels(){
        val bytes=ByteArray(256){(it*7).toByte()};val encoded=Base64.getEncoder().encodeToString(bytes)
        val wire=WireMessage("user","合成问题",images=listOf(WireImage("image/jpeg",bytes)))
        assertFalse(ImageWire.content(wire,true).toString().contains(encoded))
        assertFalse(wire.toString().contains(encoded))
        val error="HTTP 415 {\"echo\":\"data:image/jpeg;base64,$encoded\"}"
        val cleaned=DiagnosticRedactor().text(error)
        assertTrue(cleaned.contains("HTTP 415"));assertFalse(cleaned.contains(encoded));assertFalse(cleaned.contains("data:image"))
        for(raw in listOf("data:image\\/jpeg;base64,PRIVATEPIXELS","data:image\\u002fjpeg;base64,PRIVATEPIXELS","data\\u003aimage/jpeg;base64,PRIVATEPIXELS"))assertFalse(DiagnosticRedactor().text(raw).contains("PRIVATEPIXELS"))
    }
    @Test fun rejectsOversizeBeforeEncodingAndNeverSilentlyDropsPictures(){
        val request=TextChatRequest(listOf(WireMessage("user","",images=listOf(WireImage("image/jpeg",ByteArray(ChatImages.MAX_IMAGE+1))))))
        assertFalse(ImageWire.fits(request));assertEquals(1,request.messages.single().images.size)
    }
    @Test fun emptyUserTextRequiresAnActualPicture(){
        assertTrue(runCatching{WireMessage("user","")}.isFailure)
        assertTrue(runCatching{WireMessage("assistant","",images=listOf(WireImage("image/jpeg",byteArrayOf(1))))}.isFailure)
    }
    @Test fun numberedImageEvidenceKeepsAuxiliaryOriginSeparateFromUserWords(){
        val origin=ImageEvidence(SourceRef("r",1,1),"a".repeat(64),"obs")
        val source=SourceDialogue("t",SourceRef("r",3,1),Message(3,"r","右上角是什么？",true,100),null,"Asia/Shanghai",listOf(ImageObservation(origin,"蓝色三角形和数字7")))
        val numbering=MemoryNumberedReferences(listOf(source),emptyList())
        assertEquals("user",numbering.sourceRows().getJSONObject(0).getString("side"))
        assertEquals("image",numbering.sourceRows().getJSONObject(1).getString("side"))
        val normalized=numbering.normalize(org.json.JSONObject().put("evidence",JSONArray().put("S2")))
        assertEquals("obs",normalized.getJSONArray("evidence").getJSONObject(0).getString("observation_id"))
        assertEquals(source,SourceDialogue.from(source.json()))
    }
}
