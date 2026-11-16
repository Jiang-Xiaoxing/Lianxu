package app.lianxu.independent

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.security.MessageDigest

enum class ProbePhase { UNTESTED, TESTING, REACHABLE, FAILED }
data class ProbeDisplay(val phase:ProbePhase=ProbePhase.UNTESTED,val text:String="未测试")
private class SharedCreationModel:Exception()
private class StaleModelEditor:Exception()

class ModelConfigState @JvmOverloads constructor(
    application:Application,
    private val client:ModelDirectoryClient=HttpsModelClient(),
    vaultNamespace:String=AppServices.vaultNamespace
):AndroidViewModel(application) {
    private val storage=Store(application,AppServices.databaseName)
    private val vault=ModelVault(application,vaultNamespace,storage)
    private val diagnosticClient=LoggedModelDirectoryClient(client)
    private val statusStore=ModelStatusStore(storage,vault.statusNamespace)
    internal var embeddingClient:MemoryEmbeddingClient=HttpsMemoryEmbeddingClient()
    var configs by mutableStateOf(emptyList<ModelConfig>());private set
    var defaultId by mutableStateOf("");private set
    var purposes by mutableStateOf(emptyMap<ModelPurpose,String>());private set
    var saving by mutableStateOf(false);private set
    var testing by mutableStateOf(false);private set
    var fetching by mutableStateOf(false);private set
    val busy get()=saving||testing
    var status by mutableStateOf("");private set
    var statusError by mutableStateOf(false);private set
    var models by mutableStateOf(emptyList<String>());private set
    var probe by mutableStateOf(ProbeDisplay());private set
    var connections by mutableStateOf(emptyMap<String,ProbeDisplay>());private set
    /** Overview lines: last explicit test (time, model, result) and the latest real call result. */
    internal var tests by mutableStateOf(emptyMap<String,ModelTestRecord>());private set
    internal var calls by mutableStateOf(emptyMap<String,ModelCallRecord>());private set
    private var pendingTest:ModelTestRecord?=null
    private var requestVersion=0L
    private var requestJob:Job?=null
    private var probeStamp:String?=null
    init {viewModelScope.launch {try {reload()}catch(_:Exception){message("无法读取加密配置，请重试。",true)}}}
    private fun message(text:String,error:Boolean=false){status=text;statusError=error}
    fun refresh(){viewModelScope.launch{runCatching{reload()}.onFailure{message("无法读取恢复后的配置，请重试。",true)}}}
    private suspend fun reload(){val value=withContext(Dispatchers.IO){Triple(vault.list(),vault.defaultId(),ModelPurpose.entries.associateWith{vault.purposeId(it)})};configs=value.first;defaultId=value.second;purposes=value.third;reloadStatus()}
    private suspend fun reloadStatus(){
        tests=configs.mapNotNull{c->statusStore.test(c.id)?.let{c.id to it}}.toMap()
        calls=configs.mapNotNull{c->statusStore.lastCall(c.id)?.let{c.id to it}}.toMap()
    }
    /** A test of the saved, unchanged configuration is its "last test"; a draft's result waits for a matching save. */
    private suspend fun tested(config:ModelConfig,key:String,ok:Boolean,reason:String){
        val record=ModelTestRecord(System.currentTimeMillis(),config.baseUrl,config.modelId,ok,reason);pendingTest=record
        // Only address, model and key define what a test proves; budgets or the display name do not.
        if(key.isEmpty()&&configs.any{it.id==config.id&&it.providerId==config.providerId&&it.baseUrl==config.baseUrl&&it.modelId==config.modelId}){statusStore.saveTest(config.id,record);tests=tests+(config.id to record)}
    }
    /** Invalidate UI requests on edits, back, disposal and background; keep an explicit disk commit. */
    fun clearResult(){requestVersion++;requestJob?.cancel();requestJob=null;testing=false;fetching=false;probe=ProbeDisplay();probeStamp=null;pendingTest=null;models=emptyList();status="";statusError=false}
    fun cancelFetch(){if(fetching){clearResult();message("已取消获取，当前模型未更改。")}}
    private suspend fun secret(config:ModelConfig,entered:String):String {
        if(entered.isNotEmpty())return entered
        return withContext(Dispatchers.IO){AppMutationGate.read{
            ModelKeyDraft.resolve(vault.list().find{it.id==config.id},config,entered){vault.secret(config.id)}
        }}
    }
    suspend fun savedKey(config:ModelConfig):String = withContext(Dispatchers.IO) {
        try { AppMutationGate.read{ModelKeyDraft.resolve(vault.list().find{it.id==config.id},config,""){vault.secret(config.id)}} } catch(e:CancellationException) { throw e }
        catch(_:Exception) { "" }
    }
    // Ephemeral fingerprint, never persisted or logged.
    private fun stamp(config:ModelConfig,key:String)=MessageDigest.getInstance("SHA-256").digest(listOf(config.id,config.name,config.providerId,config.baseUrl,config.modelId,key).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
    fun save(config:ModelConfig,key:String,purpose:ModelPurpose?=null,original:ModelConfig?=configs.find{it.id==config.id},onSaved:()->Unit){
        saveConfig(config,key,purpose,false,original,onSaved)
    }
    internal fun saveChatFromCreation(config:ModelConfig,key:String,original:ModelConfig?=configs.find{it.id==config.id},onSaved:()->Unit){
        saveConfig(config,key,ModelPurpose.CHAT,true,original,onSaved)
    }
    private fun saveConfig(config:ModelConfig,key:String,purpose:ModelPurpose?,protectOthers:Boolean,original:ModelConfig?,onSaved:()->Unit){
        if(busy)return;saving=true;status=""
        viewModelScope.launch {
        var attempted=false;var configurationConfirmed=false;var selectionConfirmed=false
        try {
            var old:ModelConfig?=null;var oldKey="";var resolved=""
            val invalid=withContext(Dispatchers.IO){AppMutationGate.write<String?>{
                if(protectOthers&&ModelPurpose.entries.any{it!=ModelPurpose.CHAT&&vault.purposeId(it)==config.id})throw SharedCreationModel()
                old=vault.list().find{it.id==config.id}
                if(old!=original)throw StaleModelEditor()
                oldKey=if(old!=null)vault.secret(config.id)else""
                resolved=ModelKeyDraft.resolve(old,config,key){oldKey}
                ConfigRules.validate(config,resolved)?.let{return@write it}
                attempted=true
                if(ModelKeyDraft.needsWrite(old,config,oldKey,resolved))vault.save(config,resolved)
                configurationConfirmed=true
                if(purpose!=null){vault.setPurpose(purpose,config.id);selectionConfirmed=true}
                null
            }}
            if(invalid!=null){message(invalid,true);return@launch}
            val keep=probeStamp==stamp(config,resolved)&&probe.phase in setOf(ProbePhase.REACHABLE,ProbePhase.FAILED)
            // Address, key or model changed: the old test no longer describes this configuration.
            val changedTarget=old?.let{it.baseUrl!=config.baseUrl||it.modelId!=config.modelId||it.providerId!=config.providerId||oldKey!=resolved}?:true
            val pending=pendingTest
            if(keep&&pending!=null)statusStore.saveTest(config.id,pending) else if(changedTarget)statusStore.clearTest(config.id)
            reload()
            connections=if(keep)connections+(config.id to probe) else connections-config.id
            val savedText=if(purpose!=null)"已保存并用于${purpose.title}；保存本身不发送请求。"else"已保存，请在模型用途中选择这份配置；保存本身不发送请求。"
            message(ProviderRequestMetadata.compatibilityNotice(config)?.let{"$savedText $it"}?:savedText)
            onSaved()
        }catch(e:CancellationException){throw e}
        catch(_:StaleModelEditor){message("这份配置已在别处更改，请返回并重新打开后再保存。当前设置未被覆盖。",true)}
        catch(_:SharedCreationModel){runCatching{reload()};message("这份配置也用于其他用途，创建引导中不能修改。请新增全局聊天配置，或仅选择现有配置用于聊天。",true)}
        catch(_:Exception){message(when{
            configurationConfirmed&&(purpose==null||selectionConfirmed)->"配置${if(purpose!=null)"和${purpose.title}用途"else""}已保存，但状态刷新未完成。返回后可重新读取。"
            configurationConfirmed->"配置已保存，但用途写入未确认。请返回核对或重试。"
            attempted->"保存未完整完成，部分配置可能已写入。请返回核对或重试。"
            else->"保存失败，请重试。"
        },true)}finally{saving=false}}
    }
    fun testEmbedding(config:ModelConfig,key:String){
        if(busy)return;clearResult();val version=requestVersion;testing=true
        probe=ProbeDisplay(ProbePhase.TESTING,"正在测试向量接口…")
        requestJob=viewModelScope.launch {try {
            val resolved=secret(config,key)
            val invalid=ConfigRules.validate(config,resolved)
            if(invalid!=null){probe=ProbeDisplay(ProbePhase.FAILED,invalid);return@launch}
            val vectors=withContext(Dispatchers.IO){embeddingClient.embed(ModelCredential(config,resolved,"configuration-probe"),listOf("向量连接测试"),10000)}
            if(version!=requestVersion)return@launch
            val vector=vectors.single();require(MemoryVectorIndex.valid(vector))
            probe=ProbeDisplay(ProbePhase.REACHABLE,"向量接口可用 · 返回 ${vector.size} 维（自动）");probeStamp=stamp(config,resolved)
            tested(config,key,true,"")
        }catch(e:CancellationException){throw e}
        catch(_:Exception){if(version==requestVersion){probe=ProbeDisplay(ProbePhase.FAILED,"向量测试未通过，请核对模型 ID、权限和 /embeddings 兼容性。");runCatching{tested(config,key,false,"向量接口未通过")}}}
        finally{if(version==requestVersion)testing=false}}
    }
    fun makeDefault(config:ModelConfig){if(busy)return;saving=true;viewModelScope.launch {try{withContext(Dispatchers.IO){vault.setDefault(config.id)};reload();message("已设为默认，没有发起网络请求。 ")}catch(_:Exception){message("默认配置保存失败。",true)}finally{saving=false}}}
    fun selectPurpose(purpose:ModelPurpose,id:String){
        if(busy)return;saving=true
        viewModelScope.launch{
            var attempted=false;var confirmed=false
            try{withContext(Dispatchers.IO){attempted=true;vault.setPurpose(purpose,id);confirmed=true};reload();message("用途选择已保存，没有发起网络请求。")}
            catch(e:CancellationException){throw e}
            catch(_:Exception){message(if(confirmed)"用途选择已保存，但状态刷新未完成。返回后可重新读取。"else if(attempted)"用途写入未确认，可能已改变。请返回核对或重试。"else"用途保存失败，请重试。",true)}finally{saving=false}
        }
    }
    fun delete(config:ModelConfig){if(busy)return;clearResult();saving=true;viewModelScope.launch {try{withContext(Dispatchers.IO){vault.delete(config.id)};statusStore.clearAll(config.id);reload();connections=connections-config.id;message("配置和关联密钥已删除。")}catch(_:Exception){message("删除未完成，请重试。",true)}finally{saving=false}}}
    fun request(config:ModelConfig,key:String,fetch:Boolean){
        if(busy)return;clearResult();val version=requestVersion;testing=true;fetching=fetch
        if(!fetch)probe=ProbeDisplay(ProbePhase.TESTING,"正在测试…") else message("正在获取模型…")
        requestJob=viewModelScope.launch {try {
            val resolved=secret(config,key)
            if(version!=requestVersion)return@launch
            val invalid=ConfigRules.urlError(config.baseUrl)?:if(resolved.isBlank())"请填写 API Key；地址或服务商改变后需重新输入。" else null
            if(invalid!=null){if(fetch)message(invalid,true) else probe=ProbeDisplay(ProbePhase.FAILED,invalid);return@launch}
            val result=diagnosticClient.request(config,resolved,fetch)
            if(version!=requestVersion)return@launch
            when(result){
                is ConfigResult.Success->if(fetch){models=result.modelIds;message(if(models.isEmpty())"目录为空，可直接填写模型 ID。" else "已获取 ${models.size} 个条目，当前模型未更改。")} else {
                    probe=ProbeDisplay(ProbePhase.REACHABLE,"目录/认证测试可达");probeStamp=stamp(config,resolved)
                    tested(config,key,true,"")
                    if(key.isEmpty()&&configs.any{it==config})connections=connections+(config.id to probe)
                }
                is ConfigResult.Failure->if(fetch)message(result.error.messageText,true) else {
                    probe=ProbeDisplay(ProbePhase.FAILED,result.error.messageText);probeStamp=stamp(config,resolved)
                    tested(config,key,false,result.error.messageText)
                    if(key.isEmpty()&&configs.any{it==config})connections=connections+(config.id to probe)
                }
            }
        }catch(e:CancellationException){throw e}
        catch(_:Exception){if(version==requestVersion){if(fetch)message("获取失败，请重试。",true) else probe=ProbeDisplay(ProbePhase.FAILED,"连接失败，请重试。")}}
        finally{if(version==requestVersion){testing=false;fetching=false}}}
    }
}
