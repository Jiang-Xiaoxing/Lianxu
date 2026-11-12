package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelationshipSnapshotAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val day=RelationshipProjector.DAY
    private suspend fun seed(store:Store,core:CompanionRepository,at:Long,v:Double=.6):SourceRef {
        val ledger=ChatLedger(store);val turn=ledger.begin("r","快照测试合成原话","synthetic");val ref=core.sourceFor(turn)
        ProcessingRepository(store).tx{it.execSQL("UPDATE messages SET created=? WHERE id=?",arrayOf<Any>(at,ref.turnId))}
        val snapshot=core.snapshot("r");val proposal=ExperienceProposal(relationInput=RelationInput(ref,at,at/day,"关于关系",v=v),timingReferenceAt=at)
        assertTrue(core.accept(turn,proposal,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"))
        ProcessingRepository(store).tx{core.finish(it,turn,snapshot,proposal)}
        return ref
    }
    private suspend fun create(store:Store){store.addConfigured(Friend("r","快照合成角色","成年虚构角色"),RolePrompts(characterDefinition="成年合成人设",relationTemplate="DIRECT",relationStart="FRIENDS"))}
    @Test fun rollingRetentionPreservesMessagesAndEvents():Unit=runBlocking {
        val name="snapshot-retention-${System.nanoTime()}.db"
        Store(context,name).use{store->
            create(store);val core=CompanionRepository(store);seed(store,core,System.currentTimeMillis()-day)
            val repo=ProcessingRepository(store);var peak=0
            repo.tx{db->
                fun counts()=listOf("messages","companion_sources").map{table->db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getInt(0)}}
                val before=counts();val snapshots=DatabaseRelationSnapshots(db,"r");snapshots.truncate(0)
                for(i in 1..10030){
                    val at=i*day/30
                    snapshots.append(RelationSnapshotHeader(0,i,"test:$i",at,RelationDecay.ENGINE,"test","$i","DIRECT","test"),"{}")
                    peak=maxOf(peak,snapshots.headers().size);snapshots.prune(at)
                    assertTrue(snapshots.headers().size<=141)
                }
                assertEquals(before,counts());assertTrue(peak<=142)
                println("sqlite_retention inputs=10030 peak_count=$peak final_count=${snapshots.headers().size} original_counts_before=$before original_counts_after=${counts()}")
            }
        }
        context.deleteDatabase(name)
    }
    @Test fun migratedVersion11RetainsDataAndBuildsSnapshotsOnFirstRead():Unit=runBlocking {
        val name="snapshot-migration-${System.nanoTime()}.db"
        val old=object:SQLiteOpenHelper(context,name,null,11){
            override fun onCreate(db:SQLiteDatabase){
                Store(context,"unused-schema-helper").use{it.onCreate(db)}
                db.execSQL("DROP TABLE relationship_snapshots")
                db.execSQL("INSERT INTO friends VALUES('r','旧角色','保留简介','保留人设',0)")
                db.execSQL("INSERT INTO preferences VALUES('preserve-test','保留配置')")
            }
            override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }
        old.writableDatabase;old.close()
        Store(context,name).use{store->
            assertEquals(16,store.writableDatabase.version)
            assertEquals("保留简介",store.friends().single().bio)
            assertTrue(store.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key='preserve-test'",null).use{it.moveToFirst()&&it.getString(0)=="保留配置"})
            val core=CompanionRepository(store);seed(store,core,System.currentTimeMillis()-day)
            // 快照已改成存下的当前感觉：第一次读就记下来。
            core.relationship("r");assertNotNull(RelationFeeling.read(store.readableDatabase,"r"))
        }
    }
    @Test fun closeReopenDoesZeroReplayAndQueriesDoNotWrite():Unit=runBlocking {
        val name="snapshot-cold-${System.nanoTime()}.db";var store=Store(context,name);var now=System.currentTimeMillis()-10*day
        try{
            create(store);val core=CompanionRepository(store){now};seed(store,core,now);now+=day;seed(store,core,now)
            val expected=core.relationship("r").state;val headers=DatabaseRelationSnapshots(store.readableDatabase,"r").headers()
            store.close();store=Store(context,name);val cold=CompanionRepository(store){now}
            // 冷启动直接接着用存下的当前感觉：查询不重放、不改写。
            val saved=RelationFeeling.read(store.readableDatabase,"r");assertNotNull(saved)
            assertEquals(expected,cold.relationship("r").state)
            assertEquals(saved,RelationFeeling.read(store.readableDatabase,"r"))
            cold.relationship("r",now+365*day);assertEquals(headers,DatabaseRelationSnapshots(store.readableDatabase,"r").headers())
            cold.relationship("r",now-day);assertEquals(headers,DatabaseRelationSnapshots(store.readableDatabase,"r").headers())
            assertEquals(saved,RelationFeeling.read(store.readableDatabase,"r"))
        }finally{store.close()}
    }
    @Test fun latestWithdrawalAndEarlierCorrectionOnlyReplaySuffix():Unit=runBlocking {
        val store=Store(context,"snapshot-withdraw-${System.nanoTime()}.db");var now=System.currentTimeMillis()-10*day
        try{
            create(store);val core=CompanionRepository(store){now};val refs=mutableListOf<SourceRef>()
            repeat(5){refs.add(seed(store,core,now));now+=day}
            // 撤回只把那一笔从当前感觉里退掉，不把别的重新记一遍。
            suspend fun applied()=core.relationship("r").let{RelationCaches.get(store.readableDatabase,"r").feeling!!.applied}
            val applied=applied()
            val changes=SourceChanges(ProcessingRepository(store));changes.confirm(changes.preview("r",refs.last().turnId,"user","",true))
            assertEquals(applied,applied())
            // 只退那一笔（小星定的做法，不从头重算）：撤回的是最后一笔，退掉后和剩下各笔算出来的一样。
            // 「因为哪句话」只退掉撤回那笔，不回头找更早的一笔顶上，所以也不比。
            fun same(a:RelationState,b:RelationState){
                listOf(a.trust to b.trust,a.closeness to b.closeness,a.tension to b.tension,a.disappointment to b.disappointment,a.happy to b.happy,a.unhappy to b.unhappy,a.hurt to b.hurt,a.low to b.low).forEach{(x,y)->assertEquals(x,y,1e-12)}
                assertEquals(a.ledger,b.ledger);assertEquals(a.apologizable,b.apologizable);assertEquals(a.repairable,b.repairable);assertEquals(a.moments,b.moments);assertEquals(a.promises,b.promises)
            }
            val remaining=core.relationship("r").inputs
            val expected=RelationshipProjector.project(RelationTemplate.DIRECT.parameters,remaining,now,RelationStart.FRIENDS)
            val withdrawn=core.relationship("r").state
            same(expected,withdrawn)
            // 被撤回的消息当没发生过：它带来的「联系过」也退掉，最后联系回到上一条，想念照常上涨（只差分段误差）。
            assertEquals(expected.lastContact,withdrawn.lastContact);assertEquals(expected.missing,withdrawn.missing,1e-3)
            // 改正再撤销、改正再恢复：都只动那一笔（最多重记它自己，后面各笔不重记）。
            // 撤销时那一笔按原来的时间重新记上，但当时的信任已不同，数值不会和撤回后完全一样（只退那一笔的做法本来如此），所以只看那一笔回来了、数值往回走。
            fun back(after:RelationState,corrected:RelationState){
                assertEquals(withdrawn.ledger.keys,after.ledger.keys);assertEquals(withdrawn.moments.map{it.source},after.moments.map{it.source})
                assertTrue(kotlin.math.abs(after.trust-withdrawn.trust)<kotlin.math.abs(corrected.trust-withdrawn.trust))
            }
            val before=applied();assertTrue(core.correctRelationshipRecord(refs[2]));assertTrue(applied()-before in 0..1)
            val corrected=core.relationship("r").state;assertNotEquals(withdrawn.trust,corrected.trust,1e-9)
            assertTrue(core.undoRelationshipCorrection(refs[2]));back(core.relationship("r").state,corrected)
            assertTrue(core.correctRelationshipRecord(refs[2]));val again=core.relationship("r").state
            assertTrue(core.restoreRelationshipRecord(refs[2]));back(core.relationship("r").state,again)
        }finally{store.close()}
    }
    @Test fun rolledBackSnapshotsCannotPolluteLaterReads():Unit=runBlocking {
        val store=Store(context,"snapshot-rollback-${System.nanoTime()}.db");val now=System.currentTimeMillis()-day
        try{
            create(store);val core=CompanionRepository(store){now};val source=seed(store,core,now)
            val expected=core.relationship("r").state;val headers=DatabaseRelationSnapshots(store.readableDatabase,"r").headers()
            val db=store.writableDatabase;db.beginTransaction()
            try{
                val p=db.rawQuery("SELECT proposal FROM companion_sources WHERE role='r'",null).use{it.moveToFirst();Gson().fromJson(it.getString(0),ExperienceProposal::class.java)}
                db.execSQL("UPDATE companion_sources SET proposal=? WHERE source_id=?",arrayOf<Any>(Gson().toJson(p.copy(relationInput=p.relationInput!!.copy(status="disputed"))),source.turnId))
                assertNotEquals(expected,core.relationship(db,"r",now).state)
            }finally{db.endTransaction()}
            assertEquals(headers,DatabaseRelationSnapshots(db,"r").headers());assertEquals(expected,core.relationship("r").state)
        }finally{store.close()}
    }
    @Test fun corruptionAndMissingSnapshotsRebuildAndBackupExcludesCache():Unit=runBlocking {
        val name="snapshot-backup-${System.nanoTime()}";val store=Store(context,"$name.db");val dest=Store(context,"$name-copy.db")
        val now=System.currentTimeMillis()-day
        try{
            create(store);val core=CompanionRepository(store){now};seed(store,core,now)
            val expected=core.relationship("r").state;val db=store.writableDatabase
            // 存下的当前感觉坏了、引擎版本对不上或没了，都从各笔重新记一遍，结果不变。
            val key=RelationFeeling.key("r")
            for(sql in listOf("UPDATE preferences SET value='broken' WHERE key='$key'","UPDATE preferences SET value=replace(value,'\"engine\":','\"engine\":9') WHERE key='$key'","DELETE FROM preferences WHERE key='$key'")){
                db.execSQL(sql);assertEquals(expected,core.relationship("r").state)
                assertNotNull(RelationFeeling.read(db,"r"))
            }
            val exported=BackupRepository(store,ModelVault(context,name,store)).export("r")
            val tables=exported.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject
            assertFalse(tables.has("relationship_snapshots"))
            assertFalse(exported.root.toString().contains("relationship-cache:"))
            val backup=BackupRepository(dest,ModelVault(context,"$name-copy",dest))
            val preview=backup.preview(BackupArchive.Package(exported.root,exported.files,"synthetic"),"ADD")
            assertEquals(0,preview.relationReport.skipped);val role=backup.commitWithReport(preview).roles.single()
            val restored=CompanionRepository(dest){now}.relationship(role).state
            assertNotNull(RelationFeeling.read(dest.readableDatabase,role))
            // Source/role IDs are remapped by import, numerical state is preserved exactly.
            assertEquals(expected.trust,restored.trust,0.0);assertEquals(expected.tension,restored.tension,0.0)
            assertEquals(expected.hurt,restored.hurt,0.0);assertEquals(expected.missing,restored.missing,0.0)
            val originalBackup=BackupRepository(store,ModelVault(context,name,store))
            val safe=originalBackup.preview(BackupArchive.Package(exported.root,exported.files,"synthetic-cache-rebuild"),"ADD")
            db.execSQL("DELETE FROM relationship_snapshots");core.relationship("r")
            assertEquals(1,originalBackup.commitWithReport(safe).roles.size) // Cache rebuilding is not a data/configuration edit.
        }finally{store.close();dest.close()}
    }
}
