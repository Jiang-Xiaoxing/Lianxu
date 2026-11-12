package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run seed and check in separate instrumented processes with an intervening force-stop. */
class RelationshipRestartAndroidTest {
    @Test fun persistedProjectionAcrossProcessDeath():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val seed=InstrumentationRegistry.getArguments().getString("mode")=="seed"
        val pointer=File(context.filesDir,"relationship-snapshot-restart-name.txt")
        val name=if(seed)"relationship-snapshot-restart-${System.nanoTime()}.db".also{pointer.writeText(it)}else pointer.readText()
        val expected=File(context.filesDir,"$name.expected.json")
        val at=2_000_000_000_000L
        val store=Store(context,name);val core=CompanionRepository(store){at}
        try{
            if(seed){
                store.addConfigured(Friend("r","合成角色","成年虚构角色"),RolePrompts(characterDefinition="合成人设",relationTemplate="DIRECT",relationStart="FRIENDS"))
                val ledger=ChatLedger(store);val t=ledger.begin("r","提前改期的合成原话","synthetic");val source=core.sourceFor(t);val snapshot=core.snapshot("r")
                val p=ExperienceProposal(relationInput=RelationInput(source,at,at/RelationshipProjector.DAY,"关于关系",.6,0.0,.9,0.0,.81))
                assertTrue(core.accept(t,p,snapshot){true});assertTrue(ledger.finish(t.id,1,"合成回复"));ProcessingRepository(store).tx{core.finish(it,t,snapshot,p)}
                suspend fun oldEntry(text:String,make:(SourceRef)->RelationInput):SourceRef{
                    val entry=ledger.begin("r",text,"synthetic");val source=core.sourceFor(entry);val head=core.snapshot("r")
                    val proposal=ExperienceProposal(relationInput=make(source))
                    assertTrue(core.accept(entry,proposal,head){true});assertTrue(ledger.finish(entry.id,1,"合成旧回复"))
                    ProcessingRepository(store).tx{core.finish(it,entry,head,proposal)};return source
                }
                val oldAt=at-40*RelationshipProjector.DAY
                val promise=oldEntry("合成旧约定"){ref->RelationInput(ref,oldAt,oldAt/RelationshipProjector.DAY,"关于关系",promise=true)}
                oldEntry("合成已兑现的旧约定"){ref->RelationInput(ref,oldAt+1000,oldAt/RelationshipProjector.DAY,"关于关系",c=.6,fulfills=promise)}
                core.changeRelationshipTemplate("r",RelationTemplate.RESERVED)
                expected.writeText(Gson().toJson(core.relationship("r").state))
                assertTrue(requireNotNull(RelationFeeling.read(store.readableDatabase,"r")).merged!!.isNotEmpty())
            }else{
                // 存下的当前感觉直接接着用：重开后的查询不重放、不改写它。
                val saved=RelationFeeling.read(store.readableDatabase,"r");assertNotNull(saved)
                assertTrue(expected.exists());assertEquals(expected.readText(),Gson().toJson(core.relationship("r").state))
                assertEquals(1,core.relationshipChangeHistory("r").size)
                // 失望已不算心情（单独显示），这里只要求重开后心情不是平静且和存下的状态一致。
                val mood=core.relationshipMood("r");assertTrue(mood.primary.value>=.05);assertEquals(RelationshipSummary.mood(core.relationship("r").state),mood)
                assertTrue(core.relationship("r").summary.contains("提前改期的合成原话"))
                assertEquals(saved,RelationFeeling.read(store.readableDatabase,"r"))
                assertEquals(saved!!.cursor,RelationCaches.get(store.readableDatabase,"r").feelingRaw)
                val history=core.relationshipHistory("r");val promise=history.single{it.promise}.source
                val fulfilled=history.single{it.fulfills==promise}.source
                val contribution=requireNotNull(saved.merged)["${fulfilled.turnId}:${fulfilled.version}"]!!.trust
                val before=core.relationship("r").state
                store.writableDatabase.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE user_message_id=?",arrayOf(promise.turnId))
                val after=core.relationship("r")
                assertFalse(after.attributionIncomplete)
                assertEquals(before.trust-contribution,after.state.trust,1e-12)
                val withdrawn=requireNotNull(RelationFeeling.read(store.readableDatabase,"r"))
                val key="${fulfilled.turnId}:${fulfilled.version}"
                assertEquals(0.0,withdrawn.merged?.get(key)?.trust?:0.0,0.0)
                assertFalse(withdrawn.fulfillmentSources!!.containsKey(key))
            }
        }finally{store.close()}
    }
}
