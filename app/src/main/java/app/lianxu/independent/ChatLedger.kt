package app.lianxu.independent

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.security.MessageDigest

enum class TurnState { PENDING,COMPLETED,FAILED,CANCELLED,INTERRUPTED }
data class ChatTurn(val id:String,val friendId:String,val userMessageId:Long,val assistantMessageId:Long?,val state:TurnState,val attempt:Int,val configId:String,val failure:ChatFailure?)

/** Local exactly-once persistence, not a promise of remote provider billing idempotency. */
class ChatLedger(private val store:Store,private val session:String=processSession) {
    companion object {
        private val processSession=UUID.randomUUID().toString()
        fun createSchema(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE chat_turns(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),user_message_id INTEGER NOT NULL UNIQUE REFERENCES messages(id),assistant_message_id INTEGER UNIQUE REFERENCES messages(id),state TEXT NOT NULL,attempt INTEGER NOT NULL,config_id TEXT NOT NULL,error_code TEXT,owner_session TEXT NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX one_pending_chat_per_friend ON chat_turns(friend_id) WHERE state='PENDING'")
        }
    }
    private fun read(c:Cursor)=ChatTurn(c.getString(0),c.getString(1),c.getLong(2),if(c.isNull(3))null else c.getLong(3),TurnState.valueOf(c.getString(4)),c.getInt(5),c.getString(6),if(c.isNull(7))null else ChatFailure.valueOf(c.getString(7)))
    private fun turn(db:SQLiteDatabase,id:String)=db.rawQuery("SELECT id,friend_id,user_message_id,assistant_message_id,state,attempt,config_id,error_code FROM chat_turns WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())read(it) else null}
    private fun <T> atomic(block:(SQLiteDatabase)->T):T = AppMutationGate.read {val db=store.writableDatabase;db.beginTransaction();try{val value=block(db);db.setTransactionSuccessful();value}finally{db.endTransaction()}}
    suspend fun all():List<ChatTurn> = withContext(Dispatchers.IO){store.readableDatabase.rawQuery("SELECT id,friend_id,user_message_id,assistant_message_id,state,attempt,config_id,error_code FROM chat_turns ORDER BY user_message_id",null).use{c->buildList{while(c.moveToNext())add(read(c))}}}
    suspend fun recoverInterrupted()=withContext(Dispatchers.IO){store.writableDatabase.update("chat_turns",ContentValues().apply{put("state",TurnState.INTERRUPTED.name);put("error_code",ChatFailure.INTERRUPTED.name)},"state='PENDING' AND owner_session<>?",arrayOf(session))}
    suspend fun begin(friendId:String,input:String,configId:String,id:String=UUID.randomUUID().toString()):ChatTurn = withContext(Dispatchers.IO){atomic{db->
        require(input.isNotBlank()&&input.length<=2000)
        turn(db,id)?.let{old->require(old.friendId==friendId);require(db.rawQuery("SELECT body FROM messages WHERE id=?",arrayOf(old.userMessageId.toString())).use{it.moveToFirst()&&it.getString(0)==input});return@atomic old}
        check(!db.rawQuery("SELECT 1 FROM chat_turns WHERE friend_id=? AND state='PENDING'",arrayOf(friendId)).use{it.moveToFirst()})
        val message=db.insertOrThrow("messages",null,ContentValues().apply{put("friend_id",friendId);put("body",input);put("mine",1);put("created",System.currentTimeMillis())})
        db.insertOrThrow("chat_turns",null,ContentValues().apply{put("id",id);put("friend_id",friendId);put("user_message_id",message);put("state",TurnState.PENDING.name);put("attempt",1);put("config_id",configId);put("owner_session",session);put("source_zone",java.time.ZoneId.systemDefault().id)})
        turn(db,id)!!
    }}
    suspend fun retry(id:String,configId:String):ChatTurn?=withContext(Dispatchers.IO){atomic{db->
        val old=turn(db,id)?:return@atomic null
        if(old.state !in setOf(TurnState.FAILED,TurnState.CANCELLED,TurnState.INTERRUPTED))return@atomic null
        val newer=db.rawQuery("SELECT 1 FROM chat_turns WHERE friend_id=? AND user_message_id>?",arrayOf(old.friendId,old.userMessageId.toString())).use{it.moveToFirst()}
        if(newer)return@atomic null
        db.update("chat_turns",ContentValues().apply{put("state",TurnState.PENDING.name);put("attempt",old.attempt+1);put("config_id",configId);putNull("error_code");put("owner_session",session)},"id=?",arrayOf(id))
        turn(db,id)
    }}
    suspend fun finish(id:String,attempt:Int,text:String,lease:ChatAttemptLease?=null,expectedStamp:String?=null,configUnchanged:()->Boolean={true}):Boolean=
        finishWithMemory(id,attempt,text,lease,expectedStamp,{},configUnchanged)
    internal suspend fun finishWithMemory(id:String,attempt:Int,text:String,lease:ChatAttemptLease?=null,expectedStamp:String?=null,onCompleted:(SQLiteDatabase)->Unit,configUnchanged:()->Boolean={true}):Boolean=withContext(Dispatchers.IO){
        val write={atomic{db->
        val current=turn(db,id)?:return@atomic false
        if(current.state!=TurnState.PENDING||current.attempt!=attempt)return@atomic false
        if(!db.rawQuery("SELECT 1 FROM chat_turns WHERE id=? AND owner_session=?",arrayOf(id,session)).use{it.moveToFirst()})return@atomic false
        if(expectedStamp!=null&&snapshot(db,id).stamp!=expectedStamp)return@atomic false
        if(!configUnchanged())return@atomic false
        require(text.isNotBlank())
        val message=db.insertOrThrow("messages",null,ContentValues().apply{put("friend_id",current.friendId);put("body",text);put("mine",0);put("created",System.currentTimeMillis())})
        db.update("chat_turns",ContentValues().apply{put("state",TurnState.COMPLETED.name);put("assistant_message_id",message);putNull("error_code")},"id=?",arrayOf(id));onCompleted(db);RoleExamplesRepository.maintain(db,current.friendId);true
    }}
        if(lease==null)write() else lease.commit(write)
    }
    suspend fun fail(id:String,attempt:Int,error:ChatFailure)=transition(id,attempt,TurnState.FAILED,error)
    suspend fun cancel(id:String,attempt:Int)=transition(id,attempt,TurnState.CANCELLED,null)
    private suspend fun transition(id:String,attempt:Int,state:TurnState,error:ChatFailure?)=withContext(Dispatchers.IO){store.writableDatabase.update("chat_turns",ContentValues().apply{put("state",state.name);if(error==null)putNull("error_code") else put("error_code",error.name)},"id=? AND attempt=? AND state='PENDING' AND owner_session=?",arrayOf(id,attempt.toString(),session))==1}
    data class Context(val friend:Friend,val recent:List<Message>,val input:String,val rawPrompts:String,val stamp:String)
    private fun snapshot(db:SQLiteDatabase,id:String):Context {
        val t=turn(db,id)?:error("Missing turn")
        RoleExamplesRepository.maintain(db,t.friendId)
        val friend=db.rawQuery("SELECT id,name,bio,prompt,tone FROM friends WHERE id=?",arrayOf(t.friendId)).use{c->check(c.moveToFirst());Friend(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4))}
        val recent=completed(db,t.friendId)
        val input=db.rawQuery("SELECT body FROM messages WHERE id=?",arrayOf(t.userMessageId.toString())).use{c->check(c.moveToFirst());c.getString(0)}
        val prompts=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(t.friendId))).use{c->if(c.moveToFirst())c.getString(0) else ""}
        val raw=listOf(friend.toString(),recent.joinToString("\u0000"),t.userMessageId.toString(),input,prompts).joinToString("\u0001")
        val stamp=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
        return Context(friend,recent,input,prompts,stamp)
    }
    suspend fun context(id:String):Context=withContext(Dispatchers.IO){atomic{snapshot(it,id)}}
    private fun completed(db:SQLiteDatabase,friendId:String):List<Message> = db.rawQuery("SELECT m.id,m.friend_id,m.body,m.mine,m.created FROM messages m JOIN chat_turns t ON m.id=t.user_message_id OR m.id=t.assistant_message_id WHERE t.friend_id=? AND t.state='COMPLETED' AND NOT EXISTS(SELECT 1 FROM memory_retired r WHERE r.friend_id=t.friend_id AND r.source_user_id=t.user_message_id AND r.source_version=t.source_revision) AND NOT EXISTS(SELECT 1 FROM message_state ms WHERE ms.message_id IN(t.user_message_id,t.assistant_message_id) AND ms.deleted=1) ORDER BY m.id",arrayOf(friendId)).use{c->buildList{while(c.moveToNext())add(Message(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getLong(4)))}}
    /** Only completed real turns are eligible. Legacy/demo rows stay in the DB and are not imported. */
    suspend fun completedMessages(friendId:String):List<Message> = withContext(Dispatchers.IO){completed(store.readableDatabase,friendId)}
}
