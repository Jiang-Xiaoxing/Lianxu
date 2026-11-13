package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** All IDs, bodies, clocks and credentials are synthetic. No external request. */
class CompanionPipelineAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store
    private lateinit var core:CompanionRepository
    private lateinit var ledger:ChatLedger
    private lateinit var database:String
    // 和聊天记录同一个钟：原话按真实时间落库，固定的假时间会让关系主动联系误以为好久没聊而抢先发出。
    private var now=System.currentTimeMillis()
    private val gson=Gson()
    private val config=ModelConfig("synthetic-companion","合成测试","custom","https://example.invalid/v1","synthetic",128000,2048)
    @Before fun setup()=runBlocking{
        database="companion-phase1-${System.nanoTime()}.db";store=Store(context,database);core=CompanionRepository(store){now};ledger=ChatLedger(store)
        store.add(listOf(Friend("a","合成角色甲","合成设定"),Friend("b","合成角色乙","合成设定")),false)
        core.setPolicy("a",true,CompanionRuntime.owner)
    }
    @After fun close(){store.close();context.deleteDatabase(database)}
    @Test fun packageDoesNotRequestOrRegisterForegroundServiceCapability(){
        val info=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.FOREGROUND_SERVICE"))
        assertFalse(info.services.orEmpty().any{it.name=="androidx.work.impl.foreground.SystemForegroundService"})
    }
    @Test fun legacyExpiryDoesNotCancelAnOpportunityBeforeModelReview():Unit=runBlocking{
        event("合成已经过期的事件",reconsider=true)
        val opportunity=core.queued().single();now=opportunity.expiresAt
        assertNotNull(core.claim(opportunity.id,CompanionRuntime.owner));assertEquals(2,store.messages().size)
    }
    @Test fun unresolvedControlCannotScheduleAContact():Unit=runBlocking{
        val input="更正那件事";core.beginInput("a");val t=ledger.begin("a",input,config.id)
        val p=ExperienceProposal(quote=input,controlChecked=true,reconsider=Reconsideration("不应变成回访",now+900000,now+1800000))
        val head=core.snapshot("a");assertTrue(core.accept(t,p,head){true});val after=core.snapshot("a")
        assertTrue(ledger.finishWithMemory(t.id,t.attempt,p.controlReply()!!,expectedStamp=ledger.context(t.id).stamp,onCompleted={core.finish(it,t,after,p)}){core.current(store.readableDatabase,after)})
        assertTrue(core.queued().isEmpty())
    }
    @Test fun malformedExtractionExhaustsRecoveryWithoutCoreMutation():Unit=runBlocking{
        val t=ledger.begin("a","请回查以前的杯子经历",config.id);var calls=0
        val model=CompanionModel(object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success("{}")} })
        try{model.interpret(config,"synthetic-only","请回查以前的杯子经历",core.snapshot("a"),now);fail("invalid extraction must fail")}
        catch(e:CompanionModelFailure){assertEquals(ChatFailure.STRUCTURED_FORMAT,e.failure)}
        assertEquals(3,calls);assertTrue(core.experiences("a").isEmpty());assertTrue(core.queued().isEmpty())
        assertEquals(1,store.messages().size);assertNull(ledger.all().single{it.id==t.id}.assistantMessageId)
    }
    private suspend fun event(text:String,role:String="a",reconsider:Boolean=false,relation:String="none",reply:String="合成回复"):ChatTurn {
        core.beginInput(role)
        val t=ledger.begin(role,text,config.id)
        val p=ExperienceProposal(quote=text,memory=text,emotion="happy",valence=1,relationship=relation,reconsider=if(reconsider)Reconsideration("合成事件结束后关心",now+900_000,now+7_200_000)else null)
        val s=core.snapshot(role);assertTrue(core.accept(t,p,s){true});assertTrue(core.accept(t,p,s){true})
        val after=core.snapshot(role)
        assertTrue(ledger.finishWithMemory(t.id,t.attempt,reply,expectedStamp=ledger.context(t.id).stamp,onCompleted={core.finish(it,t,after,p)}){core.current(store.readableDatabase,after)})
        return t
    }
    @Test fun completeFlowIdempotentSourceJobMessageNotificationAndRead():Unit=runBlocking{
        val t=event("明天合成面试，谢谢",reconsider=true,relation="appreciation")
        assertEquals(1,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM companion_sources",null).use{it.moveToFirst();it.getInt(0)})
        assertTrue(core.snapshot("a").prompt.contains("1条明确感谢"))
        assertNull(core.claim(core.queued().single().id,CompanionRuntime.owner))
        now+=900_000
        val o=core.claim(core.queued().single().id,CompanionRuntime.owner)!!
        assertNull(core.claim(o.id,CompanionRuntime.owner))
        val s=core.snapshot("a")
        val model=CompanionModel(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            assertEquals("synthetic-only",key);val prompt=request.messages.single().content
            assertFalse(prompt.contains("不催促"));assertFalse(prompt.contains("主动交流语气"));assertFalse(prompt.contains("当前关系小结"))
            assertTrue(prompt.contains("[你答应过的回访]"));assertTrue(prompt.contains("你当时留下的原话：合成事件结束后关心"))
            return TextChatResult.Success("""{"action":"send","reason":"合成面试后的有效关心","text":"合成面试结束了，想说说吗？"}""")
        }})
        val decision=model.reconsider(config,"synthetic-only",o,s,"合成角色设定",now,"没有可见回执")
        val id=core.conclude(o,CompanionRuntime.owner,decision,s){true}!!
        assertNull(core.conclude(o,CompanionRuntime.owner,decision,s){true})
        assertEquals("合成面试结束了，想说说吗？",store.messages().single{it.id==id}.text)
        assertNull(core.receipts("a").single{it.messageId==id}.readAt)
        assertTrue(core.claimNotification(id));assertFalse(core.claimNotification(id))
        core.delivery(id,"unknown");assertFalse(core.claimNotification(id))
        core.clicked("a",id);assertNull(core.receipts("a").single{it.messageId==id}.readAt)
        core.markRead("b",id);assertNull(core.receipts("a").single{it.messageId==id}.readAt)
        core.markRead("a",id);assertEquals(now,core.receipts("a").single{it.messageId==id}.readAt)
        assertEquals(t.userMessageId,core.sourceOfMessage("a",id))
    }
    @Test fun forgettingRebuildsMixedProjectionAndExcludesRawReExtraction():Unit=runBlocking{
        val a=event("合成秘密甲");val b=event("合成爱好乙")
        val before=core.snapshot("a");assertTrue(before.prompt.contains("合成秘密甲"));assertTrue(before.prompt.contains("合成爱好乙"))
        core.forget("a",a.userMessageId)
        val after=core.snapshot("a");assertFalse(after.prompt.contains("合成秘密甲"));assertTrue(after.prompt.contains("合成爱好乙"))
        assertFalse(core.current(before))
        assertTrue(ProcessingRepository(store).sources("a").isEmpty()) // mixed old assistant context is conservatively excluded
        assertTrue(ledger.completedMessages("a").isEmpty())
        assertTrue(store.messages().any{it.text=="合成秘密甲"})
        assertEquals(1,core.recall("a",listOf("爱好"),5).size)
        assertTrue(core.recall("a",listOf("秘密"),5).isEmpty())
        assertEquals(0,core.recall("b",listOf("爱好"),5).size)
        assertNotEquals(a.userMessageId,b.userMessageId)
    }
    @Test fun forgottenSourceCannotReturnThroughManualHistoryOrPendingCandidate():Unit=runBlocking{
        val t=event("合成不再使用的事实")
        val repo=ProcessingRepository(store);val source=repo.sources("a").single()
        val original=StoredMemory("historical-private","a",MemoryCategory.EXPERIENCE_MEMORY,"合成不再使用的事实",about=MemoryAbout.USER,
            evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        repo.tx{repo.saveMemory(it,original)}
        repo.manualSave("a",original.id,original.text,original.category,false,true,MemoryAbout.USER)
        val manual=repo.memories("a").single();assertTrue(manual.evidence.isEmpty())
        repo.tx{repo.candidate(it,"a",manual.id,manual,"合成人工历史候选","old-candidate")}
        core.forget("a",t.userMessageId)
        assertFalse(repo.memories("a").single().valid)
        assertFalse(repo.candidates("a").single().valid)
        assertTrue(runCatching{repo.restore("a",manual.id,manual.version)}.isFailure)
        assertTrue(runCatching{repo.resolveCandidate("a","old-candidate",true,repo.memories("a").single().version)}.isFailure)
        assertTrue(repo.read{repo.usableMemories(it,"a").isEmpty()})
        assertTrue(store.messages().any{it.text==original.text})
    }
    @Test fun lateBackgroundResultCannotWriteAfterForgetOrPolicyChange():Unit=runBlocking{
        val t=event("合成待关心事件",reconsider=true);now+=900_000
        val o=core.claim(core.queued().single().id,CompanionRuntime.owner)!!;val s=core.snapshot("a")
        core.forget("a",t.userMessageId)
        assertNull(core.conclude(o,CompanionRuntime.owner,OpportunityDecision("send","旧模型迟到","不应保存"),s){true})
        assertFalse(store.messages().any{it.text=="不应保存"})
        event("合成第二事件",reconsider=true);now+=900_000
        val p=core.claim(core.queued().single().id,CompanionRuntime.owner)!!;val snap=core.snapshot("a")
        core.setPolicy("a",false,CompanionRuntime.owner)
        assertNull(core.conclude(p,CompanionRuntime.owner,OpportunityDecision("send","许可已撤回","不应通知"),snap){true})
    }
    @Test fun reminderCancellationKeepsMemoryAndCorrectedSourceRevokesAffect():Unit=runBlocking{
        val t=event("合成旧事件",reconsider=true,relation="appreciation")
        core.stopReminder("a",t.userMessageId);assertTrue(core.queued().isEmpty());assertTrue(core.snapshot("a").prompt.contains("合成旧事件"))
        val changes=SourceChanges(ProcessingRepository(store));changes.confirm(changes.preview("a",t.userMessageId,"user","合成更正事件",false))
        assertFalse(core.snapshot("a").prompt.contains("合成旧事件"));assertTrue(core.snapshot("a").prompt.contains("0条明确感谢"))
        assertTrue(ProcessingRepository(store).sources("a").single().user.text.contains("更正"))
    }
    @Test fun deferPreservesExpirySilentStopsAndModelSwitchRejects():Unit=runBlocking{
        event("合成延期事件",reconsider=true);now+=900_000
        val first=core.claim(core.queued().single().id,CompanionRuntime.owner)!!;val s=core.snapshot("a")
        core.conclude(first,CompanionRuntime.owner,OpportunityDecision("defer","等事情结束",nextAt=now+900_000),s){true}
        assertEquals(first.expiresAt,core.queued().single().expiresAt);assertNull(core.claim(first.id,CompanionRuntime.owner))
        now+=900_000;val next=core.claim(first.id,CompanionRuntime.owner)!!
        assertNull(core.conclude(next,CompanionRuntime.owner,OpportunityDecision("send","模型切换","不应写入"),core.snapshot("a")){false})
        core.conclude(next,CompanionRuntime.owner,OpportunityDecision("silent","没有新线索"),core.snapshot("a")){true}
        assertTrue(core.queued().isEmpty());assertFalse(store.messages().any{it.text=="不应写入"})
    }
    @Test fun interruptedProcessPreservesPermissionAndFutureOpportunity():Unit=runBlocking{
        event("合成重启事件",reconsider=true)
        val before=core.queued().single()
        core.checkContinuity("new-synthetic-process")
        core.checkContinuity("new-synthetic-process")
        assertEquals("enabled",core.policy("a"));assertEquals(before,core.queued().single())
    }
    @Test fun recoveredRunningAttemptRejectsOldReturnAndCommitsOnlyOnce():Unit=runBlocking{
        event("合成恢复中的事件",reconsider=true);now+=900_000
        val old=core.claim(core.queued().single().id,CompanionRuntime.owner)!!;val stale=core.snapshot("a")
        core.checkContinuity("new-synthetic-process");core.checkContinuity("new-synthetic-process")
        assertEquals(old.notBefore,core.queued().single().notBefore);assertEquals(old.expiresAt,core.queued().single().expiresAt)
        assertNull(core.conclude(old,CompanionRuntime.owner,OpportunityDecision("send","旧尝试迟到","旧结果"),stale){true})
        val fresh=core.claim(old.id,"new-synthetic-process")!!
        assertTrue(fresh.attempt>old.attempt);assertFalse(core.current(stale))
        val snapshot=core.snapshot("a");val decision=OpportunityDecision("send","恢复后重新判断","唯一正式消息")
        val message=core.conclude(fresh,"new-synthetic-process",decision,snapshot){true}!!
        assertNull(core.conclude(fresh,"new-synthetic-process",decision,snapshot){true})
        assertTrue(core.claimNotification(message))
        core.checkContinuity("third-synthetic-process")
        assertFalse(core.claimNotification(message));assertEquals("unknown",core.receipts("a").single{it.messageId==message}.delivery)
        assertNull(core.claim(old.id,"third-synthetic-process"));assertEquals(1,store.messages().count{it.text=="唯一正式消息"})
    }
    @Test fun unrelatedChatPreservesFutureReasonAndUsesLatestContext():Unit=runBlocking{
        event("明天合成面试",reconsider=true);val future=core.queued().single()
        event("合成新消息：面试地点改到北门")
        assertEquals(future,core.queued().single())
        now=future.notBefore
        val claimed=core.claim(future.id,CompanionRuntime.owner)!!;val latest=core.snapshot("a")
        assertEquals(latest.epoch,claimed.epoch);assertTrue(latest.prompt.contains("北门"))
        assertNotEquals(future.epoch,claimed.epoch)
        assertNotNull(core.conclude(claimed,CompanionRuntime.owner,OpportunityDecision("send","按最新地点判断","北门的面试怎么样"),latest){true})
    }
    @Test fun newInputRequeuesRunningAndPendingInputDoesNotCancelIt():Unit=runBlocking{
        event("合成正在判断的未来关心",reconsider=true);now+=900_000
        val old=core.claim(core.queued().single().id,CompanionRuntime.owner)!!;val stale=core.snapshot("a")
        core.beginInput("a");val pending=ledger.begin("a","合成普通新聊天",config.id)
        assertNull(core.conclude(old,CompanionRuntime.owner,OpportunityDecision("send","旧快照","旧结果"),stale){true})
        assertNull(core.claim(old.id,CompanionRuntime.owner));assertEquals(old.id,core.queued().single().id)
        assertEquals(old.notBefore,core.queued().single().notBefore);assertEquals(old.expiresAt,core.queued().single().expiresAt)
        ledger.cancel(pending.id,pending.attempt)
        val fresh=core.claim(old.id,CompanionRuntime.owner)!!;assertTrue(fresh.attempt>old.attempt)
        assertNotNull(core.conclude(fresh,CompanionRuntime.owner,OpportunityDecision("send","新快照重新评估","新结果"),core.snapshot("a")){true})
        assertFalse(store.messages().any{it.text=="旧结果"})
    }
    @Test fun closeReopenWithdrawalRevisionAndExpiryDoNotReviveReasons():Unit=runBlocking{
        event("合成关闭前机会",reconsider=true);val closed=core.queued().single()
        core.setPolicy("a",false,CompanionRuntime.owner);core.setPolicy("a",true,CompanionRuntime.owner)
        core.checkContinuity("new-synthetic-process");assertNull(core.claim(closed.id,"new-synthetic-process"))
        val forgotten=event("合成撤回机会",reconsider=true);val withdrawn=core.queued().single()
        core.forget("a",forgotten.userMessageId);core.checkContinuity("new-synthetic-process")
        assertNull(core.claim(withdrawn.id,"new-synthetic-process"))
        val revised=event("合成更正前机会",reconsider=true);val obsolete=core.queued().single()
        val changes=SourceChanges(ProcessingRepository(store));changes.confirm(changes.preview("a",revised.userMessageId,"user","合成更正后",false))
        assertNull(core.claim(obsolete.id,"new-synthetic-process"))
        event("合成过期机会",reconsider=true);val expired=core.queued().single();now=expired.expiresAt
        core.checkContinuity("third-synthetic-process");assertNotNull(core.claim(expired.id,"third-synthetic-process"));assertTrue(core.queued().isEmpty())
    }
    @Test fun cancelledAttemptAndCrossRoleSourceCannotCreateExperience():Unit=runBlocking{
        val t=ledger.begin("a","合成取消输入",config.id);val s=core.snapshot("a")
        assertFalse(core.accept(t,ExperienceProposal("合成取消输入","合成取消输入"),s){false})
        ledger.cancel(t.id,t.attempt)
        assertFalse(ledger.finish(t.id,t.attempt,"合成迟到回复"))
        assertTrue(store.messages().any{it.text=="合成取消输入"});assertFalse(core.snapshot("a").prompt.contains("合成取消输入"))
        try{core.forget("b",t.userMessageId);fail("cross-role source must reject")}catch(_:IllegalArgumentException){}
    }
    @Test fun realWorkerNetworkFailurePreservesSourceWithoutNewMessage():Unit=runBlocking{
        event("合成断网事件",reconsider=true);now+=900_000
        val opportunity=core.queued().single()
        val oldDb=AppServices.databaseName;val oldNamespace=AppServices.vaultNamespace;val oldClient=AppServices.createTextClient;val oldClock=AppServices.companionClock
        val namespace="synthetic-companion-offline-${System.nanoTime()}"
        val vault=ModelVault(context,namespace,store);vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
        var calls=0
        try{
            AppServices.databaseName=database;AppServices.vaultNamespace=namespace;AppServices.companionClock={now}
            AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{assertEquals("synthetic-only",key);calls++;return TextChatResult.Failure(ChatFailure.NETWORK)}}}
            val worker=TestListenableWorkerBuilder<CompanionWorker>(context).setInputData(Data.Builder().putString("opportunity",opportunity.id).build()).build()
            worker.doWork()
            assertEquals(1,calls);assertEquals(2,store.messages().size);assertTrue(core.snapshot("a").prompt.contains("合成断网事件"));assertTrue(core.queued().none{it.id==opportunity.id})
            assertEquals("failed",store.readableDatabase.rawQuery("SELECT state FROM companion_opportunities WHERE id=?",arrayOf(opportunity.id)).use{it.moveToFirst();it.getString(0)})
            assertEquals(1,core.retryable("a"));core.retryFailed("a")
            assertEquals(opportunity.expiresAt,core.queued().single{it.id==opportunity.id}.expiresAt)
        }finally{AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldNamespace;AppServices.createTextClient=oldClient;AppServices.companionClock=oldClock;vault.delete(config.id)}
    }
    @Test fun workerEarlyReturnAfterClaimDoesNotLeaveRunning():Unit=runBlocking{
        event("合成领取后快照变化",reconsider=true);now+=900_000
        val opportunity=core.queued().single()
        // A deterministic interleaving: execution generation changes just after the claim.
        store.writableDatabase.execSQL("CREATE TRIGGER synthetic_claim_change AFTER UPDATE OF state ON companion_opportunities WHEN NEW.state='running' BEGIN UPDATE companion_state SET epoch=epoch+1 WHERE role=NEW.role; END")
        val oldDb=AppServices.databaseName;val oldNamespace=AppServices.vaultNamespace;val oldClient=AppServices.createTextClient;val oldClock=AppServices.companionClock
        val vault=ModelVault(context,"synthetic-worker-early-${System.nanoTime()}",store)
        vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
        var calls=0
        try{
            AppServices.databaseName=database;AppServices.vaultNamespace=vault.statusNamespace;AppServices.companionClock={now}
            AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;error("must return before calling a model")}}}
            TestListenableWorkerBuilder<CompanionWorker>(context).setInputData(Data.Builder().putString("opportunity",opportunity.id).build()).build().doWork()
            val state=store.readableDatabase.rawQuery("SELECT state FROM companion_opportunities WHERE id=?",arrayOf(opportunity.id)).use{it.moveToFirst();it.getString(0)}
            assertEquals(0,calls);assertNotEquals("running",state);assertEquals("queued",state)
        }finally{
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("companion:a").result.get()
            AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldNamespace;AppServices.createTextClient=oldClient;AppServices.companionClock=oldClock;vault.delete(config.id)
        }
    }
    @Test fun sourceAndTombstoneSurviveBackupWithoutRevivingJobs():Unit=runBlocking{
        val t=event("合成撤回备份事件",reconsider=true);core.forget("a",t.userMessageId)
        val vault=ModelVault(context,"synthetic-companion-backup-${System.nanoTime()}",store)
        val backup=BackupRepository(store,vault);val exported=backup.export("a",false)
        val bytes=java.io.ByteArrayOutputStream().also{BackupArchive.write(exported.root,exported.files,it)}.toByteArray()
        val preview=backup.preview(BackupArchive.read(bytes.inputStream()),"ADD",null)
        val id=backup.commit(preview,true,ChatAttemptLease()).single()
        assertFalse(core.snapshot(id).prompt.contains("合成撤回备份事件"));assertTrue(ledger.completedMessages(id).isEmpty());assertEquals("awaiting_user_resume",core.policy(id))
    }
    @Test fun compactReadStatesStayLinkedAndAppearOnceInReplyAndContact():Unit=runBlocking{
        suspend fun sendContact(words:String):Long {
            now+=RelationshipProjector.DAY // Keep both synthetic contacts within the existing daily quota.
            event("合成回访来源：$words",reconsider=true);now+=900_000
            val o=core.claim(core.queued().single().id,CompanionRuntime.owner)!!
            return core.conclude(o,CompanionRuntime.owner,OpportunityDecision("send","合成联系",words),core.snapshot("a")){true}!!
        }
        val first=sendContact("第一条合成主动消息");val second=sendContact("第二条合成主动消息")
        val stateBefore=core.relationship("a").state
        val unread=core.contactFacts("a",now)
        assertTrue(unread.contains("回复 $first：未读"));assertTrue(unread.contains("回复 $second：未读"))
        core.markRead("a",first){false};core.markRead("b",first)
        assertEquals(unread,core.contactFacts("a",now))
        core.markRead("a",first);val readAt=core.receipts("a").single{it.messageId==first}.readAt
        assertEquals(stateBefore,core.relationship("a").state)
        now+=1000;core.markRead("a",first);assertEquals(readAt,core.receipts("a").single{it.messageId==first}.readAt)
        // A later user reply neither clears the read marker nor marks the other message read.
        event("我回复另一件事")
        val facts=core.contactFacts("a",now)
        assertTrue(facts.contains("回复 $first：已读"));assertTrue(facts.contains("回复 $second：未读"))
        assertFalse(facts.contains("第一条合成主动消息"));assertFalse(facts.contains("不代表"))
        val snapshots=mutableListOf<Pair<Boolean,CompanionSnapshot>>()
        for(adaptive in listOf(false,true)){
            ProcessingRepository(store).saveMemoryRules(MemoryRules(adaptiveContext=adaptive))
            snapshots+=adaptive to core.snapshot("a")
        }
        // The Worker reads facts after taking its snapshot; a later read marker must win.
        core.markRead("a",second);val latestFacts=core.contactFacts("a",now)
        assertTrue(latestFacts.contains("回复 $second：已读"))
        for((adaptive,snapshot) in snapshots){
            val refs=if(adaptive)CompanionPrompt.replyReferences(snapshot.references)else listOf(PromptReference("经历",snapshot.replyPrompt,true))
            val request=(CompanionPrompt.build(Friend("a","合成角色甲","合成设定"),RolePrompts(),emptyList(),"当前用户原话",refs,capacity=128000,adaptive=adaptive) as PromptBuild.Ready).request
            val ordinary=request.messages.filter{it.role=="system"}.joinToString("\n"){it.content}
            assertEquals(1,Regex.fromLiteral(facts).findAll(ordinary).count())
            facts.lines().forEach{line->assertEquals(line,1,Regex.fromLiteral(line).findAll(ordinary).count())}
            val ordinaryWithoutFacts=ordinary.replace(facts,"").replace(ExperiencePresentation.READ_STATE_RULE,"")
            assertFalse(ordinaryWithoutFacts.contains("已读"));assertFalse(ordinaryWithoutFacts.contains("未读"))
            assertEquals("当前用户原话",request.latestUserText())
            assertFalse(snapshot.proactivePrompt.contains(facts))
            val opportunity=CompanionOpportunity("relationship:a:test","a",ledger.all().last().userMessageId,1,0,0,now,now+900000,"","running",1)
            var calls=0
            val model=CompanionModel(object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                calls++;val text=request.messages.joinToString("\n"){it.content}
                assertEquals(1,Regex.fromLiteral(latestFacts).findAll(text).count())
                latestFacts.lines().forEach{line->assertEquals(line,1,Regex.fromLiteral(line).findAll(text).count())}
                assertFalse(text.contains("回复 $second：未读"))
                // General rules are not assertions about a message; every actual state must be in facts.
                val withoutFacts=text.replace(latestFacts,"").replace(ExperiencePresentation.READ_STATE_RULE,"")
                    .replace("未读/没回复不等于故意不理","")
                assertFalse(withoutFacts.contains("已读"));assertFalse(withoutFacts.contains("未读"))
                assertTrue(text.contains("你（回复 $first）：第一条合成主动消息"));assertTrue(text.contains("你（回复 $second）：第二条合成主动消息"))
                assertFalse(text.contains("不代表对方读过"))
                return TextChatResult.Success("""{"action":"silent","reason":"合成先不打扰"}""")
            }})
            model.reconsider(config,"synthetic-only",opportunity,snapshot,snapshot.roleProfile,now,latestFacts,recent=ProactiveMaterial.recent(core.contactHistory("a")))
            assertEquals(1,calls)
        }
    }
    private suspend fun workerScenario(block:suspend(CompanionOpportunity,ModelVault)->Unit){
        event("合成Worker恢复与调度",reconsider=true);now+=900_000
        val o=core.queued().single()
        val oldDb=AppServices.databaseName;val oldNamespace=AppServices.vaultNamespace;val oldClient=AppServices.createTextClient;val oldClock=AppServices.companionClock
        val vault=ModelVault(context,"synthetic-worker-continuity-${System.nanoTime()}",store)
        try{
            vault.save(config,"synthetic-only");vault.setPurpose(ModelPurpose.CHAT,config.id)
            AppServices.databaseName=database;AppServices.vaultNamespace=vault.statusNamespace;AppServices.companionClock={now}
            AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("Unexpected synthetic model call")}}
            block(o,vault)
        }finally{
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("companion:a").result.get()
            AppServices.databaseName=oldDb;AppServices.vaultNamespace=oldNamespace;AppServices.createTextClient=oldClient;AppServices.companionClock=oldClock;vault.delete(config.id)
        }
    }
    // 后台跑一次会顺手排上关系主动联系（relationship: 开头），这里只看事件自己那一条。
    private fun worker(id:String)=TestListenableWorkerBuilder<CompanionWorker>(context).setInputData(Data.Builder().putString("opportunity",id).build()).build()
    private fun opportunityState(id:String)=store.readableDatabase.rawQuery("SELECT state FROM companion_opportunities WHERE id=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)}
    @Test fun laterWorkerExcludesForgottenDialogueAndItsDerivedRepliesWithoutDeletingRawChat():Unit=runBlocking{
        val allowed=event("仍可使用的合成话题哨兵").let{initial->ledger.all().single{it.id==initial.id}}
        val forgotten=event("已经忘记的合成秘密哨兵",reply="忘记前对秘密的合成回复哨兵").let{initial->ledger.all().single{it.id==initial.id}}
        val derivative=event("仍可独立使用的合成后续事实",reply="衍生回复重述：已经忘记的合成秘密哨兵").let{initial->ledger.all().single{it.id==initial.id}}
        for(turn in listOf(allowed,forgotten,derivative))assertNotNull("检查实际持久化后的助手消息",turn.assistantMessageId)
        event("另一个角色的合成私话哨兵",role="b")
        core.forget("a",forgotten.userMessageId)
        assertTrue("独立的用户事实仍有效",core.snapshot("a","仍可独立使用的合成后续事实").prompt.contains("仍可独立使用的合成后续事实"))
        val history=core.contactHistory("a")
        assertTrue(history.any{it.id==allowed.userMessageId})
        assertFalse(history.any{it.id in setOf(forgotten.userMessageId,forgotten.assistantMessageId,derivative.userMessageId,derivative.assistantMessageId)})
        workerScenario{o,_->
            var calls=0
            AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                calls++;val text=request.messages.joinToString("\n"){it.content}
                assertTrue("仍可使用的合成话题哨兵" in text)
                assertTrue("合成Worker恢复与调度" in text)
                for(blocked in listOf("已经忘记的合成秘密哨兵","忘记前对秘密的合成回复哨兵","另一个角色的合成私话哨兵"))assertFalse("不得重新提供：$blocked",blocked in text)
                return TextChatResult.Success("""{"action":"silent","reason":"合成检查后不打扰"}""")
            }}}
            worker(o.id).doWork();assertEquals(1,calls);assertEquals("silent",opportunityState(o.id))
        }
        val raw=store.messages()
        for(turn in listOf(allowed,forgotten,derivative)){
            assertTrue(raw.any{it.id==turn.userMessageId});assertTrue(raw.any{it.id==turn.assistantMessageId})
        }
        assertTrue(raw.any{it.text=="已经忘记的合成秘密哨兵"})
    }
    @Test fun oldContactWithoutUseLedgerStillHonoursTheForgottenOrigin():Unit=runBlocking{
        val turn=event("旧主动消息的合成私密来源",reconsider=true);now+=900_000
        val opportunity=core.claim(core.queued().single().id,CompanionRuntime.owner)!!
        val sent=core.conclude(opportunity,CompanionRuntime.owner,OpportunityDecision("send","合成检查","由秘密衍生的旧主动消息"),core.snapshot("a")){true}!!
        // Simulate a legacy receipt predating the per-contact use ledger, in this synthetic DB only.
        store.writableDatabase.delete("companion_uses","role=? AND turn_source=?",arrayOf("a",sent.toString()))
        assertTrue(core.contactHistory("a").any{it.id==sent})
        core.forget("a",turn.userMessageId)
        assertFalse(core.contactHistory("a").any{it.id==sent})
        assertTrue(store.messages().any{it.id==sent&&it.text=="由秘密衍生的旧主动消息"})
    }
    @Test fun workerReceivesUserRelationshipAndExpressionFromOneSnapshot():Unit=runBlocking{workerScenario{o,_->
        store.savePersona(UserPersona("合成用户","小星","用户背景哨兵"))
        store.updateRolePrompts("a"){it.copy(initialRelationship="{{character}}和{{user}}是旧识",expression="表达哨兵",extra="附加哨兵")}
        var calls=0
        AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls++;val text=request.messages.joinToString{it.content}
            for(value in listOf("用户背景哨兵","合成角色甲和小星是旧识","表达哨兵","附加哨兵"))assertTrue(value in text)
            assertFalse("{{user}}" in text);assertFalse("{{character}}" in text)
            return TextChatResult.Success("""{"action":"silent","reason":"当前不打扰"}""")
        }}}
        worker(o.id).doWork();assertEquals(1,calls);assertEquals("silent",opportunityState(o.id))
    }}
    @Test fun workerCannotPublishAResultAfterUserProfileChanges():Unit=runBlocking{workerScenario{o,_->
        store.savePersona(UserPersona("合成用户","旧称呼","旧资料"))
        AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            store.savePersona(UserPersona("合成用户","新称呼","新资料"))
            return TextChatResult.Success("""{"action":"send","reason":"旧人设判断","text":"不应保存的人设旧结果"}""")
        }}}
        worker(o.id).doWork();assertFalse(store.messages().any{it.text=="不应保存的人设旧结果"})
        assertNotEquals("send",opportunityState(o.id))
    }}
    @Test fun workerChecksFullProfileCapacityBeforeCallingModel():Unit=runBlocking{workerScenario{o,vault->
        store.savePersona(UserPersona(introduction="介".repeat(4000)))
        vault.save(config.copy(contextTokens=4096,outputTokens=1024),"synthetic-only")
        var calls=0
        AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Failure(ChatFailure.NETWORK)}}}
        worker(o.id).doWork();assertEquals(0,calls);assertEquals("failed",opportunityState(o.id))
    }}
    @Test fun duplicateWorkersCreateOneFormalMessageAndOneReceipt():Unit=runBlocking{workerScenario{o,_->
        val calls=java.util.concurrent.atomic.AtomicInteger()
        AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls.incrementAndGet();assertEquals("synthetic-only",key)
            return TextChatResult.Success("""{"action":"send","reason":"合成重新评估","text":"合成唯一Worker正文"}""")
        }}}
        coroutineScope{listOf(async(Dispatchers.Default){worker(o.id).doWork()},async(Dispatchers.Default){worker(o.id).doWork()}).awaitAll()}
        assertEquals(1,calls.get());assertEquals(1,store.messages().count{it.text=="合成唯一Worker正文"})
        assertEquals("send",opportunityState(o.id))
        assertEquals(1,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM companion_receipts WHERE opportunity_id=?",arrayOf(o.id)).use{it.moveToFirst();it.getInt(0)})
    }}
    @Test fun workerCancellationRequeuesAndFencesUncooperativeOldReturn():Unit=runBlocking{workerScenario{o,_->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            entered.complete(Unit);withContext(NonCancellable){release.await()}
            return TextChatResult.Success("""{"action":"send","reason":"已取消的旧结果","text":"合成不应提交"}""")
        }}}
        coroutineScope{
            val job=launch(Dispatchers.Default){worker(o.id).doWork()}
            try{withTimeout(5000){entered.await()};job.cancel();release.complete(Unit);job.join()}
            finally{release.complete(Unit);job.cancelAndJoin()}
        }
        assertEquals("queued",opportunityState(o.id));assertFalse(store.messages().any{it.text=="合成不应提交"})
        assertEquals(o.notBefore,core.queued().single{it.id==o.id}.notBefore);assertEquals(o.expiresAt,core.queued().single{it.id==o.id}.expiresAt)
    }}
    @Test fun workerPendingForegroundUsesBackoffAndKeepsReason():Unit=runBlocking{workerScenario{o,_->
        val pending=ledger.begin("a","合成忙碌中的新输入",config.id)
        assertEquals(androidx.work.ListenableWorker.Result.retry(),worker(o.id).doWork())
        assertEquals("queued",opportunityState(o.id));assertEquals(o.notBefore,core.queued().single().notBefore)
        ledger.cancel(pending.id,pending.attempt)
    }}
    @Test fun workerRecoversOrphanForegroundWithoutAppActivity():Unit=runBlocking{workerScenario{o,_->
        val orphan=ChatLedger(store,"old-synthetic-process").begin("a","合成旧进程未完成输入",config.id)
        AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("""{"action":"silent","reason":"按最新有效经历保持安静"}""")}}
        worker(o.id).doWork()
        assertEquals(TurnState.INTERRUPTED,ledger.all().single{it.id==orphan.id}.state)
        assertEquals("silent",opportunityState(o.id));assertEquals(3,store.messages().size)
    }}
    @Test fun missingWorkerConfigTerminatesInsteadOfLeavingRunning():Unit=runBlocking{workerScenario{o,vault->
        vault.setPurpose(ModelPurpose.CHAT,"")
        worker(o.id).doWork();assertEquals("failed",opportunityState(o.id));assertEquals(2,store.messages().size)
    }}
    @Test fun commitRechecksExecutionCancellationAndKeepsFutureReason():Unit=runBlocking{
        event("合成提交窗口取消",reconsider=true);now+=900_000
        val old=core.claim(core.queued().single().id,CompanionRuntime.owner)!!;val snapshot=core.snapshot("a")
        val decision=OpportunityDecision("send","提交前已经取消","合成不能提交的正文")
        assertNull(core.conclude(old,CompanionRuntime.owner,decision,snapshot,executionCurrent={false}){true})
        assertEquals("queued",opportunityState(old.id));assertEquals(old.notBefore,core.queued().single().notBefore)
        assertNull(core.conclude(old,CompanionRuntime.owner,decision,snapshot){true})
        assertFalse(store.messages().any{it.text==decision.text})
    }
    @Test fun recoveredSuccessorActuallyRunsThroughWorkManager():Unit=runBlocking{workerScenario{o,_->
        core.claim(o.id,"old-synthetic-worker")!!
        core.checkContinuity(CompanionRuntime.owner)
        val recovered=core.queued().single();val calls=java.util.concurrent.atomic.AtomicInteger()
        AppServices.createTextClient={object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls.incrementAndGet();assertEquals("synthetic-only",key)
            return TextChatResult.Success("""{"action":"send","reason":"真实调度恢复后重新评估","text":"合成调度后的唯一正文"}""")
        }}}
        CompanionScheduler.enqueue(context,core);CompanionScheduler.enqueue(context,core)
        val wm=androidx.work.WorkManager.getInstance(context)
        val name="companion:${recovered.id}:${recovered.attempt}:${recovered.notBefore}"
        val info=withTimeout(90_000){
            var latest=wm.getWorkInfosForUniqueWork(name).get().single()
            while(!latest.state.isFinished){delay(500);latest=wm.getWorkInfoById(latest.id).get()!!}
            latest
        }
        assertEquals(androidx.work.WorkInfo.State.SUCCEEDED,info.state);assertEquals(1,calls.get())
        assertEquals(1,store.messages().count{it.text=="合成调度后的唯一正文"});assertEquals("send",opportunityState(o.id))
    }}
    @Test fun schedulerKeepsOneWorkAndSchedulesRecoveredAndDeferredSuccessors():Unit=runBlocking{workerScenario{o,_->
        val wm=androidx.work.WorkManager.getInstance(context)
        fun name(v:CompanionOpportunity)="companion:${v.id}:${v.attempt}:${v.notBefore}"
        now=o.notBefore-900_000
        CompanionScheduler.enqueue(context,core);CompanionScheduler.enqueue(context,core)
        val original=wm.getWorkInfosForUniqueWork(name(o)).get().single()
        now=o.notBefore
        val old=core.claim(o.id,CompanionRuntime.owner)!!
        core.checkContinuity("recovered-synthetic-process")
        val recovered=core.queued().single{it.id==o.id};CompanionScheduler.enqueue(context,core);CompanionScheduler.enqueue(context,core)
        val successor=wm.getWorkInfosForUniqueWork(name(recovered)).get().single();assertNotEquals(original.id,successor.id)
        val fresh=core.claim(o.id,"recovered-synthetic-process")!!
        core.conclude(fresh,"recovered-synthetic-process",OpportunityDecision("defer","合成再等一会",nextAt=now+900_000),core.snapshot("a")){true}
        val deferred=core.queued().single{it.id==o.id};CompanionScheduler.enqueue(context,core);CompanionScheduler.enqueue(context,core)
        val next=wm.getWorkInfosForUniqueWork(name(deferred)).get().single();assertNotEquals(successor.id,next.id)
        assertEquals(o.expiresAt,deferred.expiresAt);assertTrue(fresh.attempt>old.attempt)
    }}
}
