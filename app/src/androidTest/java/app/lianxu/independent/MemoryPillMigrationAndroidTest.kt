package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

/** v14 老库真跑升级：自动补胶囊表与 saved 列，原有消息、操作行和已有胶囊逐字段不动。 */
class MemoryPillMigrationAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String

    @Before fun prepare(){name="pill-migrate-${System.nanoTime()}.db"}
    @After fun close(){context.deleteDatabase(name)}

    /** 14 版真实形状：memory_operations 没有 saved，也还没有 chat_memory_pills。 */
    private fun legacyDatabase(version:Int,pillTable:Boolean=false,seedPill:Boolean=false){
        val db=context.openOrCreateDatabase(name,0,null)
        db.execSQL("CREATE TABLE friends(id TEXT PRIMARY KEY, name TEXT NOT NULL, bio TEXT NOT NULL, prompt TEXT NOT NULL, tone INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, friend_id TEXT NOT NULL REFERENCES friends(id), body TEXT NOT NULL, mine INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE memory_operations(batch_id TEXT NOT NULL,op_key TEXT NOT NULL,json TEXT NOT NULL,digest TEXT NOT NULL,result_id TEXT,result_version INTEGER,PRIMARY KEY(batch_id,op_key))")
        // v13/v14 already had this table; v16 adds failure columns to it. Keep its real old shape
        // so a pill migration fixture does not fail on an unrelated, fictitiously missing table.
        db.execSQL("CREATE TABLE companion_opportunities(id TEXT PRIMARY KEY,role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,source_id INTEGER NOT NULL,revision INTEGER NOT NULL,epoch INTEGER NOT NULL,policy_version INTEGER NOT NULL,not_before INTEGER NOT NULL,expires_at INTEGER NOT NULL,reason TEXT NOT NULL,state TEXT NOT NULL,attempt INTEGER NOT NULL DEFAULT 0,owner TEXT NOT NULL DEFAULT '',FOREIGN KEY(role,source_id) REFERENCES messages(friend_id,id) ON DELETE CASCADE)")
        if(pillTable)ChatMemoryPills.createSchema(db)
        db.execSQL("INSERT INTO friends VALUES('a','A','Synthetic','',0)")
        db.execSQL("INSERT INTO messages(friend_id,body,mine,created) VALUES('a','旧消息',0,1)")
        db.execSQL("INSERT INTO memory_operations(batch_id,op_key,json,digest,result_id,result_version) VALUES('old','k1','{\"op\":\"ADD\",\"text\":\"旧事实\"}','d',NULL,0)")
        if(seedPill)db.execSQL("INSERT INTO chat_memory_pills(batch_id,friend_id,after_message_id,saved_count,created) VALUES('kept','a',1,3,2)")
        db.version=version
        db.close()
    }
    private fun columns(db:android.database.sqlite.SQLiteDatabase,table:String)=db.rawQuery("PRAGMA table_info($table)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
    private fun Store.hasPillTable()=readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='chat_memory_pills'",null).use{it.moveToFirst()}
    private fun messageCount(db:android.database.sqlite.SQLiteDatabase)=db.rawQuery("SELECT COUNT(*) FROM messages WHERE friend_id='a'",null).use{it.moveToFirst();it.getInt(0)}
    private fun message(db:android.database.sqlite.SQLiteDatabase)=db.rawQuery("SELECT id,body,mine,created FROM messages WHERE friend_id='a'",null)
        .use{assertTrue(it.moveToFirst());listOf(it.getLong(0),it.getString(1),it.getInt(2),it.getLong(3))}
    private fun operation(db:android.database.sqlite.SQLiteDatabase)=db.rawQuery("SELECT batch_id,op_key,json,digest,result_id,result_version,saved FROM memory_operations WHERE batch_id='old'",null)
        .use{assertTrue(it.moveToFirst());listOf(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getString(4),it.getInt(5),it.getInt(6))}
    private fun pills(db:android.database.sqlite.SQLiteDatabase)=ChatMemoryPills.read(db,"a").map{listOf(it.batchId,it.friendId,it.afterMessageId,it.savedCount,it.created)}

    @Test fun aVersion14DatabaseGetsThePillTableAndSavedColumnWithoutLosingRows(){
        legacyDatabase(14)
        val store=Store(context,name)
        try{
            val db=store.writableDatabase
            assertTrue("升级后必须有胶囊表",store.hasPillTable())
            assertTrue("升级后 memory_operations 必须有 saved 列","saved" in columns(db,"memory_operations"))
            assertEquals("消息不能多也不能少",1,messageCount(db))
            assertEquals("消息必须原样保留",listOf(1L,"旧消息",0,1L),message(db))
            assertEquals("操作行必须原样保留，只多一个默认 0 的 saved",
                listOf("old","k1","{\"op\":\"ADD\",\"text\":\"旧事实\"}","d",null,0,0),operation(db))
            assertTrue("老库没有历史胶囊，不能凭空补造",pills(db).isEmpty())
            ChatMemoryPills.record(db,"a","new-batch",1)
            assertEquals("已整理 1 条记忆",ChatMemoryPills.read(db,"a").single().text)
        }finally{store.close()}
    }

    @Test fun aVersion13DatabaseGetsBothPillTableAndSavedColumnWithoutLosingRows(){
        legacyDatabase(13)
        val store=Store(context,name)
        try{
            val db=store.writableDatabase
            assertTrue(store.hasPillTable())
            assertTrue("saved" in columns(db,"memory_operations"))
            assertEquals(1,messageCount(db))
            assertEquals(listOf(1L,"旧消息",0,1L),message(db))
            assertEquals(listOf("old","k1","{\"op\":\"ADD\",\"text\":\"旧事实\"}","d",null,0,0),operation(db))
            assertTrue("老库没有历史胶囊，不能凭空补造",pills(db).isEmpty())
        }finally{store.close()}
    }

    @Test fun aVersion14DatabaseThatAlreadyHasThePillTableKeepsItsPillsAndUpgradesIdempotently(){
        legacyDatabase(14,pillTable=true,seedPill=true)
        val store=Store(context,name)
        try{
            val db=store.writableDatabase
            assertTrue(store.hasPillTable())
            assertTrue("saved" in columns(db,"memory_operations"))
            assertEquals("升级前已有的胶囊必须逐字段原样保留",listOf(listOf("kept","a",1L,3,2L)),pills(db))
            assertEquals("已整理 3 条记忆",ChatMemoryPills.read(db,"a").single().text)
            assertEquals(1,messageCount(db))
            assertEquals("已有胶囊的库升级后消息也必须原样保留",listOf(1L,"旧消息",0,1L),message(db))
        }finally{store.close()}
    }
}
