package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class RelationshipRecallAuditAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var repo:ProcessingRepository
    private lateinit var core:CompanionRepository
    private lateinit var name:String
    private val config=ModelConfig("synthetic","Synthetic","custom","https://example.invalid/v1","mock",128000,2048)
    @Before fun setup()=runBlocking {
        name="relation-recall-audit-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);core=CompanionRepository(store)
        store.addConfigured(Friend("r","合成角色","测试"),RolePrompts(characterDefinition="成年合成测试角色",relationTemplate="STEADY",relationStart="FRIENDS"))
        repo.saveMemoryRules(MemoryRules(proactiveRecall=true))
    }
    @After fun close(){store.close();context.deleteDatabase(name)}
    @Test fun hiddenEventDoesNotConsumeFiveSlotsAndUnlinkedUsesOriginal():Unit=runBlocking {
        val ledger=ChatLedger(store);val refs=mutableListOf<SourceRef>();val at=AppServices.companionClock()
        repeat(6){i->
            val turn=ledger.begin("r","事件$i","unused");val ref=core.sourceFor(turn);refs.add(ref)
            val snapshot=core.snapshot("r")
            val proposal=ExperienceProposal(relationInput=RelationInput(ref,at+i,at/RelationshipProjector.DAY,"关于关系",v=.9),timingReferenceAt=at+i)
            check(core.accept(turn,proposal,snapshot){true});check(ledger.finish(turn.id,1,"回复"));repo.tx{core.finish(it,turn,snapshot,proposal)}
        }
        val source=repo.sources("r").last()
        val hidden=StoredMemory("hidden","r",MemoryCategory.EXPERIENCE_MEMORY,source.user.text,about=MemoryAbout.BOTH,
            evidence=listOf(MemoryEvidence(refs.last(),"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{repo.saveMemory(it,hidden);MemoryForgetting.put(it,hidden,MemoryRecallState(at,0,120.0,10.0))}
        val turn=ledger.begin("r","事件","unused")
        val references=repo.references("r","事件",turn.userMessageId).copy(valid=emptyList(),pending=emptyList())
        val tools=MemoryTools(repo,turn,"事件",references,relationEnabled=true,active={true})
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            if(calls++==0)return TextChatResult.Success("",listOf(ChatToolCall("r","relation_event_search","{}")))
            val events=JSONObject(request.messages.last{it.role=="tool"}.content).getJSONArray("events")
            assertEquals(5,events.length())
            for(i in 0 until events.length()){val event=events.getJSONObject(i);assertNotEquals(refs.last().turnId,event.getLong("消息"));assertTrue(event.getString("内容").startsWith("事件"))}
            return TextChatResult.Success("回复")
        }}
        assertTrue(tools.run(config,"synthetic-only",TextChatRequest(listOf(WireMessage("system","规则"),WireMessage("user","事件"))),client) is TextChatResult.Success)
        assertEquals(refs.dropLast(1).map{it.turnId}.toSet(),tools.relationSeen)
        val view=core.relationship("r")
        val recent=repo.read{RelationshipRecall.recent(view.inputs,view.state,at+10,RelationshipRecall.resolver(repo,it,"r",at+10))}
        assertEquals(5,recent.size);assertTrue(recent.all{it["消息"]!=refs.last().turnId})
    }
    @Test fun disappointmentOnlyEventsAreExplainable():Unit=runBlocking {
        val ledger=ChatLedger(store);val refs=mutableListOf<SourceRef>();val at=AppServices.companionClock()
        repeat(6){i->
            val turn=ledger.begin("r","事件$i","unused");val ref=core.sourceFor(turn);refs.add(ref)
            val snapshot=core.snapshot("r")
            val proposal=ExperienceProposal(relationInput=RelationInput(ref,at+i,at/RelationshipProjector.DAY,"关于关系",disappointment=.6,explanationPolicy=1),timingReferenceAt=at+i)
            check(core.accept(turn,proposal,snapshot){true});check(ledger.finish(turn.id,1,"回复"));repo.tx{core.finish(it,turn,snapshot,proposal)}
        }
        val source=repo.sources("r").last()
        val hidden=StoredMemory("hidden","r",MemoryCategory.EXPERIENCE_MEMORY,source.user.text,about=MemoryAbout.BOTH,
            evidence=listOf(MemoryEvidence(refs.last(),"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{repo.saveMemory(it,hidden);MemoryForgetting.put(it,hidden,MemoryRecallState(at,0,120.0,10.0))}
        val turn=ledger.begin("r","事件","unused")
        val references=repo.references("r","事件",turn.userMessageId).copy(valid=emptyList(),pending=emptyList())
        val tools=MemoryTools(repo,turn,"事件",references,relationEnabled=true,active={true})
        var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            if(calls++==0)return TextChatResult.Success("",listOf(ChatToolCall("r","relation_event_search","{}")))
            val events=JSONObject(request.messages.last{it.role=="tool"}.content).getJSONArray("events")
            assertEquals(5,events.length())
            for(i in 0 until events.length()){val event=events.getJSONObject(i);assertNotEquals(refs.last().turnId,event.getLong("消息"));assertTrue(event.getString("内容").startsWith("事件"));assertTrue(event.getBoolean("可解释"))}
            return TextChatResult.Success("回复")
        }}
        assertTrue(tools.run(config,"synthetic-only",TextChatRequest(listOf(WireMessage("system","规则"),WireMessage("user","事件"))),client) is TextChatResult.Success)
        assertEquals(refs.dropLast(1).map{it.turnId}.toSet(),tools.relationSeen)
        val view=core.relationship("r")
        val recent=repo.read{RelationshipRecall.recent(view.inputs,view.state,at+10,RelationshipRecall.resolver(repo,it,"r",at+10))}
        assertEquals(5,recent.size);assertTrue(recent.all{it["消息"]!=refs.last().turnId})
    }
    @Test fun manualRecallAndToolMatrix():Unit=runBlocking {
        val ledger=ChatLedger(store);val turn=ledger.begin("r","芒果","unused")
        val cases=listOf(Triple(70.0,null,null),Triple(40.0,null,null),Triple(10.0,null,null),Triple(40.0,"水果过敏",null),Triple(10.0,"水果过敏",null),Triple(10.0,"水果过敏","芒果"))
        for((index,case) in cases.withIndex()){
            val m=StoredMemory("manual","r",MemoryCategory.USER_PROFILE,"晚星对芒果过敏",manual=true,core=true,version=index+1,gist=case.second,trace=case.third)
            val at=AppServices.companionClock()
            repo.tx{repo.saveMemory(it,m);MemoryForgetting.put(it,m,MemoryRecallState(at,0,3650.0,case.first))}
            val expected=when(index){0->repo.describe(m);3->"水果过敏";5->"芒果";else->""}
            val references=repo.references("r","芒果",turn.userMessageId)
            val found=references.valid.filter{it.memoryId==m.id}
            if(expected.isEmpty())assertTrue(found.isEmpty())else{assertEquals(1,found.size);assertEquals(expected,found.single().text)}
            assertTrue(references.valid.all{it.text.isNotBlank()&&it.label.isNotBlank()})
            val tools=MemoryTools(repo,turn,"芒果",references.copy(valid=emptyList(),pending=emptyList()),active={true})
            var calls=0
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                if(calls++==0)return TextChatResult.Success("",listOf(ChatToolCall("m","agent_memory_read","""{"query":"芒果"}""")))
                val results=JSONObject(request.messages.last{it.role=="tool"}.content).getJSONArray("results")
                assertEquals(if(expected.isEmpty())0 else 1,results.length())
                if(results.length()>0){assertEquals(expected,results.getJSONObject(0).getString("content"));assertEquals(if(case.first>60)m.category.label else "",results.getJSONObject(0).getString("title"))}
                return TextChatResult.Success("回复")
            }}
            assertTrue(tools.run(config,"synthetic-only",TextChatRequest(listOf(WireMessage("system","规则"),WireMessage("user","芒果"))),client) is TextChatResult.Success)
        }
    }
    @Test fun actualToolsUseThreeTiersAndSeenIds():Unit=runBlocking {
        val ledger=ChatLedger(store);val old=ledger.begin("r","周四他说周六电影改到周日","unused");val ref=core.sourceFor(old)
        val at=AppServices.companionClock();val snapshot=core.snapshot("r")
        val proposal=ExperienceProposal(relationInput=RelationInput(ref,at,at/RelationshipProjector.DAY,"关于关系",v=.9),timingReferenceAt=at)
        check(core.accept(old,proposal,snapshot){true});check(ledger.finish(old.id,1,"合成回复"));repo.tx{core.finish(it,old,snapshot,proposal)}
        val source=repo.sources("r").single()
        val memory=StoredMemory("movie","r",MemoryCategory.EXPERIENCE_MEMORY,source.user.text,about=MemoryAbout.BOTH,gist="电影改期",trace="电影",
            evidence=listOf(MemoryEvidence(ref,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{repo.saveMemory(it,memory)}
        val turn=ledger.begin("r","电影那件事","unused")
        for((clarity,expected) in listOf(80.0 to source.user.text,40.0 to "电影改期",10.0 to "电影",60.0 to "电影改期",61.0 to source.user.text,10.0 to "")){
            if(expected.isEmpty())repo.tx{repo.saveMemory(it,memory.copy(version=2,gist=null,trace=null))}
            repo.tx{MemoryForgetting.put(it,memory,MemoryRecallState(at,0,120.0,clarity))}
            val references=repo.references("r","电影",turn.userMessageId).copy(valid=emptyList(),pending=emptyList())
            val tools=MemoryTools(repo,turn,"电影那件事",references,relationEnabled=true,active={true})
            var n=0
            val client=object:TextChatClient {override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                if(n++==0)return TextChatResult.Success("",listOf(ChatToolCall("relation","relation_event_search","{\"query\":\"电影\"}"),ChatToolCall("memory","agent_memory_read","{\"query\":\"电影\"}")))
                if(expected.isEmpty()){
                    val events=JSONObject(request.messages.last{it.role=="tool"&&it.name=="relation_event_search"}.content).getJSONArray("events")
                    assertEquals(0,events.length())
                    assertEquals(0,JSONObject(request.messages.last{it.role=="tool"&&it.name=="agent_memory_read"}.content).getJSONArray("results").length())
                    return TextChatResult.Success("合成最终回复")
                }
                val event=JSONObject(request.messages.last{it.role=="tool"&&it.name=="relation_event_search"}.content).getJSONArray("events").getJSONObject(0)
                assertFalse(event.has("类型"));assertEquals(expected,event.getString("内容"));assertEquals(ref.turnId,event.getLong("消息"))
                val recalled=JSONObject(request.messages.last{it.role=="tool"&&it.name=="agent_memory_read"}.content).getJSONArray("results").getJSONObject(0)
                assertEquals(if(clarity>60)memory.category.label else "",recalled.getString("title"))
                return TextChatResult.Success("合成最终回复")
            }}
            val request=TextChatRequest(listOf(WireMessage("system","合成规则"),WireMessage("user","电影那件事")))
            assertTrue(tools.run(config,"synthetic-only",request,client) is TextChatResult.Success)
            assertEquals(expected.isNotEmpty(),ref.turnId in tools.relationSeen)
            val view=core.relationship("r")
            val release=RelationInput(SourceRef("r",turn.userMessageId,1),at,0,"关于关系",explains=ref,formatVersion=2,releaseKind="真误会")
            if(expected.isEmpty()){
                val issues=mutableListOf<String>()
                assertNull(RelationshipRecall.validate(release,view.state,tools.relationSeen){_,code->issues.add(code)}.explains)
                assertEquals(listOf("NOT_SEEN"),issues)
                assertTrue(repo.read{RelationshipRecall.recent(view.inputs,view.state,at,RelationshipRecall.resolver(repo,it,"r",at))}.isEmpty())
                continue
            }
            assertEquals(ref,RelationshipRecall.validate(release,view.state,tools.relationSeen){_,_->fail()}.explains)
            val recent=repo.read{RelationshipRecall.recent(view.inputs,view.state,at,RelationshipRecall.resolver(repo,it,"r",at))}
            assertEquals(expected,recent.single()["内容"]);assertFalse(recent.single().containsKey("类型"))
            assertTrue(com.google.gson.Gson().toJson(recent).toByteArray().size<=900)
        }
    }
}
