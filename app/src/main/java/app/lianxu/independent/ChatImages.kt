package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** Owned, metadata-free still image. Never retain the picker's URI or original filename. */
class PendingChatImage internal constructor(val mime:String,internal val bytes:ByteArray,val width:Int,val height:Int) {
    val hash:String=RoleArchive.hash(bytes)
    override fun toString()="PendingChatImage($mime,${bytes.size} bytes)"
}
data class ChatImageRef(val friendId:String,val messageId:Long,val path:String,val mime:String,val hash:String,val width:Int,val height:Int,val revision:Long=1)
class WireImage(val mime:String,internal val bytes:ByteArray) {
    override fun toString()="WireImage($mime,${bytes.size} bytes)"
}

internal object ChatImages {
    const val MAX_INPUT=24*1024*1024
    const val MAX_IMAGE=512*1024
    const val MAX_REQUEST_IMAGES=1024*1024
    const val SWITCH="aux-vision-enabled"
    const val SWITCH_REVISION="aux-vision-revision"
    fun create(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_images(friend_id TEXT NOT NULL REFERENCES friends(id),message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,path TEXT NOT NULL,mime TEXT NOT NULL,hash TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,revision INTEGER NOT NULL,FOREIGN KEY(friend_id,path) REFERENCES role_assets(friend_id,path))")
        db.execSQL("CREATE TABLE IF NOT EXISTS image_observations(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),message_id INTEGER NOT NULL REFERENCES chat_images(message_id) ON DELETE CASCADE,question_message_id INTEGER NOT NULL REFERENCES messages(id),question_revision INTEGER NOT NULL,source_revision INTEGER NOT NULL,image_hash TEXT NOT NULL,model_id TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL)")
    }
    fun prepare(input:InputStream):PendingChatImage {
        val raw=input.use{stream->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=MAX_INPUT){"图片超过24 MiB，请选择较小图片"};out.write(buffer,0,n)};out.toByteArray()}
        require(raw.isNotEmpty()){"无法识别这张图片"}
        val info=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(raw,0,raw.size,info)
        require(info.outWidth in 1..32768&&info.outHeight in 1..32768&&info.outWidth.toLong()*info.outHeight<=160_000_000){"图片尺寸过大或已损坏"}
        require(info.outMimeType in setOf("image/jpeg","image/png","image/webp","image/heic","image/heif","image/gif")){"无法识别这张图片格式"}
        var sample=1;while(maxOf(info.outWidth,info.outHeight)/sample>2048)sample*=2
        val decoded=BitmapFactory.decodeByteArray(raw,0,raw.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("无法读取这张图片")
        val orientation=runCatching{ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix=Matrix().apply{when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)}}
        var bitmap=if(matrix.isIdentity)decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also{if(it!==decoded)decoded.recycle()}
        try {
            var side=1600
            while(true){
                if(maxOf(bitmap.width,bitmap.height)>side){val ratio=side.toFloat()/maxOf(bitmap.width,bitmap.height);val next=Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*ratio).toInt()),maxOf(1,(bitmap.height*ratio).toInt()),true);if(next!==bitmap)bitmap.recycle();bitmap=next}
                val output=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.JPEG,85,output)){"图片转换失败"}
                if(output.size()<=MAX_IMAGE)return PendingChatImage("image/jpeg",output.toByteArray(),bitmap.width,bitmap.height)
                side=(side*.75).toInt();require(side>=256){"图片压缩后仍然过大"}
            }
        }finally{bitmap.recycle();raw.fill(0)}
    }
    fun put(db:SQLiteDatabase,friendId:String,messageId:Long,image:PendingChatImage){
        require(image.bytes.size in 1..MAX_IMAGE&&image.hash==RoleArchive.hash(image.bytes))
        val path="chat-images/${UUID.randomUUID()}.jpg"
        db.insertOrThrow("role_assets",null,ContentValues().apply{put("friend_id",friendId);put("path",path);put("kind","attachment");put("mime",image.mime);put("data",image.bytes)})
        db.insertOrThrow("chat_images",null,ContentValues().apply{put("friend_id",friendId);put("message_id",messageId);put("path",path);put("mime",image.mime);put("hash",image.hash);put("width",image.width);put("height",image.height);put("revision",1)})
    }
    fun ref(db:SQLiteDatabase,messageId:Long):ChatImageRef?=db.rawQuery("SELECT friend_id,message_id,path,mime,hash,width,height,revision FROM chat_images WHERE message_id=?",arrayOf(messageId.toString())).use{c->if(!c.moveToFirst())null else ChatImageRef(c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getInt(6),c.getLong(7))}
    fun read(db:SQLiteDatabase,ref:ChatImageRef):ByteArray {
        check(ref(db,ref.messageId)==ref){"图片来源已变化"}
        val size=db.rawQuery("SELECT length(data) FROM role_assets WHERE friend_id=? AND path=?",arrayOf(ref.friendId,ref.path)).use{check(it.moveToFirst()){ "图片文件已不存在" };it.getInt(0)}
        require(size in 1..MAX_IMAGE){"图片文件大小无效"}
        return RoleAssets.read(db,ref.friendId,ref.path,size).also{check(RoleArchive.hash(it)==ref.hash){"图片文件已损坏"}}
    }
    fun setting(db:SQLiteDatabase,key:String)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0)else ""}
    fun enabled(db:SQLiteDatabase)=setting(db,SWITCH)=="1"
    fun routeStamp(db:SQLiteDatabase)=setting(db,SWITCH)+":"+setting(db,SWITCH_REVISION)
    fun setEnabled(db:SQLiteDatabase,value:Boolean){
        db.beginTransaction();try{for((k,v) in listOf(SWITCH to if(value)"1"else"0",SWITCH_REVISION to UUID.randomUUID().toString()))db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",k);put("value",v)},SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    fun redact(value:String)=value.replace(Regex("""data(?::|\\u003a)image(?:\\*/|\\u002f)[^\s"'<>]*""",RegexOption.IGNORE_CASE),"[图片数据已省略]")
}
