package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** Prepare the entire selected representation before any request; no batch or frame-count quota. */
internal class VideoDelivery private constructor(private val root:File):Closeable {
    data class Original(val ref:ChatImageRef,val source:ImageSource,val info:ChatVideos.Info)
    data class Frame(val file:File,val atMillis:Long,val source:Original)
    val originals=linkedMapOf<ChatImageRef,Original>()
    val frames=linkedMapOf<ChatImageRef,List<Frame>>()
    override fun close(){root.listFiles()?.forEach{it.delete()};root.delete()}
    companion object {
        fun prepare(context:Context,refs:List<ChatImageRef>,extract:Boolean,read:(ChatImageRef)->ImageSource,validateCapacity:(List<ChatVideos.Info>)->Unit,valid:()->Unit):VideoDelivery {
            val parent=ImageDisk.root(context.cacheDir)
            val result=VideoDelivery(Files.createTempDirectory(parent.toPath(),"video-send-").toFile())
            try{
                refs.forEach{ref->
                    valid();val source=read(ref);val info=ChatVideos.inspect(requireNotNull(source.file))
                    check(source.hash()==ref.hash&&info.mime==ref.mime){"视频来源已变化或文件已损坏"}
                    result.originals[ref]=Original(ref,source,info)
                }
                validateCapacity(result.originals.values.map{it.info})
                if(extract)result.originals.forEach{(ref,original)->
                    val frames=mutableListOf<Frame>()
                    ChatVideos.withRetriever(requireNotNull(original.source.file)){retriever->
                        for(at in VideoPolicy.frameTimes(original.info.durationMillis)){
                            valid();val pixels=Math.multiplyExact(original.info.width.toLong(),original.info.height.toLong())
                            require(pixels<=Runtime.getRuntime().maxMemory()/8){"本机内存不足以读取完整视频画面，未缩图、跳帧或发送"}
                            ImageDisk.space(result.root,Math.multiplyExact(pixels,4))
                            val bitmap=requireNotNull(retriever.getFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST)){"无法读取视频 ${at/1000} 秒的画面，未跳过或发送"}
                            try{
                                val target=File(result.root,"${result.frames.size}-${frames.size}.png")
                                target.outputStream().use{out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)){"视频画面写入失败，未发送"}}
                                require(target.length() in 1..ImagePolicy.ORIGINAL_LIMIT.toLong()){"单帧画面超过可发送图片大小，未缩图、跳帧或发送"}
                                frames+=Frame(target,at,original)
                            }finally{bitmap.recycle()}
                        }
                    }
                    check(frames.size.toLong()==original.info.frameCount){"视频画面不完整，未发送"}
                    result.frames[ref]=frames
                }
                valid();return result
            }catch(e:Throwable){result.close();if(e is OutOfMemoryError)throw IllegalArgumentException("本机内存不足，无法准备完整视频画面；未跳帧或发送",e);throw e}
        }
    }
}
