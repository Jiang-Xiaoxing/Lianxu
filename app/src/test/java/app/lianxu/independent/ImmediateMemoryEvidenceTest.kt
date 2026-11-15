package app.lianxu.independent

import java.time.Instant
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ImmediateMemoryEvidenceTest {
    private fun proof(id:Long,text:String,at:String="2026-11-14T15:00:00Z",zone:String="Asia/Shanghai",role:String="role")=
        MemoryEvidence(SourceRef(role,id,1),"user",text,text,Instant.parse(at).toEpochMilli(),zone)
    private val birthday=proof(1,"我的生日是5月20日")
    private val confirmation=proof(3,"确认，记住吧","2026-11-16T15:00:00Z")
    private fun wire(p:MemoryEvidence,role:String="user")=WireMessage(role,p.snapshot,sourceMessageId=p.source.turnId.toString())
    private fun args(alias:String="S2",text:String="我的生日是5月20日",time:String?="5月20日",type:String?="birthday")=
        JSONObject().put("op","add").put("content",text).put("category","STATE_FACT").put("about","USER").put("evidence",org.json.JSONArray().put(alias)).apply{
            time?.let{put("time_text",it)};type?.let{put("date_type",it)}
        }
    private fun aliases(vararg previous:MemoryEvidence)=ImmediateMemoryEvidence.aliases(confirmation,previous.toList(),previous.map{wire(it)}+WireMessage("user",confirmation.snapshot))
    private fun rejected(code:String,block:()->Unit){assertEquals(code,(runCatching(block).exceptionOrNull() as? ProcessingRejected)?.code)}
    @Test fun previousBirthdayThenConfirmationSavesOriginalSourceWithoutInventedYear(){
        val available=aliases(birthday);val op=args();val source=ImmediateMemoryEvidence.select(op,available)
        val staged=ImmediateMemoryEvidence.stamp(source,op)
        val verified=ImmediateMemoryEvidence.revalidate(staged,listOf(birthday,confirmation))
        val dates=ImmediateMemoryEvidence.dates(staged,verified,op.getString("content"))
        val memory=StoredMemory("memory","role",MemoryCategory.STATE_FACT,op.getString("content"),evidence=listOf(verified),dateType="birthday",eventDate=dates.first,annualMonthDay=dates.second,about=MemoryAbout.USER)
        assertNull(memory.eventDate);assertEquals("05-20",memory.annualMonthDay)
        assertEquals(birthday,memory.evidence.single());assertNotEquals(confirmation.source,memory.evidence.single().source)
        assertEquals(birthday,StoredMemory.from(memory.json()).evidence.single())
    }
    @Test fun tomorrowUsesOriginalDayAndTimeZoneAcrossMidnight(){
        val old=proof(1,"我明天去看展","2026-11-15T04:30:00Z","America/New_York") // Nov 14 locally.
        val op=args(text="我明天去看展",time="明天",type="day")
        val source=ImmediateMemoryEvidence.select(op,aliases(old))
        assertEquals("2026-11-15" to null,ImmediateMemoryEvidence.dates(op,source,op.getString("content")))
        assertEquals("2026-11-17",MemoryDates.resolve("明天",confirmation.sourceAt,confirmation.zone))
    }
    @Test fun priorNonDateFactCanStillHaveNoDate(){
        val old=proof(1,"我不吃香菜");val op=args(text=old.snapshot,time=null,type=null)
        assertEquals(null to null,ImmediateMemoryEvidence.dates(op,ImmediateMemoryEvidence.select(op,aliases(old)),old.snapshot))
    }
    @Test fun onlyVisibleVerbatimUserMessagesOfSameRoleReceiveAliases(){
        val other=proof(8,"别的角色原话",role="other");val omitted=proof(9,"没有送入上下文")
        val visible=listOf(wire(birthday,"assistant"),wire(other),WireMessage("system",omitted.snapshot),WireMessage("user",confirmation.snapshot))
        assertEquals(setOf("S1"),ImmediateMemoryEvidence.aliases(confirmation,listOf(birthday,other,omitted),visible).keys)
        val changed=wire(birthday).copy(content="模型改写的摘要")
        assertEquals(setOf("S1"),ImmediateMemoryEvidence.aliases(confirmation,listOf(birthday),listOf(changed,WireMessage("user",confirmation.snapshot))).keys)
    }
    @Test fun missingCurrentInputDoesNotRenameHistoricalSourceAsS1(){
        assertEquals(setOf("S2"),ImmediateMemoryEvidence.aliases(confirmation,listOf(birthday),listOf(wire(birthday))).keys)
    }
    @Test fun sourceRemovedEditedOrRelocatedBeforeCommitIsRejected(){
        val staged=ImmediateMemoryEvidence.stamp(birthday,args())
        rejected("SOURCE_CHANGED"){ImmediateMemoryEvidence.revalidate(staged,listOf(confirmation))}
        listOf(birthday.copy(source=birthday.source.copy(version=2)),birthday.copy(snapshot="改过的原话"),birthday.copy(zone="UTC"),birthday.copy(sourceAt=birthday.sourceAt+1),birthday.copy(source=birthday.source.copy(characterId="other"))).forEach{
            rejected("SOURCE_CHANGED"){ImmediateMemoryEvidence.revalidate(staged,listOf(it))}
        }
    }
    @Test fun sourceNoLongerInOutgoingContextCannotBeUsed(){
        val captured=aliases(birthday)
        val outgoing=listOf(WireMessage("user",confirmation.snapshot))
        val available=captured.filter{(alias,p)->ImmediateMemoryEvidence.visible(p,outgoing,alias=="S1")}
        rejected("EVIDENCE"){ImmediateMemoryEvidence.select(args(),available)}
        assertEquals(confirmation,ImmediateMemoryEvidence.select(args(alias="S1"),available))
    }
    @Test fun fabricatedOrMultipleAliasesAreRejected(){
        rejected("EVIDENCE"){ImmediateMemoryEvidence.select(args(alias="S999"),aliases(birthday))}
        rejected("EVIDENCE"){ImmediateMemoryEvidence.select(args().put("evidence",org.json.JSONArray().put("S1").put("S2")),aliases(birthday))}
    }
    @Test fun missingDateAndHabitCannotBypassDateEvidence(){
        for(op in listOf(args(time=null,type=null),args(time=""),args(type="habit"))){
            rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(op,birthday,op.getString("content"))}
        }
    }
    @Test fun timeMustBeInTheChosenSourceNotInAnotherAvailableMessage(){
        val op=args(alias="S1")
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(op,confirmation,op.getString("content"))}
        assertEquals(null to "05-20",ImmediateMemoryEvidence.dates(op,birthday,op.getString("content")))
    }
    @Test fun relativeBirthdayDoesNotInventBirthYearAndImpossibleBirthdayFails(){
        val old=proof(1,"明天是我生日")
        assertEquals(null to "11-15",ImmediateMemoryEvidence.dates(args(text=old.snapshot,time="明天"),old,old.snapshot))
        val invalid=proof(1,"生日是4月31日")
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(args(text=invalid.snapshot,time="4月31日"),invalid,invalid.snapshot)}
    }
    @Test fun feedbackUsesSelectedSourceAndNeverSuggestsDroppingDate(){
        val op=args();assertTrue(ToolArgumentDiagnosis.diagnoseMemoryUpdate(op,confirmation.snapshot,emptySet(),aliases(birthday)).isEmpty())
        val wrong=op.put("evidence",org.json.JSONArray().put("S1"))
        val failure=ToolArgumentDiagnosis.writeFailure("agent_memory_update",wrong.toString(),confirmation.snapshot,emptySet(),mapOf("success" to false,"error" to "DATE_EVIDENCE"),ProcessingRejected("DATE_EVIDENCE"),aliases(birthday))
        val detail=failure["detail"].toString();assertTrue(detail.contains("所选"));assertFalse(detail.contains("应为空"));assertEquals("DATE_EVIDENCE",failure["error"])
        val missing=args(time=null);val p=ToolArgumentDiagnosis.diagnoseMemoryUpdate(missing,confirmation.snapshot,emptySet(),aliases(birthday))
        assertTrue(p.any{it.field=="time_text"&&"不能删掉" in it.expected})
    }
    @Test fun inheritedContentIsNotMistakenForANewDatedClaim(){
        val op=JSONObject().put("op","cancel").put("date_type","day")
        assertNull(ImmediateMemoryEvidence.dateProblem(op,confirmation,birthday.snapshot,false))
        assertNotNull(ImmediateMemoryEvidence.dateProblem(op,confirmation,birthday.snapshot,true))
    }
    @Test fun unrelatedTimeWordCannotSilentlyLoseTheDate(){
        val old=proof(1,"我明天去看展");val op=args(text=old.snapshot,time="看展",type="day")
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(op,old,old.snapshot)}
        val p=ToolArgumentDiagnosis.diagnoseMemoryUpdate(op,confirmation.snapshot,emptySet(),aliases(old))
        assertTrue(p.any{it.field=="time_text"&&"单日" in it.expected})
    }
    @Test fun historicalNonDatePreferenceCanBeExtractedAndParaphrasedWithoutOtherFactsDate(){
        val old=proof(1,"我明天去看展，我不吃香菜")
        val text="饮食偏好是不吃香菜";val op=args(text=text,time=null,type=null).put("quote","我不吃香菜")
        val fact=ImmediateMemoryEvidence.quote(ImmediateMemoryEvidence.select(op,aliases(old)),op)
        assertEquals("我不吃香菜",fact.quote);assertEquals(old.snapshot,fact.snapshot)
        assertEquals(null to null,ImmediateMemoryEvidence.dates(op,fact,text))
        val memory=StoredMemory("pref","role",MemoryCategory.PREFERENCE_BOUNDARY,text,evidence=listOf(fact),about=MemoryAbout.USER)
        assertEquals(text,StoredMemory.from(memory.json()).text);assertNotEquals(text,fact.quote)
        val staged=ImmediateMemoryEvidence.stamp(fact,op)
        assertEquals(fact,ImmediateMemoryEvidence.revalidate(staged,listOf(old)))
        val withoutQuote=args(text="我不吃香菜",time=null,type=null)
        assertEquals(null to null,ImmediateMemoryEvidence.dates(withoutQuote,ImmediateMemoryEvidence.quote(old,withoutQuote),"我不吃香菜"))
    }
    @Test fun inheritedBirthdayTypeIsResolvedBeforeBothStageAndCommit(){
        val old=proof(1,"明天是我生日");val raw=args(text=old.snapshot,time="明天",type=null)
        val effective=ImmediateMemoryEvidence.withDateType(raw,"birthday")
        assertEquals("birthday",effective.getString("date_type"))
        assertEquals(null to "11-15",ImmediateMemoryEvidence.dates(effective,old,old.snapshot))
    }
    @Test fun wrongQuoteFeedbackNamesQuoteAndKeepsRealRejection(){
        val old=proof(1,"我不吃香菜");val op=args(text="我对花生过敏",time=null,type=null).put("quote","我对花生过敏")
        rejected("USER_EVIDENCE"){ImmediateMemoryEvidence.quote(old,op)}
        val result=ToolArgumentDiagnosis.writeFailure("agent_memory_update",op.toString(),confirmation.snapshot,emptySet(),mapOf("success" to false,"error" to "USER_EVIDENCE"),ProcessingRejected("USER_EVIDENCE"),aliases(old))
        assertEquals("USER_EVIDENCE",result["error"]);assertTrue(result["detail"].toString().contains("quote"))
    }
    @Test fun leadingDateCannotBeCutOffAtACommaAndDateTokenCannotBeShortened(){
        val old=proof(1,"明天，我去看展")
        val op=args(text="我计划去看展",time=null,type="day").put("quote",old.snapshot)
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(op,ImmediateMemoryEvidence.quote(old,op),op.getString("content"))}
        val november=proof(1,"我的生日是11月20日")
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(args(text=november.snapshot,time="1月20日"),november,november.snapshot)}
        assertEquals(null to "11-20",ImmediateMemoryEvidence.dates(args(text=november.snapshot,time="11月20日"),november,november.snapshot))
    }
    @Test fun currentNonDateParaphraseDoesNotBorrowAnotherFactsDate(){
        val current=proof(3,"我喜欢喝茶。明天去看展。")
        val op=args(alias="S1",text="我爱喝茶",time=null,type=null)
        val fact=ImmediateMemoryEvidence.quote(current,op)
        assertEquals(null to null,ImmediateMemoryEvidence.dates(op,fact,"我爱喝茶"))
    }
    @Test fun inheritedHabitConflictFeedbackUsesTheValidatorReason(){
        val old=proof(1,"明天去看展");val raw=args(text=old.snapshot,time="明天",type=null)
        val effective=ImmediateMemoryEvidence.withDateType(raw,"habit")
        val error=runCatching{ImmediateMemoryEvidence.dates(effective,old,old.snapshot)}.exceptionOrNull() as ProcessingRejected
        val response=ToolArgumentDiagnosis.writeFailure("agent_memory_update",raw.toString(),old.snapshot,emptySet(),mapOf("success" to false,"error" to error.code),error,mapOf("S2" to old))
        assertTrue(response["detail"].toString().contains("date_type"));assertTrue(response["detail"].toString().contains("habit"))
    }
    @Test fun normalizedDateMustMatchTheSameVerbatimOccurrence(){
        val source=proof(1,"原定11月20日，改为１月２０日")
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(args(text=source.snapshot,time="1月20日"),source,source.snapshot)}
        assertEquals(null to "01-20",ImmediateMemoryEvidence.dates(args(text=source.snapshot,time="１月２０日"),source,source.snapshot))
    }
    @Test fun suppliedTimeStoresEffectiveDayEvenWithoutInheritedType(){
        assertEquals("day",ImmediateMemoryEvidence.withDateType(args(time="明天",type=null),null).getString("date_type"))
        assertFalse(ImmediateMemoryEvidence.withDateType(args(time=null,type=null),"birthday").has("date_type"))
    }
    @Test fun candidateConfirmationUsesProposalThenLaterOperationsUseJustWrittenVersion(){
        val original=StoredMemory("m","role",MemoryCategory.STATE_FACT,"旧生日",dateType="birthday",annualMonthDay="05-20",evidence=listOf(birthday))
        val proposed=original.copy(text="新候选生日",annualMonthDay="11-20")
        val confirmed=ImmediateMemoryEvidence.base(original,proposed)!!
        assertEquals("11-20",confirmed.annualMonthDay)
        val justWritten=confirmed.copy(text="本次明确改过的新正文",annualMonthDay="12-20",version=2)
        val afterSecondOperation=ImmediateMemoryEvidence.base(justWritten,proposed,true)!!
        assertEquals(justWritten,afterSecondOperation);assertEquals("12-20",afterSecondOperation.annualMonthDay)
        rejected("SOURCE_CHANGED"){ImmediateMemoryEvidence.base(null,proposed,true)}
    }
    @Test fun birthdayCanBeExtractedFromALongerSourceAndKeepOriginalClock(){
        val old=proof(1,"今天聊得很开心。我的生日是5月20日，你帮我记一下吧。")
        val op=args(text="生日为5月20日").put("quote","我的生日是5月20日")
        val proof=ImmediateMemoryEvidence.quote(ImmediateMemoryEvidence.select(op,aliases(old)),op)
        assertEquals(null to "05-20",ImmediateMemoryEvidence.dates(op,proof,op.getString("content")))
        assertEquals(old.source,proof.source);assertEquals(old.sourceAt,proof.sourceAt);assertEquals(old.zone,proof.zone)
        assertEquals(old.snapshot,proof.snapshot)
    }
    @Test fun selectedQuoteCannotMakeAPartialMonthIntoAFullDate(){
        val old=proof(1,"生日是11月20日，确认一下")
        val op=args(text="生日为1月20日",time="1月20日").put("quote","1月20日")
        val quoted=ImmediateMemoryEvidence.quote(old,op)
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(op,quoted,op.getString("content"))}
        val other=proof(2,"我不吃香菜")
        rejected("USER_EVIDENCE"){ImmediateMemoryEvidence.quote(other,args().put("quote","5月20日"))}
        val both=proof(1,"原定11月20日，改为1月20日")
        val misplaced=args(text="生日为1月20日",time="1月20日").put("quote","1月20日，改为")
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(misplaced,ImmediateMemoryEvidence.quote(both,misplaced),misplaced.getString("content"))}
    }
    @Test fun updateOnlyDatedQuoteStillRequiresDateMetadataWithoutChangingInheritedBody(){
        val old=proof(1,"明天去看展")
        val op=JSONObject().put("op","update").put("quote",old.snapshot)
        rejected("DATE_EVIDENCE"){ImmediateMemoryEvidence.dates(op,ImmediateMemoryEvidence.quote(old,op),"已保存正文",false)}
    }
}
