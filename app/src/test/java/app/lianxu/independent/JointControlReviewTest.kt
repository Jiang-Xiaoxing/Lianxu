package app.lianxu.independent
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JointControlReviewTest {
 private val source=SourceRef("review",5,1)
 private val cfg=ModelConfig("synthetic","synthetic","custom","https://example.invalid","synthetic",64000,2048)
 private val context=RoleContext("review",source,mapOf(5L to source),emptyList(),emptyList(),emptyList(),emptyList())
 private val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("no network")}
 private val input="不用再提醒我了，谢谢你一直关心我"
 private val plainReply="嗯，我听到了，也很开心你这样说。"
 private fun raw(control:Boolean):String {
  val json=JsonParser.parseString("""{"schemaVersion":1,"appraisal":{"event":"感谢关心","meaning":"对方认可我的关心","certainty":"reported","relevance":"回应感谢","attribution":"shared_interaction","situation":"chat","eventRelation":"new_event","uncertainty":[],"evidence":[{"source":5,"layer":"fact"}]},"feelings":[{"kind":"joy","degree":"mild","object":"这次感谢","reason":"对方感谢我的关心","evidence":[{"source":5,"layer":"fact"}]}],"goal":{"description":"回应感谢","origin":"current_request","state":"active","reason":"当前交流","evidence":[{"source":5,"layer":"fact"}]},"choices":[{"id":"r","action":"acknowledge","reason":"回应当前感谢","evidence":[{"source":5,"layer":"fact"}]}],"selected":"r","judgment":{"position":"对方表达了感谢","kind":"inference","evidence":[{"source":5,"layer":"fact"}]},"feedback":[],"learning":null,"usedLearningIds":[],"consideredLearningIds":[],"future":null,"关系输入":{"消息":"5","信息类别":"关于关系","P":"轻","C":"无","V":"无","B":"无"},"experience":{"quote":"谢谢你一直关心我","memory":"","emotion":"unknown","valence":0,"relationship":"appreciation","control":"none","targets":[],"reconsider":null,"claimKind":"interaction","controlIntent":"none","requestQuote":""}}""").asJsonObject
  if(control){json.getAsJsonObject("experience").addProperty("controlIntent","stop_reminding");json.getAsJsonObject("experience").addProperty("requestQuote","不用再提醒我了")}
  return json.toString()
 }
 private fun receipt()=ExperienceProposal(control="stop_reminding",targets=listOf(1L),controlChecked=true,controlAuthorized=true,controlIntent="stop_reminding",requestQuote="不用再提醒我了",controlQuote="不用再提醒我了")
 private suspend fun respond(args:String,control:()->ExperienceProposal?={null},current:()->RoleContext={context},ctx:RoleContext=context,reply:String=plainReply)=
  RolePlanner(client).respond(cfg,"synthetic",input,ExperienceProposal(interpretationUnknown=true),ctx,0,TextChatRequest(listOf(WireMessage("user",input))),{true},{0},false,controlOutcome=control,currentContext=current){_,record->
   record?.invoke(args)
   TextChatResult.Success(reply)
  }
 @Test fun ordinaryComparisonKeepsModelFeelingAndPositiveInput()=runBlocking {
  val result=respond(raw(false))
  assertEquals(plainReply,result.text)
  assertEquals(1,result.understanding!!.feelings.size);assertEquals(.3,result.relationInput!!.p!!,0.0)
 }
 @Test fun controlShouldKeepIndependentCurrentFeelingAndPositiveInput()=runBlocking {
  val result=respond(raw(true),control={receipt()})
  assertEquals(plainReply,result.text)
  assertEquals("Independent current feeling is lost solely because control applied",1,result.understanding!!.feelings.size)
  assertNotNull(result.relationInput)
  assertEquals("stop_reminding",result.experience!!.control)
 }
 @Test fun recordWithoutExperienceIsRejectedWithAHintAndPublishesNothing()=runBlocking {
  val receipts=mutableListOf<Map<String,Any>>()
  val result=RolePlanner(client).respond(cfg,"synthetic",input,ExperienceProposal(interpretationUnknown=true),context,0,TextChatRequest(listOf(WireMessage("user",input))),{true},{0},false){_,record->
   receipts+=record!!.invoke("""{"feelings":[]}""")
   TextChatResult.Success(plainReply)
  }
  val receipt=receipts.single()
  assertEquals(false,receipt["success"]);assertEquals("MISSING_EXPERIENCE",receipt["error"])
  assertTrue((receipt["hint"] as String).isNotBlank())
  assertNull(result.understanding);assertEquals(plainReply,result.text)
 }
 @Test fun controlRecordsUseRefreshedSourcesAndKeepOnlyValidFeelings()=runBlocking {
  val old=SourceRef("review",1,1)
  val stale=context.copy(knownSources=context.knownSources+(1L to old))
  val json=JsonParser.parseString(raw(true)).asJsonObject
  val feelings=json.getAsJsonArray("feelings")
  val withdrawn=feelings[0].asJsonObject.deepCopy()
  withdrawn.getAsJsonArray("evidence")[0].asJsonObject.addProperty("source",1)
  feelings.add(withdrawn)
  val result=respond(json.toString(),control={receipt()},current={context},ctx=stale)
  assertEquals(1,result.understanding!!.feelings.size)
  assertEquals(source,result.understanding!!.feelings.single().evidence.single().source)
  assertTrue(result.understanding!!.dependencies.none{it.source==old})
  assertEquals(.3,result.relationInput!!.p!!,0.0)
 }
 @Test fun recordTakenBeforeControlIsRevalidatedAfterIt()=runBlocking {
  var executed:ExperienceProposal?=null
  val old=SourceRef("review",1,1)
  val stale=context.copy(knownSources=context.knownSources+(1L to old))
  val json=JsonParser.parseString(raw(false)).asJsonObject
  // The record cites the old source in a feeling; the control that follows withdraws it.
  json.getAsJsonArray("feelings").add(JsonParser.parseString("""{"kind":"joy","degree":"mild","object":"旧事","reason":"旧事","evidence":[{"source":1,"layer":"fact"}]}"""))
  val reply=RolePlanner(client).respond(cfg,"synthetic",input,ExperienceProposal(interpretationUnknown=true),stale,0,TextChatRequest(listOf(WireMessage("user",input))),{true},{0},false,
      controlOutcome={executed},
      currentContext={if(executed==null)stale else context}){_,record->
      record!!.invoke(json.toString())
      executed=receipt()
      TextChatResult.Success(plainReply)
  }
  assertEquals("stop_reminding",reply.experience!!.control)
  assertEquals("The withdrawn-source feeling must be dropped, the valid one kept",1,reply.understanding!!.feelings.size)
  assertEquals(source,reply.understanding!!.feelings.single().evidence.single().source)
  assertTrue(reply.understanding!!.dependencies.none{it.source==old})
 }
 @Test fun failedRecordAfterAnAcceptedOneDoesNotReplaceIt()=runBlocking {
  var executed:ExperienceProposal?=null
  val receipts=mutableListOf<Map<String,Any>>()
  val reply=RolePlanner(client).respond(cfg,"synthetic",input,ExperienceProposal(interpretationUnknown=true),context,0,TextChatRequest(listOf(WireMessage("user",input))),{true},{0},false,
      controlOutcome={executed},
      currentContext={context}){_,record->
      receipts+=record!!.invoke(raw(false))
      receipts+=record!!.invoke("""{"feelings":[]}""")
      executed=receipt()
      TextChatResult.Success(plainReply)
  }
  assertEquals(2,receipts.size)
  assertEquals(true,receipts[0]["success"])
  assertEquals("staged_until_reply_commit",receipts[0]["status"])
  assertEquals(false,receipts[1]["success"])
  assertTrue("A later failed record must not wipe the accepted one",reply.recordAccepted)
  assertEquals("stop_reminding",reply.experience!!.control)
 }
}
