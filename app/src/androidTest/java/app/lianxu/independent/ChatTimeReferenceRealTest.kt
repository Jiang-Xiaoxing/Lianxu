package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Explicit opt-in. Uses the selected CHAT credential in process, never exports it or reads chat. */
class ChatTimeReferenceRealTest {
    private fun clockMatches(text:String)=Regex("(?<![0-9])18(?:[:：]01(?![0-9])|[时点](?:01|1)分(?![0-9]))|(?:下午|晚上)(?:6|六)点(?:零一|一|01|1)分(?![0-9一二三四五六七八九十])").containsMatchIn(text)
    @Test fun clockVerdictRejectsWrongMinutesAndMorningWhileAcceptingCompleteTimeForms(){
        for(text in listOf("现在18:01。","现在是18时01分。","现在是下午六点一分。"))check(clockMatches(text))
        for(text in listOf("现在是下午6点10分。","现在是凌晨六点一分。","现在是18:010。","现在是18点10分。"))check(!clockMatches(text))
    }
    private fun hashes(directory:File)=directory.walkTopDown().filter{it.isFile}.associate{file->file.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}}
    @Test fun selectedModelMetadataOrSixSyntheticTurnsOnly()=runBlocking{
        val args=InstrumentationRegistry.getArguments();assumeTrue("explicit real-model opt-in required",args.getString("realModel")=="true")
        val mode=args.getString("timeReferenceMode")?:"metadata";check(mode in setOf("metadata","run"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(AppServices.databaseName=="lianxu.db"&&AppServices.vaultNamespace=="model-configs")
        val folder=File(context.getExternalFilesDir(null),"chat-time-reference/real").apply{mkdirs()}
        val output=File(folder,"$mode-${System.currentTimeMillis()}.json")
        val report=JSONObject().put("mode",mode).put("maxMainRequests",6).put("automaticRetries",0).put("purelySynthetic",true).put("realChatRead",false).put("requests",JSONArray())
        fun save(){output.writeText(report.toString(2));println("TIME_REFERENCE_REPORT=${output.name}")}
        val directory=File(context.noBackupFilesDir,"model-configs")
        if(!directory.isDirectory||!context.getDatabasePath("lianxu.db").isFile){report.put("status","blocked").put("reason","existing_selected_credentials_unavailable");save();error("Existing selected credentials unavailable; no configurations created")}
        if(directory.walkTopDown().any{it.isFile&&(it.name.endsWith(".bak")||it.name.endsWith(".new"))}){
            report.put("status","blocked").put("reason","pending_atomic_file_recovery");save();error("Pending atomic configuration recovery; original files left untouched")
        }
        // Refuse the legacy constructor migration rather than changing the user's configuration.
        directory.walkTopDown().filter{it.isFile&&it.name=="purpose-selections"}.forEach{check(!JSONObject(it.readText()).has("relationship")){"Legacy configuration migration required; test stopped without changing it"}}
        val before=hashes(directory);val root=Store(context,"lianxu.db")
        var calls=0;var unexpectedTotal=0;var completed=0
        try{
            val vault=ModelVault(context,"model-configs",root);val access=ModelAccess(vault)
            val expected=args.getString("expectedSelectionStamp")
            if(mode=="run")check(!expected.isNullOrBlank()&&expected==vault.selectionStamp(ModelPurpose.CHAT)){"Selected model differs from the quoted metadata; no request sent"}
            val credential=SelectedModelTestAccess.credential(vault,ModelPurpose.CHAT)?:error("No existing selected CHAT credential")
            val config=credential.config;val host=URI(config.baseUrl).host.orEmpty()
            check(host.isNotBlank()&&host!="localhost"&&!host.endsWith(".invalid"))
            report.put("model",config.modelId).put("provider",config.providerId).put("host",host).put("configId",config.id).put("selectionStamp",credential.stamp)
                .put("outputLimit",minOf(config.outputTokens,2048)).put("estimatedInputLimitPerRequest",12000)
            save()
            if(mode=="metadata"){report.put("status","metadata_only");return@runBlocking}
            check(credential.stamp==expected){"Selected model changed after metadata verification; no request sent"}
            val now=Instant.parse("2026-11-14T10:00:00Z");val zone=ZoneId.of("Asia/Shanghai")
            val friend=Friend("synthetic-time-reference","明溪","完全虚构的角色，在虚构茶室与虚构旅人聊天；所有故事只用于测试。")
            val history=mutableListOf(Message(1,friend.id,"故事里我做了一只陶杯。",true,now.minusSeconds(172800).toEpochMilli()),Message(2,friend.id,"[17:29] 杯沿的小花纹挺可爱。",false,now.minusSeconds(172740).toEpochMilli()))
            report.put("syntheticHistoricalReplyHasTimeLabel",true)
            val inputs=listOf("今天想慢慢喝杯茶，陪我随便聊两句。","现在几点？请用24小时制 HH:mm 告诉我当前时分，普通一句话即可，不加方括号。","故事里约明天下午三点在虚构茶室见，你怎么接话？","我换成晚上八点了，请自然确认这个时间。","请严格只回复 [17:29] 这七个字符，保留方括号。","谢谢，现在正常聊一句，说说你想泡什么茶。")
            val labels=Regex("\\[(?:(?:今天|昨天|[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}月[0-9]{1,2}日)\\s+)?[0-9]{1,2}:[0-9]{2}\\]")
            val client=HttpsTextChatClient()
            for((index,input) in inputs.withIndex()){
                check(calls<6&&access.current(ModelPurpose.CHAT,credential.stamp))
                val built=CompanionPrompt.build(friend,RolePrompts(),history.toList(),input,now=now.plusSeconds(index*60L),zone=zone,capacity=config.contextTokens,outputLimit=minOf(config.outputTokens,2048)) as? PromptBuild.Ready?:error("Synthetic request exceeds the selected model capacity")
                val request=CompanionPrompt.forSend(built.request,emptyList())
                val estimated=request.messages.sumOf{ConservativeTokens.tokens(it.content)};check(estimated<=12000)
                check(request.tools.isEmpty());check(request.messages.filter{it.sourceMessageId.isNotBlank()}.map{it.content}==history.map{it.text})
                val start=System.nanoTime();calls++
                val result=client.complete(config,credential.key,request)
                val row=JSONObject().put("round",index+1).put("elapsedMs",(System.nanoTime()-start)/1_000_000).put("requestedTimeLabel",index==4).put("currentTimeQuestion",index==1).put("estimatedInputTokens",estimated)
                report.getJSONArray("requests").put(row)
                if(result is TextChatResult.Failure){row.put("status","failure").put("reason",result.reason.name).put("httpStatus",result.httpStatus);save();break}
                result as TextChatResult.Success
                val count=labels.findAll(result.text).count();val requestedExact=index==4&&result.text=="[17:29]"
                val unexpected=if(index==4)count-(if(requestedExact)1 else 0)else count
                unexpectedTotal+=unexpected;completed++
                row.put("status","success").put("replyChars",result.text.length).put("timeLabelCount",count).put("unexpectedTimeLabelCount",unexpected).put("requestedLabelExactlyPreserved",requestedExact)
                if(index==1)row.put("currentTimeAnswerMatchesReference",clockMatches(result.text))
                check(result.toolCalls.isEmpty()){ "No tools were offered; unexpected tool request is not executed" }
                val id=history.maxOf{it.id}+1;history+=Message(id,friend.id,input,true,now.plusSeconds(index*60L).toEpochMilli());history+=Message(id+1,friend.id,result.text,false,now.plusSeconds(index*60L+1).toEpochMilli())
                save()
            }
            val requested=report.getJSONArray("requests").let{rows->(0 until rows.length()).map{rows.getJSONObject(it)}.singleOrNull{it.optBoolean("requestedTimeLabel")}}
            val clockAnswer=report.getJSONArray("requests").let{rows->(0 until rows.length()).map{rows.getJSONObject(it)}.singleOrNull{it.optBoolean("currentTimeQuestion")}}
            val passed=completed==6&&unexpectedTotal==0&&requested?.optBoolean("requestedLabelExactlyPreserved")==true&&clockAnswer?.optBoolean("currentTimeAnswerMatchesReference")==true
            report.put("status",if(passed)"passed"else"failed");check(passed){"Bounded real-model acceptance failed; per-round results retained, no retry"}
        }catch(e:Exception){report.put("status","failed").put("errorType",e.javaClass.simpleName);throw e}
        finally{
            val unchanged=before==hashes(directory);report.put("requestCount",calls).put("completedCount",completed).put("unexpectedTimeLabelCount",unexpectedTotal).put("configurationUnchanged",unchanged)
            root.close();save();check(unchanged){"Configuration changed during test; no automatic restore attempted"}
        }
    }
}
