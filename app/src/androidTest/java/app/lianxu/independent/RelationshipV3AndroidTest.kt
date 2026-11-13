package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RelationshipV3AndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val day=RelationshipProjector.DAY
    private suspend fun seed(store:Store,core:CompanionRepository,at:Long,text:String,make:(SourceRef)->RelationInput):SourceRef {
        val ledger=ChatLedger(store);val turn=ledger.begin("r",text,"synthetic");val ref=core.sourceFor(turn);val snapshot=core.snapshot("r")
        val proposal=ExperienceProposal(relationInput=make(ref),relationAttempted=true,timingReferenceAt=at)
        assertTrue(core.accept(turn,proposal,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"))
        // 先把时间改成合成时刻再收尾：收尾时会记下当前感觉，晚改时间会让它按真实时间多记一次联系。
        ProcessingRepository(store).tx{it.execSQL("UPDATE messages SET created=? WHERE id=? OR id=(SELECT assistant_message_id FROM chat_turns WHERE id=?)",arrayOf<Any>(at,ref.turnId,turn.id));core.finish(it,turn,snapshot,proposal)}
        return ref
    }
    @Test fun historyIncrementalCorrectionUndoRestoreAndRestart():Unit=runBlocking{
        val name="relationship-v3-history-${System.nanoTime()}.db";var store=Store(context,name);var now=2_000_000_000_000L
        try{
            store.addConfigured(Friend("r","合成角色","成年"),RolePrompts(characterDefinition="成年合成角色",relationTemplate="DIRECT",relationStart="FRIENDS"))
            var core=CompanionRepository(store){now}
            val first=seed(store,core,now,"感谢陪伴"){RelationInput(it,now,now/day,"关于关系",p=.6)}
            now+=day;val before=core.relationship("r").state
            core.changeRelationshipTemplate("r",RelationTemplate.RESERVED);assertEquals(before,core.relationship("r").state)
            val second=seed(store,core,now,"失约了"){RelationInput(it,now,now/day,"关于关系",v=.6,disappointment=.54)}
            val after=core.relationship("r").state
            fun applied()=RelationCaches.get(store.readableDatabase,"r").feeling!!.applied
            val applied=applied()
            repeat(4){core.relationship("r");core.snapshot("r")};assertEquals(applied,applied())
            assertTrue(core.correctRelationshipRecord(second));assertNotEquals(after,core.relationship("r").state)
            assertTrue(core.undoRelationshipCorrection(second));assertEquals(after,core.relationship("r").state)
            assertTrue(core.correctRelationshipRecord(second));assertTrue(core.restoreRelationshipRecord(second));assertEquals(after,core.relationship("r").state)
            assertEquals(4,core.relationshipCorrectionHistory("r").size);assertTrue(core.relationshipCorrectionHistory("r").all{it.at==now})
            store.close();store=Store(context,name);core=CompanionRepository(store){now}
            assertEquals(after,core.relationship("r").state);assertEquals(1,core.relationshipChangeHistory("r").size)
            // 只改正那一笔（小星定的做法，不从头重算）：第一笔的加减量换成改正后的，后来那笔失约记下的心结和失望原样不动。
            assertTrue(core.correctRelationshipRecord(first));val corrected=core.relationship("r").state
            assertTrue(corrected.trust<after.trust);assertEquals(after.tension,corrected.tension,0.0);assertEquals(after.disappointment,corrected.disappointment,0.0)
            assertEquals("已改正",core.relationshipAudit("r").single{it.input.source==first}.status)
        }finally{store.close();context.deleteDatabase(name)}
    }
    @Test fun backupSkipsOnlyUnmatchedRelationsAndRestoresHistory():Unit=runBlocking{
        val name="relationship-v3-backup-${System.nanoTime()}";val store=Store(context,"$name.db");val dest=Store(context,"$name-copy.db");val now=2_000_000_000_000L
        try{
            store.addConfigured(Friend("r","合成角色","成年"),RolePrompts(characterDefinition="成年合成人设",relationTemplate="DIRECT",relationStart="FRIENDS"))
            val core=CompanionRepository(store){now}
            repeat(7){index->seed(store,core,now,"完整原话$index"){RelationInput(it,now,now/day,"关于关系",p=.3)}}
            core.changeRelationshipTemplate("r",RelationTemplate.QUIET)
            val exported=BackupRepository(store,ModelVault(context,name,store)).export("r")
            val root=exported.root.deepCopy();val role=root["roles"].asJsonArray[0].asJsonObject;val sources=role["tables"].asJsonObject["companion_sources"].asJsonArray
            for(index in 0..5){val entry=sources[index].asJsonObject;val proposal=JsonParser.parseString(entry["proposal"].asString).asJsonObject;val relation=proposal["relationInput"].asJsonObject
                when(index){0->relation.addProperty("category","无效");1->relation.addProperty("formatVersion",99);2->relation.getAsJsonObject("source").addProperty("version",99);3->relation.remove("category");4->relation.addProperty("sourceHash","不同");5->relation.add("fulfills",Gson().toJsonTree(SourceRef("r",999999,1)))}
                entry.addProperty("proposal",proposal.toString())
            }
            val target=BackupRepository(dest,ModelVault(context,"$name-copy",dest));val pack=BackupArchive.Package(root,exported.files,"synthetic")
            val preview=target.preview(pack,"ADD");assertEquals(6,preview.relationReport.skipped);assertEquals("有 6 条关系记录对不上，已跳过",preview.relationReport.message)
            val result=target.commitWithReport(preview);assertEquals(6,result.relationReport.skipped);val roleId=result.roles.single()
            assertEquals(14,dest.messages().size);assertEquals(1,CompanionRepository(dest){now}.relationship(roleId).inputs.size)
            assertEquals(1,CompanionRepository(dest).relationshipChangeHistory(roleId).size)
            val good=target.preview(BackupArchive.Package(exported.root,exported.files,"synthetic-good"),"ADD")
            assertEquals(0,good.relationReport.skipped);assertNull(good.relationReport.message)
        }finally{store.close();dest.close();context.deleteDatabase("$name.db");context.deleteDatabase("$name-copy.db")}
    }
    @Test fun parameterSuggestionsNeedConfirmationAndNeverChangeEmotionParameters():Unit=runBlocking{
        val name="relationship-v3-proposal-${System.nanoTime()}.db";val store=Store(context,name);var now=2_000_000_000_000L
        try{
            store.addConfigured(Friend("r","合成角色","成年"),RolePrompts(characterDefinition="成年合成人设",relationTemplate="DIRECT"))
            val core=CompanionRepository(store){now};val original=core.relationship("r").parameters
            val proposal=RelationParameterProposal(null,mapOf("silenceDays" to "3"),"隔三天找你。",now)
            assertEquals(original,core.relationship("r").parameters)
            assertTrue(core.confirmRelationshipParameters("r",proposal,false));assertEquals(original,core.relationship("r").parameters)
            now++;assertTrue(core.confirmRelationshipParameters("r",proposal,true));assertEquals(3.0,core.relationship("r").parameters.silenceDays,0.0)
            assertEquals(original.sensitivity,core.relationship("r").parameters.sensitivity,0.0)
            assertFalse(core.confirmRelationshipParameters("r",proposal.copy(values=mapOf("sensitivity" to "0")),true))
        }finally{store.close();context.deleteDatabase(name)}
    }
    @Test fun persistedAutomaticSilenceCacheRebuildsOnceWithoutChangingEvidenceOrAnotherRole():Unit=runBlocking{
        val name="relationship-silence-cache-${System.nanoTime()}.db";val store=Store(context,name)
        val initialAt=2_000_000_000_000L;var now=initialAt
        try{
            store.addConfigured(Friend("r","合成角色","成年"),RolePrompts(characterDefinition="成年合成人设",relationTemplate="DIRECT",relationStart="FRIENDS"))
            store.addConfigured(Friend("other","另一角色","成年"),RolePrompts(characterDefinition="成年合成人设"))
            val core=CompanionRepository(store){now};val repo=ProcessingRepository(store);val p=RelationTemplate.DIRECT.parameters
            val real=seed(store,core,now,"答应的事没做到"){RelationInput(it,now,now/day,"关于关系",v=.6,disappointment=.3)}
            val sent=store.messages().last{it.friendId=="r"&&!it.mine}.id
            val autoAt=initialAt+day
            val automatic=RelationInput(SourceRef("r",sent,1),autoAt,autoAt/day,"关于关系",ignored=.2,explanationPolicy=1)
            now=initialAt+60*day
            core.relationship("other")
            val rows=repo.read{core.relationshipInputs(it,"r")}
            fun build(inputs:List<RelationInput>)=RelationFeeling.update("r",null,p,p,RelationStart.FRIENDS,inputs,listOf(initialAt),emptyList(),emptyList(),now)
            val old=build(rows+automatic);old.cursor.advance(now);old.cursor.compact()
            repo.tx{db->
                RelationStorage.write(db,"r",RelationStorage.read(db,"r").copy(programInputs=listOf(automatic)))
                RelationFeeling.write(db,"r",old.saved.copy(cursor=RelationSnapshotCodec.encode(old.cursor)))
            }
            suspend fun preference(key:String)=repo.read{db->db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{assertTrue(it.moveToFirst());it.getString(0)}}
            val historyBefore=preference(RelationStorage.key("r"));val otherBefore=preference(RelationFeeling.key("other"))
            val expected=build(rows).cursor.also{it.advance(now);it.compact()}.state()
            val first=core.relationship("r");assertEquals(expected,first.state);assertEquals(real,first.inputs.single().source)
            assertTrue(first.state.disappointment>0) // The real source's feeling remains.
            val saved=repo.read{RelationFeeling.read(it,"r")!!};assertFalse(saved.ledger.containsKey("$sent:1"))
            val after=preference(RelationFeeling.key("r"))
            assertEquals(first.state,core.relationship("r").state);assertEquals(after,preference(RelationFeeling.key("r")))
            assertEquals(historyBefore,preference(RelationStorage.key("r")));assertEquals(otherBefore,preference(RelationFeeling.key("other")))
            assertEquals("答应的事没做到",store.messages().single{it.id==real.turnId}.text)
        }finally{store.close();context.deleteDatabase(name)}
    }
    @Test fun explicitContactDoesNotRecordSilenceFeelingsAndTemplatesCannotSchedule():Unit=runBlocking{
        for(template in listOf(RelationTemplate.DIRECT,RelationTemplate.RESERVED,RelationTemplate.STEADY)){
            val name="relationship-v3-proactive-${System.nanoTime()}.db";val store=Store(context,name);var now=2_000_000_000_000L
            try{
                store.addConfigured(Friend("r","合成角色","成年"),RolePrompts(characterDefinition="成年合成人设",relationTemplate=template.name,relationStart="FRIENDS"))
                val core=CompanionRepository(store){now};core.setPolicy("r",true,"test")
                val source=seed(store,core,now,"普通问候"){RelationInput(it,now,now/day,"普通信息")}
                core.refreshRelationshipContacts();assertTrue(core.queued().isEmpty())
                // Explicit model plan fixture; the relationship template no longer chooses its time.
                val snapshot=core.snapshot("r")
                store.writableDatabase.execSQL("INSERT INTO companion_opportunities(id,role,source_id,revision,epoch,policy_version,not_before,expires_at,reason,state) VALUES('explicit','r',?,?,?,?,?,?,'合成模型安排','queued')",arrayOf<Any>(source.turnId,source.version,snapshot.epoch,snapshot.policyVersion,now+3600000,Long.MAX_VALUE))
                val queued=core.queued().single()
                now=queued.notBefore;val claimed=core.claim(queued.id,"test")!!
                val message=core.conclude(claimed,"test",OpportunityDecision("send","自然联系","最近怎么样？"),core.snapshot("r")){true}!!
                now+=day;core.refreshRelationshipContacts()
                val state=core.relationship("r").state;assertEquals(.5,state.trust,0.0);assertEquals(0.0,state.disappointment,0.0)
                assertEquals(0.0,state.hurt,0.0);assertEquals(0.0,state.low,0.0)
                assertTrue(ProcessingRepository(store).read{RelationStorage.read(it,"r").programInputs.isEmpty()})
                val unseen=core.contactFacts("r");assertTrue(unseen.contains("回复 $message：未读"));assertTrue(unseen.contains("距现在约 1 天"))
                core.markRead("r",message)
                val appeared=core.contactFacts("r");assertTrue(appeared.contains("回复 $message：已读"));assertFalse(appeared.contains("不代表对方读过"))
                for(adaptive in listOf(false,true)){
                    ProcessingRepository(store).saveMemoryRules(MemoryRules(adaptiveContext=adaptive))
                    val snapshot=core.snapshot("r")
                    val reply=if(adaptive)snapshot.references.joinToString("\n"){it.text}else snapshot.replyPrompt
                    assertTrue(reply.contains(appeared));assertFalse(snapshot.proactivePrompt.contains(appeared))
                }
                assertEquals(state,core.relationship("r").state)
                now++;val missingBefore=core.relationship("r").state.missing;seed(store,core,now,"我回来啦"){RelationInput(it,now,now/day,"普通信息")}
                core.refreshRelationshipContacts();assertEquals(missingBefore*.2,core.relationship("r").state.missing,1e-9)
                now+=day;core.refreshRelationshipContacts();assertTrue(ProcessingRepository(store).read{RelationStorage.read(it,"r").programInputs.isEmpty()})
            }finally{store.close();context.deleteDatabase(name)}
        }
    }
}
