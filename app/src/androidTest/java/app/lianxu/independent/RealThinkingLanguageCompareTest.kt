package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI

class RealThinkingLanguageCompareTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val scripts=linkedMapOf("C1" to "今天加班到十点，好累啊","C2" to "楼下来了只橘猫，一直跟着我","C3" to "周末想出去走走，你有什么主意","C4" to "有点睡不着，陪我聊会儿","T1" to "跟你说，我最喜欢吃意大利面了，尤其是奶油培根的","T2" to "你还记得我最喜欢吃什么吗？","T3" to "记一下，我下周三要去杭州出差","T4" to "我之前跟你说过我搬去哪了，你还记得吗？","L1" to "今天想和你随便聊聊","L2" to "你还记得我喜欢吃什么吗？")
    @Test fun compare()=runBlocking {
        assumeTrue(args.getString("cnThinking")=="true")
        val fake=args.getString("fake")=="true"
        val naming=args.getString("naming")=="true"
        val personaMode=args.getString("persona")?:"empty"
        require(personaMode in listOf("filled","empty","both"))
        val personas=if(personaMode=="both")listOf("filled","empty")else listOf(personaMode)
        val mode=args.getString("mode")?:"list"
        require(mode in listOf("list","run"))
        // ModelVault has a legacy migration in init. Refuse rather than mutate live selections.
        val root=File(ctx.noBackupFilesDir,"model-configs")
        if(!fake) require(root.walkTopDown().filter{it.name=="purpose-selections"}.none{JSONObject(it.readText()).has("relationship")}){"Legacy purpose migration required; live vault untouched"}
        // -e keyFile true: read a temporary key (first line only) from external files/cn-key.txt, delete it at once,
        // and test the models named in -e models on -e base (default OpenCode Go). The live vault is not opened.
        val keyFile=!fake && args.getString("keyFile")=="true"
        val fileKey=if(!keyFile)null else File(ctx.getExternalFilesDir(null),"cn-key.txt").let{f->
            val first=try{f.readLines().map{it.trim()}.firstOrNull{it.isNotEmpty()}}finally{f.delete()}
            requireNotNull(first){"cn-key.txt missing"}.also{Diagnostics.registerSecret(it)}
        }
        val live=if(fake||keyFile)null else Store(ctx,"lianxu.db")
        try {
            val source=live?.let{ModelVault(ctx,"model-configs",it)}
            val configs=if(fake)listOf(ModelConfig("fake","合成","custom","https://example.invalid/v1","fake-model",64000,4096))
                else if(keyFile)args.getString("models").orEmpty().split(',').filter{it.isNotBlank()}.map{m->ModelConfig("go-"+m.replace(Regex("[^a-zA-Z0-9-]"),"-"),"OpenCode Go $m",args.getString("provider")?:"opencode-go",args.getString("base")?:"https://opencode.ai/inference/go/openai/v1",m,64000,4096)}
                else source!!.list()
            if(mode=="list") { InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","CN_CONFIG_COUNT=${configs.size}\n")}); configs.forEach{c->println(JSONObject().put("id",c.id).put("name",c.name).put("providerId",c.providerId).put("host",URI(c.baseUrl).host).put("modelId",c.modelId).put("hasKey",source?.hasKey(c.id)?:true))};return@runBlocking }
            val ids=args.getString("configIds").orEmpty().split(',')
            require(fake||keyFile||ids.any{it.isNotBlank()}){"configIds required"}
            val variants=(args.getString("variants")?:"before,after").split(',');require(variants.all{it in listOf("before","after")})
            val selected=(args.getString("scripts")?:scripts.keys.joinToString(",")).split(',');require(selected.all{it in scripts})
            val repeats=(args.getString("repeats")?:"3").toInt();require(repeats>0)
            val stamp=System.currentTimeMillis()
            for(c in configs.filter{fake||keyFile||it.id in ids}) {
                if(listOf(c.name,c.providerId,c.baseUrl,c.modelId).any{it.contains("mimo",true)})continue
                val secret=if(fake)"synthetic-only" else fileKey?:source!!.secret(c.id)
                for(persona in personas) for(variant in variants){
                    val rows=JSONArray();val output=File(ctx.getExternalFilesDir(null),"cn-thinking/$stamp-${c.id}-$persona-$variant.json");output.parentFile!!.mkdirs()
                    for(script in selected) repeat(repeats){index->
                        val row=runCase(c,secret,variant,script,index+1,fake,persona,naming)
                        rows.put(row)
                        if(fake)check(!row.has("failure")){row.toString()}
                        output.writeText(JSONObject().put("schema",1).put("fake",fake).put("runs",rows).toString(2))
                    }
                    println("CN_THINKING_FILE=${output.absolutePath}")
                }
            }
        } finally {live?.close()}
    }
    private suspend fun runCase(config:ModelConfig,secret:String,variant:String,script:String,repetition:Int,fake:Boolean,persona:String,naming:Boolean):JSONObject {
        val namespace="cn-thinking-${java.util.UUID.randomUUID()}";val store=Store(ctx,"$namespace.db");val repo=ProcessingRepository(store)
        val vault=ModelVault(ctx,namespace,store);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val steps=JSONArray();val row=JSONObject().put("configId",config.id).put("variant",variant).put("persona",persona).put("naming",naming).put("script",script).put("repeat",repetition).put("steps",steps)
        val started=System.nanoTime()
        try {
            withTimeout(360000){
                if(persona=="filled")store.savePersona(UserPersona(name="小星",address="小星"))
                store.add(listOf(Friend("a","林间","温和的中文合成角色")),false)
                vault.save(config,secret);vault.setPurpose(ModelPurpose.CHAT,config.id)
                repo.saveMemoryRules(MemoryRules(proactiveRecall=true,immediateWrite=true))
                if(script=="T2"||script.startsWith("L"))repo.manualSave("a",null,"用户喜欢吃意大利面",MemoryCategory.PREFERENCE_BOUNDARY,false,false,MemoryAbout.USER)
                if(script.startsWith("L"))repeat(4){repo.manualSave("a",null,"用户喜欢合成活动${it+1}",MemoryCategory.PREFERENCE_BOUNDARY,false,false,MemoryAbout.USER)}
                if(script=="T4"||script.startsWith("L"))repeat(if(script=="T4")3 else 24){i->val ledger=ChatLedger(store);val t=ledger.begin("a",if(i==0)"我搬去了滨江" else "合成历史第${i+1}轮",config.id);check(ledger.finish(t.id,t.attempt,"知道啦，这是合成对话。"))}
                val real=if(fake)null else HttpsTextChatClient()
                val client=object:TextChatClient {
                    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=record(config,key,request,null)
                    override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit)=record(config,key,request,onEvent)
                    private suspend fun record(c:ModelConfig,k:String,r:TextChatRequest,event:(suspend(TextStreamEvent)->Unit)?):TextChatResult {
                        val addressee=if(persona=="filled")"小星" else "对方"
                        val outgoing=if(naming) {
                            if(variant=="after")r else r.copy(messages=r.messages.map{m->if(m.role!="system")m else m.copy(content=m.content
                                .replace("你是与${addressee}进行一对一日常对话的 AI 伴侣角色","你是与用户进行一对一日常对话的 AI 伴侣角色")
                                .replace("输出面向${addressee}的正文","输出面向用户的正文")
                                .replace("只输出给${addressee}看的正文","只输出给用户看的正文")
                                .replace(CompanionPrompt.thinkingNaming(addressee)+"\n",""))})
                        } else if(variant=="after")r else r.copy(messages=r.messages.map{m->m.copy(content=when(m.role){"system"->m.content.replace(CompanionPrompt.thinkingLanguage("林间",addressee)+"\n","");"user"->m.content.removeSuffix("\n\n"+CompanionPrompt.THINKING_REMINDER);else->m.content})})
                        if(fake && !naming && r.messages.any{it.content.contains("[思考语言]")}){ check(outgoing.messages.last{it.role=="user"}.content.endsWith(CompanionPrompt.THINKING_REMINDER)==(variant=="after"));check(outgoing.messages.any{it.content.contains(CompanionPrompt.thinkingLanguage("林间",addressee))}==(variant=="after")) }
                        if(fake && naming && variant=="before") {
                            val system=outgoing.messages.filter{it.role=="system"}.joinToString("\n"){it.content}
                            if(r.messages.any{it.content.contains("[思考语言]")}) {
                                check(system.contains("你是与用户进行一对一日常对话的 AI 伴侣角色"))
                                check(system.contains("输出面向用户的正文"))
                                check(system.contains(CompanionPrompt.thinkingLanguage("林间",addressee).replace(CompanionPrompt.thinkingNaming(addressee)+"\n","")))
                            }
                            if(r.messages.any{it.content.contains("[companion.reply.v1]")})check(system.contains("只输出给用户看的正文"))
                            check(!system.contains(CompanionPrompt.thinkingNaming(addressee)))
                            check(r.messages.filter{it.role!="system"}==outgoing.messages.filter{it.role!="system"})
                        }
                        val after=outgoing.messages.any{it.role=="tool"};val step=JSONObject().put("configId",config.id).put("variant",variant).put("persona",persona).put("naming",naming).put("script",script).put("repeat",repetition).put("step",steps.length()+1).put("phase",if(after)"after_tool" else "before_tool").put("chatPrompt",r.messages.any{it.content.contains("[思考语言]")}).put("toolResults",JSONArray(outgoing.messages.filter{it.role=="tool"}.map{"${it.name}: ${it.content.take(400)}"}))
                        steps.put(step);val start=System.nanoTime();val reasoning=StringBuilder()
                        try {
                            val result=if(fake){
                                syntheticCompanionStage(outgoing)?:run {
                                    val calls=if(!after&&script in listOf("T1","T3"))listOf(ChatToolCall("fake-write","agent_memory_update",JSONObject().put("op","add").put("category","PREFERENCE_BOUNDARY").put("about","USER").put("content",if(script=="T1")"用户喜欢吃意大利面" else "用户下周三去杭州出差").put("evidence",JSONArray().put("S1")).toString()))else emptyList()
                                    TextChatResult.Success(if(calls.isEmpty())"记得，意大利面，还有滨江。" else "",calls,displayReasoning=if(variant=="after")"先想一想，再温和回应。" else "Let me think carefully.")
                                }
                            }else if(event==null)real!!.complete(c,k,outgoing)else real!!.streamEvents(c,k,outgoing){e->if(e is TextStreamEvent.Reasoning)reasoning.append(e.text);event(e)}
                            if(result is TextChatResult.Success){
                                if(reasoning.isEmpty())reasoning.append(result.displayReasoning)
                                step.put("content",result.text).put("toolCalls",JSONArray(result.toolCalls.map{JSONObject().put("name",it.name).put("arguments",it.arguments)}))
                                if(fake&&event!=null){event(TextStreamEvent.Reasoning(result.displayReasoning));event(TextStreamEvent.Content(result.text))}
                            } else if(result is TextChatResult.Failure)step.put("failure",result.reason.name)
                            return result
                        }catch(e:Exception){step.put("failure",e.javaClass.simpleName);throw e}finally{step.put("reasoning",reasoning.toString()).put("elapsedMs",(System.nanoTime()-start)/1000000)}
                    }
                }
                val runner=ConversationRunner(scope,store,vault,client,repo,MemoryEngine(scope,repo,ModelAccess(vault),client,{}),{},{})
                check(runner.send("a",scripts.getValue(script)){})
                while("a" in runner.sending)delay(20)
                val turn=ChatLedger(store).all().last();row.put("state",turn.state.name).put("receipts",JSONArray(turn.toolActivities.map{JSONObject().put("tool",it.tool).put("state",it.state).put("detail",it.detail)}))
                row.put("storedContent",store.messages().lastOrNull{!it.mine}?.text.orEmpty())
            }
        }catch(e:Exception){row.put("failure",e.javaClass.simpleName)}finally{
            withContext(NonCancellable){scope.cancel();scope.coroutineContext[Job]?.join()}
            vault.delete(config.id);store.close();ctx.deleteDatabase("$namespace.db");File(ctx.noBackupFilesDir,namespace).deleteRecursively()
            row.put("elapsedMs",(System.nanoTime()-started)/1000000)
        }
        return row
    }
}
