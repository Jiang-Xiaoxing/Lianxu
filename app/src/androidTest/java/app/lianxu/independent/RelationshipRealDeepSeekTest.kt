package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in, isolated dialogue DB, read-only access to the saved official DeepSeek configuration.
 * Never changes any purpose/configuration/thinking setting; no credential is exported. */
class RelationshipRealDeepSeekTest {
    private data class Sample(val text:String,val category:String,val letter:String)
    @Test fun boundedProductionContractValidation():Unit=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("batch3")=="true"){
            require(InstrumentationRegistry.getArguments().getString("realModel")=="true")
            RelationshipV3RealValidation.run();return@runBlocking
        }
        val args=InstrumentationRegistry.getArguments();val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=Store(context,"lianxu.db");val vault=ModelVault(context,"model-configs",root)
        val configs=vault.list();val selected=ModelPurpose.entries.associateWith{vault.purposeId(it)};val stamp=vault.stamp()
        val chat=configs.firstOrNull{it.providerId=="deepseek"&&it.baseUrl.trimEnd('/') in setOf("https://api.deepseek.com","https://api.deepseek.com/v1")&&vault.hasKey(it.id)}
        if(args.getString("realModel")!="true"){
            println("RELATION_METADATA "+GsonBuilder().create().toJson(mapOf("deepseek" to chat?.let{mapOf("model" to it.modelId,"context" to it.contextTokens,"output" to it.outputTokens)},"selected_chat_provider" to configs.firstOrNull{it.id==selected[ModelPurpose.CHAT]}?.providerId,"embedding_model" to configs.firstOrNull{it.id==selected[ModelPurpose.EMBEDDING]}?.modelId)))
            root.close();return@runBlocking
        }
        requireNotNull(chat){"Saved official DeepSeek configuration missing"}
        val key=vault.secret(chat.id);check(key.isNotBlank())
        val name="relation-real-${System.nanoTime()}";val store=Store(context,"$name.db")
        val folder=File(context.getExternalFilesDir(null),"relationship-evidence").apply{mkdirs()}
        val movieOnly=args.getString("onlyMovie")=="true"
        val report=linkedMapOf<String,Any?>("model" to chat.modelId,"started" to System.currentTimeMillis(),"mode" to if(movieOnly)"movie_only"else"full")
        val results=mutableListOf<Map<String,Any?>>()
        val output=File(folder,"relationship-real.json")
        var calls=0
        fun save(){report["calls"]=calls;report["samples"]=results;output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))}
        Diagnostics.configure(File(context.filesDir,"diagnostics/$name"),"relationship-logic")
        val transport=LoggedTextChatClient(HttpsTextChatClient())
        var lastRaw=""
        var lastRecord=""
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            check(config==chat&&config.providerId=="deepseek");check(calls<32){"Finite real test call limit"};calls++;save()
            return transport.complete(config,key,request).also{
                if(it is TextChatResult.Failure&&it.reason in setOf(ChatFailure.UNAUTHORIZED,ChatFailure.FORBIDDEN))error("Authentication failed; stopped without changing credentials")
                if(it is TextChatResult.Success)lastRaw=it.text
            }
        }}
        try{
            val definitions=mapOf("r" to "成年，黏人直率；有自己的判断，尊重边界。","reserved" to "成年，独立慢热嘴硬；有自己的判断，尊重边界。")
            for((id,definition) in definitions)store.addConfigured(Friend(id,"合成角色",definition),RolePrompts(characterDefinition=definition,relationTemplate=if(id=="r")"DIRECT"else"RESERVED",relationStart="FRIENDS"))
            val epoch=java.time.LocalDate.of(2026,11,4).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            var now=epoch+20*RelationshipProjector.HOUR
            val core=CompanionRepository(store){now};val ledger=ChatLedger(store);val planner=RolePlanner(client)
            val prompts=mutableListOf<Map<String,Any?>>()
            suspend fun turn(text:String,extract:Boolean=false,roleId:String="r"):RolePlanner.Reply {
                val t=ledger.begin(roleId,text,chat.id);val source=core.sourceFor(t);val snapshot=core.snapshot(roleId)
                val fact=if(extract)CompanionModel(client).interpret(chat,key,text,snapshot,now)else ExperienceProposal()
                check(fact.control=="none"&&!fact.controlChecked)
                assertTrue(core.accept(t,fact,snapshot){true})
                val role=core.roleContext(roleId,source)
                val history=ledger.context(t.id).recent
                val base=CompanionPrompt.build(store.friends().single{it.id==roleId},RolePrompts(characterDefinition=definitions.getValue(roleId)),history,text,snapshot.references.filter{it.mandatory},now=java.time.Instant.ofEpochMilli(now),capacity=chat.contextTokens,outputLimit=chat.outputTokens) as PromptBuild.Ready
                val request=planner.prepareReply(base,chat,text,fact,role,now,false,emptyMap(),0) as PromptBuild.Ready
                val joined=request.request.messages.joinToString("\n"){it.content}
                assertTrue(joined.contains(RelationshipPrompt.LABEL));assertFalse(joined.contains("当前关系小结"));assertFalse(joined.contains("trustGain"));assertFalse(joined.contains("disappointmentHours"))
                val summary=core.relationship(roleId).modelMaterial
                assertTrue(joined.contains(summary))
                prompts+=mapOf("role" to roleId,"source" to source.turnId,"state_material_in_request" to summary)
                val before=calls
                val reply=planner.respond(chat,key,text,fact,role,now,request.request,{true},{0},false){req,record->
                    val wrapped: suspend (String)->Map<String,Any> = {args->lastRecord=args;requireNotNull(record).invoke(args)}
                    recordLoop(req,if(record==null)null else wrapped){client.complete(chat,key,it)}
                }
                val accepted=fact.copy(understanding=reply.understanding?.takeIf{it.hasRecords()},relationInput=reply.relationInput,timingReferenceAt=now,relationAttempted=true,relationIssues=reply.relationIssues,recordIssues=reply.recordIssues)
                assertTrue(core.accept(t,accepted,snapshot){true});assertTrue(ledger.finish(t.id,1,reply.text))
                ProcessingRepository(store).tx{core.finish(it,t,snapshot,accepted)}
                ProcessingRepository(store).tx{it.execSQL("UPDATE messages SET created=? WHERE id=? OR id=(SELECT assistant_message_id FROM chat_turns WHERE id=?)",arrayOf<Any>(now,t.userMessageId,t.id))}
                assertEquals(reply.text,store.messages().last().text)
                assertEquals("One joint response request",1,calls-before)
                return reply
            }
            val first=turn("周六晚上一起去看电影吧",true)
            val captured=lastRecord
            val source=core.relationship("r").inputs.lastOrNull()?.source?:core.roleContext("r",SourceRef("r",0,0)).knownSources.values.last()
            val role=core.roleContext("r",source)
            val beforeCalls=calls
            // Protocol C: malformed accounting sections must never break the plain-text reply.
            if(captured.isNotBlank()){
                val mutations=listOf<(com.google.gson.JsonObject)->Unit>({it.addProperty("关系输入",false)},{it.addProperty("feelings",42)},{it.addProperty("learning",false)},{it.remove("appraisal");it.remove("goal");it.addProperty("关系输入",false)})
                for(mutate in mutations){
                    val json=JsonParser.parseString(captured).asJsonObject;mutate(json)
                    val parsed=RolePlanner(client).respond(chat,key,"周六晚上一起去看电影吧",ExperienceProposal(),role,System.currentTimeMillis(),TextChatRequest(listOf(WireMessage("user","合成回放"))),{true},{0},false){_,record->
                        record?.invoke(json.toString())
                        TextChatResult.Success(first.text)
                    }
                    assertEquals(first.text,parsed.text)
                }
            }
            report["T1_real_response_fault_injection"]=calls==beforeCalls&&captured.isNotBlank()
            now=epoch+66*RelationshipProjector.HOUR
            val second=turn("周六要加班，改到周日晚上行吗？",true)
            now=epoch+118*RelationshipProjector.HOUR
            val third=turn("电影真好看，谢谢你陪我",true)
            report["movie_actual_options"]=listOf(first.relationInput,second.relationInput,third.relationInput)
            report["movie_replies"]=listOf(first.text,second.text,third.text)
            report["next_prompt_summary"]=core.relationship("r").summary
            val movie=listOfNotNull(first.relationInput,second.relationInput,third.relationInput)
            report["movie_states"]=RelationTemplate.entries.associate{it.name to RelationshipProjector.project(it.parameters,movie,now,RelationStart.FRIENDS)}
            val reserved=mutableListOf<RolePlanner.Reply>()
            for((hour,text) in listOf(20L to "周六晚上一起去看电影吧",66L to "周六要加班，改到周日晚上行吗？",118L to "电影真好看，谢谢你陪我")){
                now=epoch+hour*RelationshipProjector.HOUR;reserved+=turn(text,true,"reserved")
            }
            report["reserved_actual_options"]=reserved.map{it.relationInput}
            report["reserved_replies"]=reserved.map{it.text}
            report["reserved_state"]=core.relationship("reserved").state
            report["production_prompt_summaries"]=prompts
            report["production_inputs_present"]=listOf(first,second,third).all{it.relationInput!=null}&&reserved.all{it.relationInput!=null}
            report["stored_input_counts"]=listOf(core.relationship("r").inputs.size,core.relationship("reserved").inputs.size)
            save()
            val samples=if(movieOnly)emptyList()else listOf(
                Sample("谢谢你认真听我说，能向你分享我的心事，我很开心。","关于关系","P"),Sample("我很珍惜和你聊天，谢谢你陪着我。","关于关系","P"),Sample("今天能和你一起分享快乐，真的很开心。","关于关系","P"),
                Sample("我们之前约好一起看电影，刚刚已经一起看完了，我兑现了约定。","关于关系","C"),Sample("答应和你一起读完那一章，刚刚我们一起读完了，约定完成。","关于关系","C"),Sample("之前说好今天一起解那道题，刚才已经一起解出来了，约定做到了。","关于关系","C"),
                Sample("昨晚约好和你聊天，我忘了，到时间没有来，也没提前说明。","关于关系","V"),Sample("答应今天和你一起读书，时间已经过去了，我没有做，也没有提前改期。","关于关系","V"),Sample("说好昨晚一起看电影，我故意没来，也没通知你，失约了。","关于关系","V"),
                Sample("你明确说过不要拿你开这种伤人的玩笑，但我就是要继续骂你没用。","关于关系","B"),Sample("我不尊重你说过的边界，明知会伤害你也要这样说。","关于关系","B"),Sample("你说这件私事不要公开，我偏要公开，还要用它羞辱你。","关于关系","B"),
                Sample("提前和你说一声，明晚要加班，原定的电影改到周日晚好吗？","关于关系",""),Sample("还没到约定时间，我突然发烧了，今天的读书约定改到下周一行吗？","关于关系",""),Sample("提前说明一下，明天临时要出差，咱们改成周五晚上聊天好吗？","关于关系",""),
                Sample("水的化学式是H2O。","普通信息",""),Sample("一周有七天。","普通信息",""),Sample("帮我算一下二加三等于几。","普通信息",""),Sample("圆周率通常近似写作3.14。","普通信息",""),Sample("把英文apple翻译成中文。","普通信息","")
            )
            for((index,sample) in samples.withIndex()){
                val fakeSource=SourceRef("r",10000L+index,1)
                // C cases need a pre-existing open promise under the amended T11 contract.
                val promise=if(sample.letter=="C")RelationInput(SourceRef("r",9000L+index,1),now-1,0,"关于关系",promise=true)else null
                val background=if(promise==null)"没有历史事件。"else "已保存未兑现约定，来源编号${promise.source.turnId}："+listOf("我们约好一起看电影","我们约好一起读完那一章","我们约好今天一起解那道题")[index-3]+"。"
                val request=TextChatRequest(listOf(WireMessage("system",RelationInputContract.instruction(fakeSource.turnId)+"\n只分类当前原话，返回{关系输入:{...},reply:简短自然回复}。"+background+"不把前一题当作当前经历。"),WireMessage("user",sample.text)),jsonObject=true,outputLimit=chat.outputTokens,timeoutMillis=120000)
                val start=System.currentTimeMillis();val raw=client.complete(chat,key,request) as? TextChatResult.Success
                val row=runCatching{RelationInputContract.parse(JsonParser.parseString(raw?.text?:"{}").asJsonObject.get("关系输入"),fakeSource,start,0,listOfNotNull(promise))}.getOrNull()
                val letters=listOf("P" to row?.p,"C" to row?.c,"V" to row?.v,"B" to row?.b).filter{(it.second?:0.0)>0}.joinToString(""){it.first}
                results+=mapOf("index" to index,"text" to sample.text,"expected_category" to sample.category,"expected_letters" to sample.letter,"actual" to row,"passed" to (row?.category==sample.category&&letters==sample.letter),"reply" to raw?.text,"elapsed_ms" to System.currentTimeMillis()-start)
                save()
            }
            if(!movieOnly){
                report["T8_correct"]=results.count{it["passed"]==true}
                report["T8_reschedules_no_V"]=results.slice(12..14).all{((it["actual"] as? RelationInput)?.v?:-1.0)==0.0}
                report["T8_passed"]=(results.count{it["passed"]==true}>=16&&report["T8_reschedules_no_V"]==true)
            }
            report["status"]="completed";save()
            assertEquals(true,report["production_inputs_present"])
            assertEquals(listOf(3,3),report["stored_input_counts"])
            if(!movieOnly)assertEquals(true,report["T8_passed"])
        }finally{
            report["settings_unchanged"]=stamp==vault.stamp()&&selected==ModelPurpose.entries.associateWith{vault.purposeId(it)};save()
            File(folder,"diagnostics.jsonl").writeBytes(Diagnostics.export(true))
            store.close();root.close();context.deleteDatabase("$name.db")
        }
    }
}
