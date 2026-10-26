package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import android.content.ContentValues
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream

/** Isolated synthetic DBs only. Does not use providers, user databases or model credentials. */
class RoleArchiveAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository;private lateinit var transfer:RoleArchiveRepository;private lateinit var dbName:String
    @Before fun setup()=runBlocking{dbName="archive-${System.nanoTime()}.db";store=Store(context,dbName);repo=ProcessingRepository(store);transfer=RoleArchiveRepository(store);store.add(listOf(Friend("a","Synthetic A","Adult test role"),Friend("b","Synthetic B","Unrelated role")),false)}
    @After fun cleanup(){store.close();context.deleteDatabase(dbName)}
    private fun pack(root:JsonObject):RoleArchive.Package=RoleArchive.read(ByteArrayOutputStream().also{RoleArchive.write(root,it)}.toByteArray().inputStream())
    private suspend fun seed(){val ledger=ChatLedger(store);val t=ledger.begin("a","Synthetic preference: low sugar","unused-config");check(ledger.finish(t.id,1,"Synthetic reply"));val source=repo.sources("a").single()
        val m=StoredMemory("m1","a",MemoryCategory.PREFERENCE_BOUNDARY,"Synthetic low sugar",core=true,locked=true,about=MemoryAbout.USER,evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{db->repo.saveMemory(db,m);repo.candidate(db,"a","m1",m,"Synthetic question","candidate1")
            val state=RelationshipState(mapOf(RelationshipDimension.TRUST to RelationshipValue(51,"我愿意慢慢了解。")),ObservedFeeling("平静",null,null,source.assistant.created,source.source),1,source.source.turnId)
            db.insertOrThrow("relationship_state",null,ContentValues().apply{put("friend_id","a");put("json",state.json().toString());put("version",1);put("cursor",state.cursor)})
            db.insertOrThrow("relationship_history",null,ContentValues().apply{put("friend_id","a");put("json",state.json().toString());put("version",1);put("boundary",state.cursor);put("created",1)})
        }
    }
    @Test fun roundTripAddsIsolatedCopyWithSourcesAndRelationships()=runBlocking{seed();val oldB=transfer.export("b",true);val source=repo.sources("a").single();val p=pack(transfer.export("a",true));val id=transfer.commit(transfer.preview(p,null))
        assertNotEquals("a",id);val copied=repo.memories(id).single();assertNotEquals("m1",copied.id);assertTrue(copied.locked);assertTrue(copied.core);assertEquals(id,copied.friendId);assertNotEquals(source.source.turnId,copied.evidence.single().source.turnId);assertTrue(repo.read{repo.sourceValid(it,copied.evidence.single().source)})
        assertEquals(51,RelationshipRepository(repo).state(id).values.getValue(RelationshipDimension.TRUST).score);assertEquals(1,repo.candidates(id).size);assertEquals(oldB,transfer.export("b",true));assertEquals(1,repo.memories("a").size);assertFalse(repo.policy(id).first.relationshipEnabled)
    }
    @Test fun overwriteNeedsConfirmationAndPreservesUnrelatedRole()=runBlocking{seed();val p=pack(transfer.export("a",true));val before=transfer.export("b",true);val plan=transfer.preview(p,"b");assertTrue(plan.impact.contains("覆盖"));assertTrue(runCatching{transfer.commit(plan)}.isFailure);assertEquals(before,transfer.export("b",true));transfer.commit(plan,true);assertEquals(1,repo.memories("b").size);assertEquals(1,repo.memories("a").size)}
    @Test fun failedTransactionRestoresEverythingAfterPartialWrites()=runBlocking{seed();val p=pack(transfer.export("a",true));val before=transfer.export("b",true);val plan=transfer.preview(p,"b");var n=0;assertTrue(runCatching{transfer.commit(plan,true){if(++n==4)error("synthetic interruption")}}.isFailure);assertEquals(before,transfer.export("b",true));assertEquals(1,repo.memories("a").size)}
    @Test fun stalePreviewRejectedWithoutOverwritingNewData()=runBlocking{seed();val plan=transfer.preview(pack(transfer.export("a",true)),"b");store.exchange(Friend("b","b","b"),"New synthetic message","New synthetic reply");val newer=transfer.export("b",true);assertTrue(runCatching{transfer.commit(plan,true)}.isFailure);assertEquals(newer,transfer.export("b",true))}
    @Test fun invalidSourceVersionAndSnapshotNeverHalfWrite()=runBlocking{seed();val before=store.friends();for(mode in 0..1){val r=transfer.export("a",true);val row=r["records"].asJsonObject["memory_current"].asJsonArray[0].asJsonObject;val j=JSONObject(row["json"].asString);val e=j.getJSONArray("evidence").getJSONObject(0);if(mode==0)e.put("version",999)else e.put("snapshot","not an original message");row.addProperty("json",j.toString());assertTrue(runCatching{transfer.preview(pack(r),null)}.isFailure)};assertEquals(before,store.friends())}
    @Test fun roleOnlyOverwritePreservesAllChatAndMemoryRecords()=runBlocking{seed();val before=transfer.export("a",true)["records"];val p=pack(transfer.export("b",false));transfer.commit(transfer.preview(p,"a"),true);assertEquals(before,transfer.export("a",true)["records"])}
    @Test fun runningWorkBlocksExportAndOverwrite()=runBlocking{val p=pack(transfer.export("a",false));ChatLedger(store).begin("b","pending synthetic","unused");assertTrue(runCatching{transfer.export("b",true)}.isFailure);assertTrue(runCatching{transfer.preview(p,"b")}.isFailure)}
    @Test fun editedSourceHistoryAndInvalidMemoryRemainReadable()=runBlocking{seed();val source=repo.sources("a").single();val changes=SourceChanges(repo);changes.confirm(changes.preview("a",source.source.turnId,"user","Synthetic correction",false));val p=pack(transfer.export("a",true));val id=transfer.commit(transfer.preview(p,null));assertEquals(1,repo.memories(id).size);assertFalse(repo.memories(id).single().valid);assertTrue(repo.read{repo.usableMemories(it,id)}.isEmpty())}
    @Test fun deliveredLinchePackageParsesAndImportsWithoutSeededRecords()=runBlocking{
        val testContext=InstrumentationRegistry.getInstrumentation().context
        val p=testContext.assets.open("linche.lianxu.zip").use(RoleArchive::read);assertEquals("林澈",p.name);val id=transfer.commit(transfer.preview(p,null));assertTrue(repo.memories(id).isEmpty());assertTrue(repo.sources(id).isEmpty());assertTrue(RelationshipRepository(repo).state(id).values.isEmpty());assertFalse(repo.policy(id).first.relationshipEnabled)
    }
    @Test fun noModelSettingsExportedOrChanged()=runBlocking{store.setPreference("model-secret-synthetic","NOT_A_REAL_SECRET");val p=pack(transfer.export("a",false));assertFalse(p.raw.contains("NOT_A_REAL_SECRET"));transfer.commit(transfer.preview(p,null));assertEquals("NOT_A_REAL_SECRET",store.preference("model-secret-synthetic",""))}
}
