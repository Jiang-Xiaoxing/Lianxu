package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.nio.file.Files

/** A complete, bounded plan on private temporary files before any paid network request. */
internal class ImageParts private constructor(private val root:File):Closeable {
    data class Part(val source:ChatImageRef,val file:File,val mime:String,val label:String,val overview:Boolean=false){
        fun wire()=WireImage.file(mime,file,label)
    }
    val parts=mutableListOf<Part>()
    private var total=0L
    override fun close(){root.listFiles()?.forEach{it.delete()};root.delete()}
    private fun add(ref:ChatImageRef,mime:String,label:String,overview:Boolean=false,write:(java.io.OutputStream)->Unit){
        require(parts.size<ImagePolicy.MAX_PARTS){"本轮超过64张图片/局部/总览，未发送"}
        val file=File(root,"${parts.size}.bin")
        file.outputStream().use{target->val bounded=object:FilterOutputStream(target){
            var count=0L
            override fun write(b:Int){require(++count<=ImagePolicy.ORIGINAL_LIMIT);out.write(b)}
            override fun write(b:ByteArray,off:Int,len:Int){require(count+len<=ImagePolicy.ORIGINAL_LIMIT){"单张图片超过20,000,000字节，未降质或漏图"};out.write(b,off,len);count+=len}
        };write(bounded)}
        total+=file.length();require(total<=ImagePolicy.MAX_PREPARED){"本轮图片派生总量超过96 MiB的本机上限，未发送或漏图"}
        parts.add(Part(ref,file,mime,label,overview))
    }
    companion object {
        fun prepare(context:Context,refs:List<ChatImageRef>,read:(ChatImageRef)->ByteArray,valid:()->Unit):ImageParts {
            val parent=File(context.cacheDir,"chat-image-wire").apply{check(mkdirs()||isDirectory)}
            val plan=ImageParts(Files.createTempDirectory(parent.toPath(),"attempt-").toFile())
            try {
                for(ref in refs){valid();val raw=read(ref);val info=ChatImages.inspect(raw)
                    val origin="来源消息${ref.messageId}；保存文件SHA256=${ref.hash}；原始像素${info.width}×${info.height}；EXIF方向${info.orientation}；${if(ref.storageKind=="original")"原件"else"历史压缩副本，不能恢复原件"}"
                    if(ImagePolicy.original(raw.size.toLong()))plan.add(ref,ref.mime,"$origin；保存文件完整发送、字节未修改（包含现存元数据）"){it.write(raw)}
                    else {
                        require(info.mime!="image/png"||raw.size>24&&(raw[24].toInt() and 255)<=8){"超过20 MB的16位PNG暂不能无损切片；原件仍保留，未降为8位或发送"}
                        val regions=ImagePolicy.regions(info.displayWidth,info.displayHeight)
                        val decoder=BitmapRegionDecoder.newInstance(raw,0,raw.size,false)?:error("此原图无法无损区域解码，未缩小或发送")
                        try{regions.forEachIndexed{index,r->valid()
                            val source=ImagePixels.sourceRegion(info,android.graphics.RectF(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat()))
                            val tile=decoder.decodeRegion(source,BitmapFactory.Options().apply{inSampleSize=1;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("图片局部解码失败，未遗漏继续")
                            check(tile.width==source.width()&&tile.height==source.height()){"区域解码改变了分辨率，未发送"}
                            val display=ImagePixels.oriented(tile,info.orientation)
                            try{check(display.width==r.width&&display.height==r.height);plan.add(ref,"image/png","$origin；局部${index+1}/${regions.size}；原始坐标[${source.left},${source.top},${source.right},${source.bottom})；正向坐标[${r.left},${r.top},${r.right},${r.bottom})；正向行优先（从上到下、每行从左到右），相邻轴重叠${ImagePolicy.OVERLAP}像素；无损PNG，已按EXIF转正"){check(display.compress(Bitmap.CompressFormat.PNG,100,it)){"局部编码失败"}}}finally{display.recycle()}
                        }}finally{decoder.recycle()}
                        valid();val overview=ImagePixels.preview(raw,1024)
                        try{plan.add(ref,"image/png","$origin；最后一张：缩小全图总览，仅供定位；细节以此前全部${regions.size}张原分辨率局部为准",true){check(overview.compress(Bitmap.CompressFormat.PNG,100,it)){"总览编码失败"}}}finally{overview.recycle()}
                    }
                }
                valid();return plan
            }catch(e:Throwable){plan.close();if(e is OutOfMemoryError)throw IllegalArgumentException("本机内存不足，原图未缩小，也未发送不完整图片",e);throw e}
        }
    }
}
