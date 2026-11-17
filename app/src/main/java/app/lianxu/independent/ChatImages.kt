package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** Owned original bytes. EXIF and other embedded metadata are deliberately preserved. */
class PendingChatImage internal constructor(val mime:String,internal val bytes:ByteArray,val width:Int,val height:Int) {
    val hash:String=RoleArchive.hash(bytes)
    override fun toString()="PendingChatImage($mime,${bytes.size} bytes)"
}
data class ChatImageRef(val friendId:String,val messageId:Long,val path:String,val mime:String,val hash:String,val width:Int,val height:Int,val revision:Long=1,val storageKind:String="legacy-normalized")
class WireImage private constructor(val mime:String,private val data:ByteArray?,private val file:java.io.File?,val description:String) {
    constructor(mime:String,bytes:ByteArray,description:String=""):this(mime,bytes,null,description)
    internal val size:Long get()=data?.size?.toLong()?:requireNotNull(file).length()
    internal val bytes:ByteArray get()=data?:requireNotNull(file).readBytes()
    internal fun input():InputStream=data?.inputStream()?:requireNotNull(file).inputStream()
    internal companion object { fun file(mime:String,file:java.io.File,description:String)=WireImage(mime,null,file,description) }
    override fun toString()="WireImage($mime,$size bytes)"
}

internal object ChatImages {
    const val MAX_INPUT=ImagePolicy.INPUT_LIMIT
    const val MAX_IMAGE=ImagePolicy.INPUT_LIMIT
    const val SWITCH="aux-vision-enabled"
    const val SWITCH_REVISION="aux-vision-revision"
    fun create(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_images(friend_id TEXT NOT NULL REFERENCES friends(id),message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,path TEXT NOT NULL,mime TEXT NOT NULL,hash TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,revision INTEGER NOT NULL,storage_kind TEXT NOT NULL DEFAULT 'legacy-normalized',FOREIGN KEY(friend_id,path) REFERENCES role_assets(friend_id,path))")
        db.execSQL("CREATE TABLE IF NOT EXISTS image_observations(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),message_id INTEGER NOT NULL REFERENCES chat_images(message_id) ON DELETE CASCADE,question_message_id INTEGER NOT NULL REFERENCES messages(id),question_revision INTEGER NOT NULL,source_revision INTEGER NOT NULL,image_hash TEXT NOT NULL,model_id TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL)")
    }
    fun migrateOriginals(db:SQLiteDatabase){
        create(db)
        val columns=db.rawQuery("PRAGMA table_info(chat_images)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
        if("storage_kind" !in columns)db.execSQL("ALTER TABLE chat_images ADD COLUMN storage_kind TEXT NOT NULL DEFAULT 'legacy-normalized'")
    }
    fun prepare(input:InputStream):PendingChatImage {
        try {
            val raw=input.use{stream->val out=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size().toLong()+n<=MAX_INPUT){"原文件超过40,000,000字节的本机处理上限，未缩小或发送"};out.write(buffer,0,n)};out.toByteArray()}
            val info=inspect(raw)
            // Validate actual decoding without allocating a full-resolution long image.
            ImagePixels.preview(raw,1024).recycle()
            return PendingChatImage(info.mime,raw,info.width,info.height)
        } catch(e:OutOfMemoryError){throw IllegalArgumentException("本机内存不足，原图没有缩小或发送",e)}
    }
    data class Info(val mime:String,val width:Int,val height:Int,val orientation:Int){
        val displayWidth get()=if(orientation in 5..8)height else width
        val displayHeight get()=if(orientation in 5..8)width else height
    }
    fun inspect(raw:ByteArray):Info {
        require(raw.size in 1..MAX_INPUT){"图片文件大小无效"}
        val info=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(raw,0,raw.size,info)
        require(info.outMimeType in ImagePolicy.mimes){"仅支持静态JPEG、PNG、WebP原图；其他格式不会自动转换"}
        require(info.outWidth in 1..ImagePolicy.MAX_SIDE&&info.outHeight in 1..ImagePolicy.MAX_SIDE&&info.outWidth.toLong()*info.outHeight<=ImagePolicy.MAX_PIXELS){"图片尺寸过大或已损坏"}
        require(!ImagePixels.animated(raw,info.outMimeType)){"暂不支持动态图片，不会只取首帧发送"}
        val orientation=runCatching{ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1).takeIf{it in 1..8}?:1
        return Info(info.outMimeType,info.outWidth,info.outHeight,orientation)
    }
    fun put(db:SQLiteDatabase,friendId:String,messageId:Long,image:PendingChatImage){
        require(image.bytes.size in 1..MAX_IMAGE&&image.hash==RoleArchive.hash(image.bytes))
        val path="chat-images/${UUID.randomUUID()}."+when(image.mime){"image/png"->"png";"image/webp"->"webp";else->"jpg"}
        db.insertOrThrow("role_assets",null,ContentValues().apply{put("friend_id",friendId);put("path",path);put("kind","attachment");put("mime",image.mime);put("data",image.bytes)})
        db.insertOrThrow("chat_images",null,ContentValues().apply{put("friend_id",friendId);put("message_id",messageId);put("path",path);put("mime",image.mime);put("hash",image.hash);put("width",image.width);put("height",image.height);put("revision",1);put("storage_kind","original")})
    }
    fun ref(db:SQLiteDatabase,messageId:Long):ChatImageRef?=db.rawQuery("SELECT friend_id,message_id,path,mime,hash,width,height,revision,storage_kind FROM chat_images WHERE message_id=?",arrayOf(messageId.toString())).use{c->if(!c.moveToFirst())null else ChatImageRef(c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getInt(6),c.getLong(7),c.getString(8))}
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
