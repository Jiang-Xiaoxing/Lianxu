package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

internal object OriginalImageFixtures {
    fun picture(width:Int=120,height:Int=100,format:Bitmap.CompressFormat=Bitmap.CompressFormat.PNG):ByteArray {
        val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val c=Canvas(b)
        c.drawColor(Color.TRANSPARENT);val p=Paint().apply{color=Color.rgb(110,160,90)};c.drawRect(0f,0f,width*.8f,height.toFloat(),p)
        p.color=Color.BLUE;c.drawRect(width*.8f,0f,width.toFloat(),height*.1f,p)
        p.color=Color.RED;c.drawRect(0f,height-12f,width.toFloat(),height.toFloat(),p)
        return ByteArrayOutputStream().also{check(b.compress(format,95,it));b.recycle()}.toByteArray()
    }
    /** Valid APP15 segments: synthetic padding to test exact byte boundaries without a huge decoded bitmap. */
    fun paddedJpeg(size:Int,width:Int=120,height:Int=100):ByteArray {
        return padJpeg(picture(width,height,Bitmap.CompressFormat.JPEG),size)
    }
    fun padJpeg(source:ByteArray,size:Int):ByteArray {
        require(size>=source.size+4)
        val out=ByteArray(size);source.copyInto(out,0,0,2);var at=2;var remaining=size-source.size
        while(remaining>0){var total=minOf(65537,remaining);if(remaining-total in 1..3)total-=4;require(total>=4)
            out[at]=255.toByte();out[at+1]=239.toByte();out[at+2]=((total-2) ushr 8).toByte();out[at+3]=(total-2).toByte();at+=total;remaining-=total
        };source.copyInto(out,at,2);return out
    }
    fun noisyTransparentPng():ByteArray {
        val w=2800;val h=2600;val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val row=IntArray(w);var seed=123456789
        for(y in 0 until h){for(x in 0 until w){seed=seed xor (seed shl 13);seed=seed xor (seed ushr 17);seed=seed xor (seed shl 5);row[x]=if(x%17==0)Color.TRANSPARENT else seed};b.setPixels(row,0,w,0,y,w,1)}
        return ByteArrayOutputStream().also{check(b.compress(Bitmap.CompressFormat.PNG,100,it));b.recycle()}.toByteArray()
    }
    fun png16(padding:Int=0,animated:Boolean=false):ByteArray {
        val output=ByteArrayOutputStream();val data=java.io.DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type:String,bytes:ByteArray){val name=type.toByteArray(Charsets.US_ASCII);data.writeInt(bytes.size);data.write(name);data.write(bytes);val crc=java.util.zip.CRC32();crc.update(name);crc.update(bytes);data.writeInt(crc.value.toInt())}
        val header=ByteArrayOutputStream();java.io.DataOutputStream(header).apply{writeInt(2);writeInt(1);writeByte(16);writeByte(6);writeByte(0);writeByte(0);writeByte(0)};chunk("IHDR",header.toByteArray())
        if(animated)chunk("acTL",byteArrayOf(0,0,0,1,0,0,0,0))
        val pixels=byteArrayOf(0,255.toByte(),255.toByte(),0,0,0,0,255.toByte(),255.toByte(),0,0,0,0,255.toByte(),255.toByte(),128.toByte(),0)
        val compressed=ByteArrayOutputStream();java.util.zip.DeflaterOutputStream(compressed).use{it.write(pixels)};chunk("IDAT",compressed.toByteArray())
        if(padding>0)chunk("paDd",ByteArray(padding))
        chunk("IEND",byteArrayOf());return output.toByteArray()
    }
}
