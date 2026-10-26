package app.lianxu.independent

import android.content.ContentValues

/** Minimal persistent owned resources for portable backups, not attachment sending or image understanding. */
internal class RoleAssets(private val repo:ProcessingRepository){
    companion object {
        // Keep each BLOB window comfortably below Android's typical 2 MiB CursorWindow.
        internal fun read(db:android.database.sqlite.SQLiteDatabase,id:String,path:String,size:Int):ByteArray{
            require(size in 0..BackupArchive.MAX_FILE){"单个资源超过16 MiB"}
            val bytes=ByteArray(size);var offset=0
            while(offset<size){val count=minOf(512*1024,size-offset)
                val chunk=db.rawQuery("SELECT substr(data,?,?) FROM role_assets WHERE friend_id=? AND path=?",arrayOf((offset+1).toString(),count.toString(),id,path)).use{check(it.moveToFirst());it.getBlob(0)}
                check(chunk.size==count){"资源读取不完整"};chunk.copyInto(bytes,offset);offset+=count
            }
            return bytes
        }
    }
    data class Asset(val path:String,val kind:String,val mime:String,val bytes:ByteArray){override fun toString()="RoleAsset($kind, ${bytes.size} bytes)"}
    suspend fun put(id:String,value:Asset)=repo.tx{db->
        require(RoleArchive.entryName(value.path)==value.path&&value.path.length<=240)
        require(value.kind in setOf("avatar","attachment")&&value.mime.length in 1..100&&value.bytes.size<=BackupArchive.MAX_FILE)
        db.insertWithOnConflict("role_assets",null,ContentValues().apply{put("friend_id",id);put("path",value.path);put("kind",value.kind);put("mime",value.mime);put("data",value.bytes)},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }
    suspend fun list(id:String)=repo.read{db->db.rawQuery("SELECT path,kind,mime,length(data) FROM role_assets WHERE friend_id=? ORDER BY path",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(Asset(c.getString(0),c.getString(1),c.getString(2),read(db,id,c.getString(0),c.getInt(3))))}}}
}
