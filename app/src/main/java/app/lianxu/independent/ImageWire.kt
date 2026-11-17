package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal object ImageWire {
    fun content(message:WireMessage,diagnostic:Boolean=false):Any {
        require(message.imageRefs.isEmpty()){"图片尚未建立发送快照"}
        if(message.images.isEmpty())return message.content
        return JSONArray().apply{
            if(message.content.isNotBlank())put(JSONObject().put("type","text").put("text",message.content))
            message.images.forEach{image->put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",if(diagnostic)"[${image.mime}; ${image.bytes.size} bytes; 图片数据已省略]"else "data:${image.mime};base64,"+Base64.getEncoder().encodeToString(image.bytes))))}
        }
    }
    fun fits(request:TextChatRequest):Boolean {
        val images=request.messages.flatMap{it.images}
        if(images.isEmpty())return true
        return images.all{it.bytes.size in 1..ChatImages.MAX_IMAGE&&it.mime in setOf("image/jpeg","image/png","image/webp")}&&
            images.sumOf{it.bytes.size.toLong()}<=ChatImages.MAX_REQUEST_IMAGES&&
            images.sumOf{4L*((it.bytes.size+2)/3)+128}+request.messages.sumOf{it.content.toByteArray(Charsets.UTF_8).size.toLong()*6+256}+org.json.JSONArray(request.tools).toString().toByteArray().size<2*1024*1024
    }
}
