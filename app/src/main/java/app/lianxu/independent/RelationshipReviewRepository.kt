package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.UUID

/** Previews are transient. Only explicit adoption appends a new relationship version. */
data class RelationshipRollback(val friendId:String,val expectedVersion:Int,val targetVersion:Int,val characterStamp:String,val before:RelationshipState,val after:RelationshipState)
data class RelationshipReassessment(val work:RelationshipWork,val expectedVersion:Int,val originalTask:String,val before:RelationshipState)
data class RelationshipCandidate(val plan:RelationshipReassessment,val after:RelationshipState)

class RelationshipReviewRepository(private val repo:ProcessingRepository) {
    private val relations=RelationshipRepository(repo)
    private fun history(db:SQLiteDatabase,id:String,version:Int):RelationshipState =
        db.rawQuery("SELECT json FROM relationship_history WHERE friend_id=? AND version=?",arrayOf(id,version.toString())).use{c->
            rejectUnless(c.moveToFirst(),"RELATION_HISTORY_MISSING");RelationshipState.from(JSONObject(c.getString(0)))
        }
    suspend fun previewRollback(id:String,version:Int):RelationshipRollback=repo.read{db->
        val current=relations.state(db,id)
        rejectUnless(version<current.version,"RELATION_HISTORY_MISSING")
        val old=history(db,id,version)
        RelationshipRollback(id,current.version,version,repo.characterStamp(db,id),current,old.copy(version=current.version+1,cursor=current.cursor))
    }
    suspend fun rollback(plan:RelationshipRollback,lease:ChatAttemptLease=ChatAttemptLease()):Boolean=repo.tx{db->lease.commit{
        val current=relations.state(db,plan.friendId)
        rejectUnless(current.version==plan.expectedVersion&&repo.characterStamp(db,plan.friendId)==plan.characterStamp,"SOURCE_CHANGED")
        val old=history(db,plan.friendId,plan.targetVersion)
        // Preserve the high-water mark: restoring a value never replays already processed dialogue.
        relations.save(db,plan.friendId,old.copy(version=current.version+1,cursor=current.cursor),"rollback:${plan.targetVersion}")
        db.update("relationship_jobs",ContentValues().apply{put("state","FAILED");put("error","SOURCE_CHANGED")},"friend_id=? AND state='RUNNING'",arrayOf(plan.friendId))
        true
    }}
    private fun latest(db:SQLiteDatabase,id:String):RelationshipWork {
        val current=relations.state(db,id)
        val task=db.rawQuery("SELECT json FROM relationship_history WHERE friend_id=? AND version=?",arrayOf(id,current.version.toString())).use{c->
            rejectUnless(c.moveToFirst(),"RELATION_REVIEW_UNAVAILABLE");JSONObject(c.getString(0)).optString("task")
        }
        val work=db.rawQuery("SELECT id,json,state FROM relationship_jobs WHERE friend_id=?",arrayOf(id)).use{c->
            rejectUnless(c.moveToFirst()&&c.getString(0)==task&&c.getString(2)=="COMPLETED","RELATION_REVIEW_UNAVAILABLE")
            RelationshipWork.from(JSONObject(c.getString(1)))
        }
        rejectUnless(work.friendId==id&&work.id==task&&work.targets.isNotEmpty()&&work.targets.all{it.source.characterId==id},"RELATION_REVIEW_UNAVAILABLE")
        rejectUnless(work.targets.last().source.turnId==current.cursor&&repo.sourcesMatch(db,work.targets),"SOURCE_CHANGED")
        rejectUnless(repo.characterStamp(db,id)==work.characterStamp,"SOURCE_CHANGED")
        return work
    }
    suspend fun prepare(id:String,configStamp:String):RelationshipReassessment=repo.read{db->
        val original=latest(db,id);val (policy,version)=repo.policy(db,id)
        rejectUnless(policy.relationshipEnabled,"DISABLED")
        val current=relations.state(db,id)
        // Keep the original pre-batch state even when this assessment has already been replaced.
        RelationshipReassessment(original.copy(id=UUID.randomUUID().toString(),policyVersion=version,configStamp=configStamp),current.version,original.id,current)
    }
    private fun validate(db:SQLiteDatabase,plan:RelationshipReassessment,configCurrent:()->Boolean){
        val work=plan.work
        rejectUnless(relations.state(db,work.friendId).version==plan.expectedVersion,"SOURCE_CHANGED")
        rejectUnless(latest(db,work.friendId).id==plan.originalTask,"SOURCE_CHANGED")
        rejectUnless(repo.policy(db,work.friendId).second==work.policyVersion&&repo.policy(db,work.friendId).first.relationshipEnabled,"SOURCE_CHANGED")
        rejectUnless(configCurrent(),"SOURCE_CHANGED")
    }
    suspend fun preview(plan:RelationshipReassessment,response:JSONObject,configCurrent:()->Boolean):RelationshipCandidate=repo.read{db->
        validate(db,plan,configCurrent)
        RelationshipCandidate(plan,relations.propose(db,plan.work,response))
    }
    suspend fun adopt(candidate:RelationshipCandidate,configCurrent:()->Boolean,lease:ChatAttemptLease=ChatAttemptLease()):Boolean=repo.tx{db->lease.commit{
        val plan=candidate.plan;validate(db,plan,configCurrent)
        val current=relations.state(db,plan.work.friendId)
        relations.save(db,plan.work.friendId,candidate.after.copy(version=current.version+1,cursor=current.cursor),plan.work.id)
        // Retain the exact original batch and its pre-evaluation state for any later replacement.
        db.update("relationship_jobs",ContentValues().apply{put("id",plan.work.id);put("json",plan.work.json().toString());put("state","COMPLETED");putNull("error")},"friend_id=? AND id=?",arrayOf(plan.work.friendId,plan.originalTask))
        true
    }}
}
