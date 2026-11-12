package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class RelationshipBasisAndroidTest {
 @Test fun recallReopenBackupAndWithdrawalKeepActionPairsConsistent():Unit=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="repair-basis-${System.nanoTime()}";var store=Store(context,"$name.db");val destination=Store(context,"$name-copy.db")
  val clock=System.currentTimeMillis()+RelationshipProjector.HOUR
  try {
   store.addConfigured(Friend("r","合成角色","成年"),RolePrompts(characterDefinition="合成角色",relationTemplate="DIRECT",relationStart="FRIENDS"))
   var core=CompanionRepository(store){clock}
   suspend fun add(text:String,make:(SourceRef)->RelationInput):SourceRef {
    val ledger=ChatLedger(store);val turn=ledger.begin("r",text,"synthetic");val ref=core.sourceFor(turn);val snapshot=core.snapshot("r")
    val proposal=ExperienceProposal(relationInput=make(ref));assertTrue(core.accept(turn,proposal,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"))
    ProcessingRepository(store).tx{core.finish(it,turn,snapshot,proposal)};return ref
   }
   fun row(ref:SourceRef)=RelationInput(ref,clock,clock/RelationshipProjector.DAY,"关于关系")
   val hurt=add("合成旧事：电影失约"){row(it).copy(v=.9)}
   val action=add("合成已完成行动：补看电影完成凭据"){row(it).copy(repairs=hurt,repairCompleted=true)}
   val once=core.relationship("r").state.tension
   add("合成复述旧行动"){row(it).copy(repairs=hurt,repairCompleted=true,repairBasis=action)}
   assertEquals(once,core.relationship("r").state.tension,0.0)
   store.close();store=Store(context,"$name.db");core=CompanionRepository(store){clock}
   assertEquals(once,core.relationship("r").state.tension,0.0);assertEquals(setOf(RelationRepairPair(action,hurt)),core.relationship("r").state.repairPairs)
   val archive=BackupRepository(store,ModelVault(context,name,store)).export("r",false)
   val bytes=ByteArrayOutputStream().also{BackupArchive.write(archive.root,archive.files,it)}.toByteArray()
   val target=BackupRepository(destination,ModelVault(context,"$name-copy",destination))
   val imported=target.commit(target.preview(BackupArchive.read(bytes.inputStream()),"ADD")).single()
   val copy=CompanionRepository(destination){clock}.relationship(imported)
   assertEquals(once,copy.state.tension,0.0);assertEquals(1,copy.state.repairPairs.size)
   val linked=copy.inputs.single{it.repairBasis!=null}
   assertEquals(imported,linked.repairBasis!!.characterId);assertTrue(copy.inputs.any{it.source==linked.repairBasis})
   assertEquals(RelationRepairPair(linked.repairBasis,linked.repairs!!),copy.state.repairPairs.single())
   // The real tool must return completed actions too, not only negatives/open promises.
   val ledger=ChatLedger(store);val turn=ledger.begin("r","查找旧行动","synthetic");val repo=ProcessingRepository(store)
   suspend fun search(expectHit:Boolean){
    val refs=repo.references("r","补看电影完成凭据",turn.userMessageId)
    val tools=MemoryTools(repo,turn,"查找旧行动",refs,relationEnabled=true,active={true});var calls=0
    val config=ModelConfig("synthetic","合成","custom","https://example.invalid","synthetic",64000,2048)
    val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
     if(calls++==0)return TextChatResult.Success("",listOf(ChatToolCall("basis","relation_event_search","""{"query":"补看电影完成凭据","kind":"行动"}""")))
     val events=JSONObject(request.messages.last{it.role=="tool"}.content).getJSONArray("events")
     val matches=(0 until events.length()).map{events.getJSONObject(it)}.filter{it.getLong("消息")==action.turnId}
     assertEquals(if(expectHit)1 else 0,matches.size)
     if(expectHit){assertEquals(action.turnId,matches.single().getLong("消息"));assertTrue(matches.single().getBoolean("可引用行动来源"));assertTrue(matches.single().getString("内容").contains("补看电影完成凭据"))}
     return TextChatResult.Success("合成回复")
    }}
    assertTrue(tools.run(config,"synthetic",TextChatRequest(listOf(WireMessage("system","合成规则"),WireMessage("user","查找旧行动"))),client) is TextChatResult.Success)
    assertEquals(expectHit,action.turnId in tools.relationSeen)
   }
   search(true)
   repo.tx{it.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE user_message_id=?",arrayOf<Any>(action.turnId))}
   assertTrue(core.relationship("r").state.repairPairs.isEmpty())
   assertEquals(once*2,core.relationship("r").state.tension,1e-12)
   search(false)
  } finally {store.close();destination.close();context.deleteDatabase("$name.db");context.deleteDatabase("$name-copy.db")}
 }
}
