package app.lianxu.independent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Own app-private database. Primary key on friend is also the unique conversation key. */
class Store(context: Context, name: String = AppServices.databaseName) : SQLiteOpenHelper(context, name, null, 5) {
    internal val memorySummary=MemorySummaryGuard()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE friends(id TEXT PRIMARY KEY, name TEXT NOT NULL, bio TEXT NOT NULL, prompt TEXT NOT NULL, tone INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, friend_id TEXT NOT NULL REFERENCES friends(id), body TEXT NOT NULL, mine INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX messages_friend ON messages(friend_id,id)")
        db.execSQL("CREATE TABLE preferences(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        ChatLedger.createSchema(db)
        ProcessingSchema.create(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion<2)ChatLedger.createSchema(db);if(oldVersion<5)ProcessingSchema.create(db) }
    private fun insertFriend(db: SQLiteDatabase, f: Friend): Boolean = db.insertWithOnConflict("friends", null, ContentValues().apply {
        put("id", f.id); put("name", f.name.trim()); put("bio", f.bio.trim()); put("prompt", f.prompt); put("tone", f.tone)
    }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    private fun insertMessage(db: SQLiteDatabase, id: String, text: String, mine: Boolean) {
        db.insertOrThrow("messages", null, ContentValues().apply { put("friend_id", id); put("body", text); put("mine", if (mine) 1 else 0); put("created", System.currentTimeMillis()) })
    }
    suspend fun friends(): List<Friend> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT id,name,bio,prompt,tone FROM friends ORDER BY rowid", null).use { c -> buildList { while (c.moveToNext()) add(Friend(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4))) } }
    }
    suspend fun messages(): List<Message> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT m.id,m.friend_id,CASE WHEN COALESCE(s.deleted,0)=1 THEN '【已撤回】' ELSE m.body END,m.mine,m.created FROM messages m LEFT JOIN message_state s ON s.message_id=m.id ORDER BY m.id", null).use { c -> buildList { while (c.moveToNext()) add(Message(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getLong(4))) } }
    }
    suspend fun add(friends: List<Friend>, demoGreeting:Boolean=true) = withContext(Dispatchers.IO) {
        require(friends.all { it.id.isNotBlank() && it.name.isNotBlank() && it.bio.isNotBlank() })
        val db = writableDatabase; db.beginTransaction()
        try { friends.forEach { if (insertFriend(db,it)) { RoleExamplesRepository.initialize(db,it.id); if(demoGreeting) insertMessage(db,it.id,"【演示开场】你好，我是${it.name}。很高兴认识你。",false) } }; db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    suspend fun exchange(friend: Friend, input: String, reply: String) = withContext(Dispatchers.IO) {
        val db=writableDatabase; db.beginTransaction()
        try { insertMessage(db,friend.id,input,true); insertMessage(db,friend.id,reply,false); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    suspend fun preference(key: String, default: String): String = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else default }
    }
    internal suspend fun updateRolePrompts(id:String,change:(RolePrompts)->RolePrompts)=withContext(Dispatchers.IO){AppMutationGate.read{
        val db=writableDatabase;db.beginTransaction()
        try{
            require(db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(id)).use{it.moveToFirst()}){"角色已不存在，请返回列表。"}
            val key=RolePromptStore.key(id)
            val old=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())RolePromptStore.decode(it.getString(0)) else RolePrompts()}
            db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",RolePromptStore.encode(change(old)))},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }}
    internal fun persona(db:SQLiteDatabase):UserPersona {
        fun value(key:String)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0)else""}
        return UserPersona.decode(value(UserPersona.KEY),value("nickname"))
    }
    suspend fun persona()=withContext(Dispatchers.IO){AppMutationGate.read{persona(readableDatabase)}}
    suspend fun savePersona(value:UserPersona)=withContext(Dispatchers.IO){AppMutationGate.read{
        val p=value.normalized();val db=writableDatabase;db.beginTransaction()
        try{
            db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",UserPersona.KEY);put("value",p.encode())},SQLiteDatabase.CONFLICT_REPLACE)
            if(p.name.isBlank())db.delete("preferences","key=?",arrayOf("nickname"))else db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key","nickname");put("value",p.name)},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }}
    internal fun seedOpening(db:SQLiteDatabase,id:String,opening:String){
        if(opening.isNotBlank()&&!db.rawQuery("SELECT 1 FROM messages WHERE friend_id=? LIMIT 1",arrayOf(id)).use{it.moveToFirst()}){
            val name=db.rawQuery("SELECT name FROM friends WHERE id=?",arrayOf(id)).use{check(it.moveToFirst());it.getString(0)}
            insertMessage(db,id,UserPersona.expand(opening,name,persona(db)),false)
        }
    }
    internal suspend fun addConfigured(friend:Friend,prompts:RolePrompts)=withContext(Dispatchers.IO){AppMutationGate.read{
        require(friend.name.isNotBlank()&&friend.name.length<=30&&prompts.characterDefinition.isNotBlank()&&prompts.characterDefinition.length<=8000&&prompts.expression.length<=8000&&prompts.opening.length<=8000)
        val db=writableDatabase;db.beginTransaction()
        try{
            check(insertFriend(db,friend));RoleExamplesRepository.initialize(db,friend.id)
            db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",RolePromptStore.key(friend.id));put("value",RolePromptStore.encode(prompts))},SQLiteDatabase.CONFLICT_REPLACE)
            seedOpening(db,friend.id,prompts.opening);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }}
    internal suspend fun saveConfigured(id:String,name:String,change:(RolePrompts)->RolePrompts)=withContext(Dispatchers.IO){AppMutationGate.read{
        require(name.isNotBlank()&&name.trim().length<=30)
        val db=writableDatabase;db.beginTransaction()
        try{
            val raw=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(id))).use{if(it.moveToFirst())it.getString(0)else""}
            val p=change(RolePromptStore.decode(raw));require(p.characterDefinition.isNotBlank()&&p.initialRelationship.length<=4000&&listOf(p.characterDefinition,p.expression,p.opening,p.extra,p.internalPrompt).all{it.length<=8000})
            check(db.update("friends",ContentValues().apply{put("name",name.trim())},"id=?",arrayOf(id))==1)
            db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",RolePromptStore.key(id));put("value",RolePromptStore.encode(p))},SQLiteDatabase.CONFLICT_REPLACE)
            seedOpening(db,id,p.opening);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }}
    suspend fun setPreference(key: String, value: String) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply { put("key",key); put("value",value) },SQLiteDatabase.CONFLICT_REPLACE)
    }
}
