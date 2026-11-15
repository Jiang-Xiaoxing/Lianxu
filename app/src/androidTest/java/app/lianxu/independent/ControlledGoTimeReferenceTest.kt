package app.lianxu.independent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.spec.MGF1ParameterSpec
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.net.ssl.HttpsURLConnection

/** Explicitly authorized, temporary credential handoff. No production API or endpoint changes. */
class ControlledGoTimeReferenceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val runId get()=checkNotNull(InstrumentationRegistry.getArguments().getString("runId")).also{check(it.matches(Regex("[0-9a-f]{32}")))}
    private val alias get()="lianxu.test.go-time-$runId"
    internal val namespace get()="go-time-$runId"
    internal val database get()="$namespace.db"
    private val configId="synthetic-go-model"
    private val endpoint="https://opencode.ai/inference/go/openai/v1/chat/completions"
    internal val folder get()=File(context.getExternalFilesDir(null),"controlled-go-time-20261114/$runId").apply{mkdirs()}
    private val ownership get()=File(context.noBackupFilesDir,"go-owner-$runId")
    private fun storeKeys()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun vaultDirectory()=File(context.noBackupFilesDir,namespace)
    private fun clean():Boolean {
        val dir=vaultDirectory()
        if(!ownership.exists())return !dir.exists()&&!context.getDatabasePath(database).exists()&&!File(folder,"credential.bin").exists()&&!File(folder,"public.der").exists()&&storeKeys().let{!it.containsAlias(alias)&&!it.containsAlias("lianxu.$namespace.$configId")}
        if(!ownership.isFile)return false
        var ok=true
        fun attempt(action:()->Unit){if(runCatching(action).isFailure)ok=false}
        var keys:KeyStore?=null
        attempt{keys=storeKeys()}
        // Each cleanup is independent: a file/database failure must not skip key deletion.
        attempt{val k=checkNotNull(keys);if(k.containsAlias(alias))k.deleteEntry(alias)}
        attempt{val k=checkNotNull(keys);if(k.containsAlias("lianxu.$namespace.$configId"))k.deleteEntry("lianxu.$namespace.$configId")}
        attempt{if(dir.exists()) {
            check(dir.canonicalFile==File(context.noBackupFilesDir,namespace).canonicalFile)
            val allowed=setOf("$configId.enc","$configId.enc.bak","$configId.enc.new","purpose-selections","purpose-selections.bak","purpose-selections.new")
            check(dir.listFiles().orEmpty().all{it.isFile&&it.name in allowed})
            dir.listFiles().orEmpty().forEach{check(it.delete())}
            check(dir.delete())
        }}
        attempt{ModelVaultCache.invalidate(namespace)}
        attempt{if(context.getDatabasePath(database).exists())check(context.deleteDatabase(database))}
        for(name in listOf("public.der","credential.bin"))attempt{File(folder,name).takeIf{it.exists()}?.let{check(it.delete())}}
        val absent=runCatching{val k=checkNotNull(keys);!k.containsAlias(alias)&&!k.containsAlias("lianxu.$namespace.$configId")&&!dir.exists()&&!context.getDatabasePath(database).exists()&&!File(folder,"credential.bin").exists()}.getOrDefault(false)
        if(ok&&absent)attempt{check(ownership.delete())}
        return ok&&absent&&!ownership.exists()
    }
    @Test fun controlledSixRequests()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("explicit opt-in",args.getString("controlledGo")=="true")
        val phase=args.getString("phase")?:"prepare"
        check(phase in setOf("prepare","import","run","cleanup"))
        val report=JSONObject().put("phase",phase).put("runId",runId).put("purelySynthetic",true).put("realChatRead",false)
            .put("maxMainRequests",6).put("automaticRetries",0).put("requests",JSONArray())
        fun save(){File(folder,"$phase.json").writeText(report.toString(2))}
        if(phase=="cleanup") {report.put("cleaned",clean());save();return@runBlocking}
        if(phase=="prepare") {
            check(!ownership.exists()&&!vaultDirectory().exists()&&!context.getDatabasePath(database).exists()&&storeKeys().let{!it.containsAlias(alias)&&!it.containsAlias("lianxu.$namespace.$configId")}){"Test namespace already exists; no overwrite"}
            check(!File(folder,"run.json").exists()){ "This one-shot run already has a result; no repeat" }
            check(!File(folder,"public.der").exists()&&!File(folder,"credential.bin").exists()){"Existing handoff artifacts are not owned"}
            check(ownership.createNewFile())
            try {
                val pair=KeyPairGenerator.getInstance("RSA","AndroidKeyStore").apply{
                    initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_DECRYPT).setKeySize(3072)
                        .setDigests(KeyProperties.DIGEST_SHA256).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
                }.generateKeyPair()
                File(folder,"public.der").writeBytes(pair.public.encoded)
                report.put("publicSha256",digest(pair.public.encoded)).put("model","deepseek-v4.1-flash").put("status","prepared");save()
            }catch(e:Exception){if(args.getString("controlledNormalSample")!="true")clean();error("Preparation failed: ${e.javaClass.simpleName}")}
            return@runBlocking
        }
        if(phase=="import") {
            try {
                check(ownership.isFile&&!vaultDirectory().exists()&&!context.getDatabasePath(database).exists())
                val keys=storeKeys();val public=keys.getCertificate(alias).publicKey.encoded
                check(digest(public)==args.getString("publicSha256"))
                val ciphertext=File(folder,"credential.bin").readBytes();check(ciphertext.size==384)
                val cipher=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
                cipher.init(Cipher.DECRYPT_MODE,keys.getKey(alias,null),OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT))
                val plain=try{cipher.doFinal(ciphertext)}finally{ciphertext.fill(0)}
                val key=try{String(plain,Charsets.UTF_8)}finally{plain.fill(0)}
                check(key.length in 16..256&&key.none{it.isWhitespace()||it.code<32})
                Diagnostics.registerSecret(key);keys.deleteEntry(alias);check(File(folder,"credential.bin").delete())
                val db=Store(context,database)
                try {
                    val config=ModelConfig(configId,"专用UI实测","opencode-go",endpoint.removeSuffix("/chat/completions"),"deepseek-v4.1-flash",262144,2048)
                    ModelVault(context,namespace,db).apply{save(config,key);setPurpose(ModelPurpose.CHAT,configId)}
                }finally{db.close()}
                report.put("status","imported");save()
            }catch(e:Exception){
                report.put("status","failed").put("errorType",e.javaClass.simpleName)
                if(args.getString("controlledNormalSample")=="true")report.put("cleanupDeferredUntilHostForceStop",true)
                else report.put("cleaned",runCatching{clean()}.getOrDefault(false))
                save();throw AssertionError("Temporary import failed: ${e.javaClass.simpleName}")
            }
            return@runBlocking
        }
        check(!File(folder,"run.json").exists()){ "One-shot request budget cannot be restarted" }
        var completed=0;var unexpected=0;var calls=0;var acceptance=false;var pendingManual=false
        val opens=AtomicInteger()
        var store:Store?=null
        try {
            check(!vaultDirectory().exists()&&!context.getDatabasePath(database).exists())
            val keys=storeKeys();val public=keys.getCertificate(alias).publicKey.encoded
            check(ownership.isFile)
            check(digest(public)==args.getString("publicSha256")){"Temporary public key changed"}
            val ciphertext=File(folder,"credential.bin").readBytes();check(ciphertext.size==384)
            val cipher=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            cipher.init(Cipher.DECRYPT_MODE,keys.getKey(alias,null),OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT))
            val plain=try{cipher.doFinal(ciphertext)}finally{ciphertext.fill(0)}
            val key=try{String(plain,Charsets.UTF_8)}finally{plain.fill(0)}
            check(key.length in 16..256&&key.none{it.isWhitespace()||it.code<32}){"Invalid temporary credential"}
            Diagnostics.registerSecret(key)
            keys.deleteEntry(alias);check(File(folder,"credential.bin").delete())
            val config=ModelConfig(configId,"专用合成测试","opencode-go",endpoint.removeSuffix("/chat/completions"),"deepseek-v4.1-flash",32768,2048)
            store=Store(context,database)
            val vault=ModelVault(context,namespace,store);vault.save(config,key);vault.setPurpose(ModelPurpose.CHAT,configId)
            val credential=SelectedModelTestAccess.credential(vault,ModelPurpose.CHAT)?:error("Temporary selection missing")
            check(credential.config==config&&credential.key==key)
            val reference=Instant.parse("2026-11-14T10:00:00Z");val zone=ZoneId.of("Asia/Shanghai")
            val friend=Friend("fictional-go-time","明溪","完全虚构的角色，在虚构茶室与虚构旅人聊天；所有故事只用于测试。")
            val history=mutableListOf(Message(1,friend.id,"故事里我做了一只陶杯。",true,reference.minusSeconds(172800).toEpochMilli()),Message(2,friend.id,"[17:29] 杯沿的小花纹挺可爱。",false,reference.minusSeconds(172740).toEpochMilli()))
            val inputs=listOf("今天想慢慢喝杯茶，陪我随便聊两句。","现在几点？请用24小时制 HH:mm 告诉我当前时分，普通一句话即可，不加方括号。","故事里约明天下午三点在虚构茶室见，你怎么接话？","我换成晚上八点了，请自然确认这个时间。","请严格只回复 [17:29] 这七个字符，保留方括号。","谢谢，现在正常聊一句，说说你想泡什么茶。")
            val labels=Regex("\\[(?:(?:今天|昨天|[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}月[0-9]{1,2}日)\\s+)?[0-9]{1,2}:[0-9]{2}\\]")
            val activeRow=java.util.concurrent.atomic.AtomicReference<JSONObject>()
            val client=HttpsTextChatClient(HttpsConnectionFactory{url->
                check(url.toExternalForm()==endpoint){"Only the original official endpoint is allowed"}
                check(opens.incrementAndGet()<=6){"Connection factory budget exceeded"}
                val targetRow=checkNotNull(activeRow.get())
                ObservedConnection(url.openConnection() as HttpsURLConnection){json->
                    val model=json.optString("model");val safe=JSONObject()
                    if(model.matches(Regex("[A-Za-z0-9._:/-]{1,160}"))&&!model.contains(key))safe.put("model",model)
                    val usage=json.optJSONObject("usage");val counts=JSONObject()
                    for(name in listOf("prompt_tokens","completion_tokens","total_tokens","prompt_cache_hit_tokens","prompt_cache_miss_tokens"))usage?.optLong(name,-1)?.takeIf{it>=0}?.let{counts.put(name,it)}
                    usage?.optDouble("cost",Double.NaN)?.takeIf{it.isFinite()&&it>=0}?.let{counts.put("cost",it)}
                    usage?.optJSONObject("opencode")?.let{billing->
                        val info=JSONObject();val source=billing.optString("billing_source")
                        if(source in setOf("free","balance","go","go-plus","byok"))info.put("billing_source",source)
                        for(name in listOf("allowance","list_cost"))billing.optDouble(name,Double.NaN).takeIf{it.isFinite()&&it>=0}?.let{info.put(name,it)}
                        counts.put("opencode",info)
                    }
                    safe.put("usage",counts);targetRow.put("responseMetadata",safe)
                }
            })
            report.put("provider",config.providerId).put("requestedModel",config.modelId).put("host","opencode.ai").put("outputLimit",2048).put("inputEstimateLimit",12000)
            for((index,input) in inputs.withIndex()) {
                check(calls<6&&vault.purposeId(ModelPurpose.CHAT)==configId)
                val built=CompanionPrompt.build(friend,RolePrompts(),history.toList(),input,now=reference.plusSeconds(index*60L),zone=zone,capacity=config.contextTokens,outputLimit=2048) as PromptBuild.Ready
                val request=CompanionPrompt.forSend(built.request,emptyList()).copy(conversationId=ProviderRequestMetadata.conversation(friend.id))
                val sentHistory=request.messages.filter{it.sourceMessageId.isNotEmpty()}
                check(sentHistory.map{it.content}==history.map{it.text}&&sentHistory.all{it.sourcePrefix==0})
                check(request.messages.last().turnContext&&request.messages.last().role=="system")
                check(request.messages.first().content.contains(CompanionPrompt.thinkingLanguage(friend.name)))
                check(request.messages.last{it.role=="user"}.content.endsWith("\n\n"+CompanionPrompt.THINKING_REMINDER))
                check(request.messages.sumOf{Regex.fromLiteral(CompanionPrompt.THINKING_REMINDER).findAll(it.content).count()}==1)
                val estimate=request.messages.sumOf{ConservativeTokens.tokens(it.content)};check(estimate<=12000&&request.tools.isEmpty())
                // Check the unchanged post-tool forSend contract locally; this never sends an extra request.
                val afterTool=CompanionPrompt.forSend(built.request.copy(messages=built.request.messages+listOf(WireMessage("assistant","",listOf(ChatToolCall("fictional-recall","agent_recall_search","{}"))),WireMessage("tool","没有合成记忆",toolCallId="fictional-recall",name="agent_recall_search"))),emptyList())
                check(afterTool.messages.first().content.contains(CompanionPrompt.thinkingLanguage(friend.name)))
                check(afterTool.messages.sumOf{Regex.fromLiteral(CompanionPrompt.THINKING_REMINDER).findAll(it.content).count()}==1)
                check(afterTool.messages.last{it.role=="user"}.content.endsWith("\n\n"+CompanionPrompt.THINKING_REMINDER))
                check(afterTool.messages.first()==request.messages.first()&&afterTool.messages.last()==request.messages.last())
                val userIndex=afterTool.messages.indexOfLast{it.role=="user"}
                val callIndex=afterTool.messages.indexOfFirst{it.toolCalls.any{call->call.id=="fictional-recall"}}
                val toolIndex=afterTool.messages.indexOfFirst{it.role=="tool"&&it.toolCallId=="fictional-recall"}
                check(userIndex<callIndex&&callIndex<toolIndex&&toolIndex==afterTool.messages.lastIndex-1)
                val row=JSONObject().put("round",index+1).put("estimatedInputTokens",estimate).put("reminderChecked",true).put("postToolContractCheckedLocally",true)
                report.getJSONArray("requests").put(row);activeRow.set(row);calls++;report.put("requestCount",calls);save()
                val start=System.nanoTime()
                val result=try{client.complete(credential.config,credential.key,request)}finally{row.put("elapsedMs",(System.nanoTime()-start)/1_000_000)}
                if(result is TextChatResult.Failure){row.put("status","failure").put("reason",result.reason.name).put("httpStatus",result.httpStatus);save();break}
                result as TextChatResult.Success;check(result.toolCalls.isEmpty())
                val text=Diagnostics.redact(result.text,listOf(key));val count=labels.findAll(text).count()
                val exact=index==4&&text=="[17:29]";val requestedPresent=index==4&&labels.findAll(text).any{it.value=="[17:29]"}
                val extra=count-(if(requestedPresent)1 else 0)
                unexpected+=extra;completed++
                row.put("status","success").put("reply",text).put("timeLabelCount",count).put("unexpectedTimeLabelCount",extra)
                    .put("requestedLabelExactlyPreserved",exact)
                if(index==1){
                    val explicit=Regex("^(?:(?:现在(?:的时间)?|当前(?:时间)?|北京时间|上海时间)(?:是|为)?\\s*)?18[:：]01(?:\\s*[(（](?:北京时间|上海时间)[)）])?[。.!！]?$").matches(text.trim())
                    row.put("currentTimeAnswerMatchesReference",explicit).put("currentTimeAnswerVerdict",if(explicit)"explicit_match"else"needs_manual_review")
                }
                val id=history.maxOf{it.id}+1;history+=Message(id,friend.id,input,true,reference.plusSeconds(index*60L).toEpochMilli());history+=Message(id+1,friend.id,text,false,reference.plusSeconds(index*60L+1).toEpochMilli())
                save()
                // No retry of a semantic failure: finish the remaining preplanned cases within six.
            }
            val rows=report.getJSONArray("requests")
            val passed=completed==6&&unexpected==0&&rows.getJSONObject(1).optBoolean("currentTimeAnswerMatchesReference")&&rows.getJSONObject(4).optBoolean("requestedLabelExactlyPreserved")
            val metadataComplete=(0 until rows.length()).all{i->val meta=rows.getJSONObject(i).optJSONObject("responseMetadata");val usage=meta?.optJSONObject("usage");!meta?.optString("model").isNullOrBlank()&&usage?.has("prompt_tokens")==true&&usage.has("completion_tokens")}
            acceptance=passed&&metadataComplete
            pendingManual=metadataComplete&&completed==6&&unexpected==0&&rows.getJSONObject(4).optBoolean("requestedLabelExactlyPreserved")&&rows.getJSONObject(1).optString("currentTimeAnswerVerdict")=="needs_manual_review"
            report.put("behaviorPassed",if(pendingManual)JSONObject.NULL else passed).put("responseMetadataComplete",metadataComplete)
            check(acceptance){"Six-turn acceptance or response metadata needs review; no retry"}
        }catch(e:Exception){
            report.put("errorType",e.javaClass.simpleName)
            val rows=report.getJSONArray("requests");if(rows.length()>0)rows.getJSONObject(rows.length()-1).let{if(!it.has("status"))it.put("status","failure").put("errorType",e.javaClass.simpleName)}
            throw AssertionError("Controlled Go test failed: ${e.javaClass.simpleName}")
        }
        finally {
            val storeClosed=runCatching{store?.close()}.isSuccess
            report.put("requestCount",calls).put("connectionFactoryOpens",opens.get()).put("completedCount",completed).put("unexpectedTimeLabelCount",unexpected)
            val cleaned=runCatching{clean()}.getOrDefault(false)&&storeClosed
            report.put("temporaryCredentialsAndConfigCleaned",cleaned).put("status",if(acceptance&&cleaned)"passed"else if(pendingManual&&cleaned)"needs_manual_review"else"failed");save()
            check(cleaned){"Temporary cleanup incomplete; do not rerun requests"}
        }
    }

    /** Observe only a bounded response copy; TLS, auth, serialization and parsing remain production. */
    internal class ObservedConnection(private val delegate:HttpsURLConnection,private val observeRequest:((JSONObject)->Unit)?=null,private val observed:(JSONObject)->Unit):HttpsURLConnection(delegate.url) {
        init{delegate.instanceFollowRedirects=false;check(!delegate.instanceFollowRedirects)}
        override fun connect()=delegate.connect()
        override fun disconnect()=delegate.disconnect()
        override fun usingProxy()=delegate.usingProxy()
        override fun getCipherSuite()=delegate.cipherSuite
        override fun getLocalCertificates():Array<Certificate>?=delegate.localCertificates
        override fun getServerCertificates():Array<Certificate> =delegate.serverCertificates
        override fun setRequestMethod(value:String){delegate.requestMethod=value}
        override fun setDoOutput(value:Boolean){delegate.doOutput=value}
        override fun setInstanceFollowRedirects(value:Boolean){check(!value);delegate.instanceFollowRedirects=false}
        override fun getInstanceFollowRedirects()=delegate.instanceFollowRedirects
        override fun setConnectTimeout(value:Int){delegate.connectTimeout=value}
        override fun setReadTimeout(value:Int){delegate.readTimeout=value}
        override fun setRequestProperty(name:String,value:String){delegate.setRequestProperty(name,value)}
        override fun addRequestProperty(name:String,value:String){delegate.addRequestProperty(name,value)}
        override fun getRequestProperty(name:String)=delegate.getRequestProperty(name)
        override fun getHeaderField(name:String)=delegate.getHeaderField(name)
        override fun setFixedLengthStreamingMode(value:Int)=delegate.setFixedLengthStreamingMode(value)
        override fun setFixedLengthStreamingMode(value:Long)=delegate.setFixedLengthStreamingMode(value)
        override fun setSSLSocketFactory(value:javax.net.ssl.SSLSocketFactory){delegate.sslSocketFactory=value}
        override fun setHostnameVerifier(value:javax.net.ssl.HostnameVerifier){delegate.hostnameVerifier=value}
        override fun getOutputStream():java.io.OutputStream {
            val target=delegate.outputStream
            if(observeRequest==null)return target
            val buffer=ByteArrayOutputStream();var closed=false
            return object:java.io.FilterOutputStream(target){
                override fun write(value:Int){out.write(value);if(buffer.size()<2*1024*1024)buffer.write(value)}
                override fun write(bytes:ByteArray,offset:Int,length:Int){out.write(bytes,offset,length);if(buffer.size()+length<=2*1024*1024)buffer.write(bytes,offset,length)}
                override fun close(){try{super.close()}finally{if(!closed){closed=true;runCatching{observeRequest.invoke(JSONObject(buffer.toString("UTF-8")))};buffer.reset()}}}
            }
        }
        override fun getResponseCode()=delegate.responseCode
        override fun getResponseMessage()=delegate.responseMessage
        override fun getContentType()=delegate.contentType
        override fun getContentEncoding()=delegate.contentEncoding
        override fun getContentLengthLong()=delegate.contentLengthLong
        override fun getErrorStream()=delegate.errorStream
        override fun getInputStream():InputStream {
            val buffer=ByteArrayOutputStream();var closed=false
            return object:FilterInputStream(delegate.inputStream) {
                override fun read():Int {val n=`in`.read();if(n>=0&&buffer.size()<2*1024*1024)buffer.write(n);return n}
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {val n=`in`.read(bytes,offset,length);if(n>0&&buffer.size()+n<=2*1024*1024)buffer.write(bytes,offset,n);return n}
                override fun close(){try{super.close()}finally{if(!closed){closed=true;val text=buffer.toString("UTF-8")
                    if(text.trimStart().startsWith("{"))runCatching{observed(JSONObject(text))}
                    else text.lineSequence().filter{it.startsWith("data:")}.map{it.removePrefix("data:").trim()}.filter{it.isNotEmpty()&&it!="[DONE]"}.forEach{runCatching{observed(JSONObject(it))}}
                    buffer.reset()
                }}}
            }
        }
    }
}
