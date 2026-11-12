package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class RelationshipRepairAndroidTest {
    @Test fun targetedRepairPersistsAndRemapsWhileSourceChangesInvalidateIt():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="targeted-repair-${System.nanoTime()}"
        var store=Store(context,"$name.db")
        val destination=Store(context,"$name-import.db")
        val clock=System.currentTimeMillis()+RelationshipProjector.HOUR
        try {
            store.addConfigured(Friend("r","合成角色","成年合成角色"),RolePrompts(characterDefinition="合成人设",relationTemplate="DIRECT",relationStart="FRIENDS"))
            var core=CompanionRepository(store){clock}
            suspend fun add(text:String,make:(SourceRef)->RelationInput):SourceRef {
                val ledger=ChatLedger(store);val turn=ledger.begin("r",text,"synthetic")
                val ref=core.sourceFor(turn);val snapshot=core.snapshot("r")
                val proposal=ExperienceProposal(relationInput=make(ref))
                assertTrue(core.accept(turn,proposal,snapshot){true})
                assertTrue(ledger.finish(turn.id,1,"合成回复"))
                ProcessingRepository(store).tx{core.finish(it,turn,snapshot,proposal)}
                return ref
            }
            fun row(ref:SourceRef)=RelationInput(ref,clock,clock/RelationshipProjector.DAY,"关于关系")
            val hurt=add("合成事件：失约未赴电影约定"){row(it).copy(v=.9)}
            val other=add("合成事件：越过另一条边界"){row(it).copy(b=.6)}
            val unrelated=add("合成约定：帮忙查资料"){row(it).copy(promise=true)}
            val before=core.relationship("r").state
            add("合成兑现：资料已查好"){row(it).copy(c=.6,fulfills=unrelated)}
            val afterUnrelated=core.relationship("r").state
            assertEquals(before.tension,afterUnrelated.tension,0.0)
            assertTrue(afterUnrelated.trust>before.trust)
            val remedy=add("合成约定：补看电影"){row(it).copy(promise=true)}
            val repair=add("合成兑现：已经一起补看电影"){row(it).copy(c=.6,fulfills=remedy,repairs=hurt,repairCompleted=true)}
            val view=core.relationship("r")
            val reference=RelationCursor(RelationStart.FRIENDS,view.parameters)
            view.inputs.dropLast(1).forEach{reference.apply(it)}
            val expected=reference.state().tension-reference.impacts.getValue(hurt).tension*.5
            assertEquals(expected,view.state.tension,1e-12)
            assertTrue(other in view.state.repairable)
            assertEquals(hurt,view.inputs.single{it.source==repair}.repairs)
            store.close();store=Store(context,"$name.db");core=CompanionRepository(store){clock}
            assertEquals(view.state,core.relationship("r").state)
            val archive=BackupRepository(store,ModelVault(context,name,store)).export("r",false)
            val bytes=ByteArrayOutputStream().also{BackupArchive.write(archive.root,archive.files,it)}.toByteArray()
            val target=BackupRepository(destination,ModelVault(context,"$name-import",destination))
            val imported=target.commit(target.preview(BackupArchive.read(bytes.inputStream()),"ADD")).single()
            val copy=CompanionRepository(destination){clock}.relationship(imported)
            assertEquals(view.state.tension,copy.state.tension,0.0)
            val restored=copy.inputs.single{it.repairs!=null}
            assertEquals(imported,restored.repairs!!.characterId)
            assertTrue(copy.inputs.any{it.source==restored.repairs})
            assertTrue(copy.inputs.any{it.source==restored.fulfills&&it.promise})
            assertTrue(core.correctRelationshipRecord(repair))
            assertEquals(reference.state().tension,core.relationship("r").state.tension,1e-12)
            assertTrue(core.restoreRelationshipRecord(repair))
            ProcessingRepository(store).tx{it.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE user_message_id=?",arrayOf<Any>(hurt.turnId))}
            val changed=core.relationship("r")
            assertFalse(hurt in changed.state.repairable)
            // Current-feeling corrections remove only that event's impact; they do not replay
            // unrelated events as if the person had never lived through it. B keeps its original
            // saturated contribution, .3 * .6 * .7 * (1 - .3 * .9 * .7).
            assertEquals(.3*.6*.7*(1-.3*.9*.7),changed.state.tension,1e-12)
        } finally {
            store.close();destination.close()
            context.deleteDatabase("$name.db");context.deleteDatabase("$name-import.db")
        }
    }
}
