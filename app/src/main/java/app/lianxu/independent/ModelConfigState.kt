package app.lianxu.independent

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.security.MessageDigest

enum class ProbePhase { UNTESTED, TESTING, REACHABLE, FAILED }
data class ProbeDisplay(val phase:ProbePhase=ProbePhase.UNTESTED,val text:String="未测试")

class ModelConfigState @JvmOverloads constructor(
    application:Application,
    private val client:ModelDirectoryClient=HttpsModelClient(),
    vaultNamespace:String=AppServices.vaultNamespace
):AndroidViewModel(application) {
    private val vault=ModelVault(application,vaultNamespace)
    var configs by mutableStateOf(emptyList<ModelConfig>());private set
    var defaultId by mutableStateOf("");private set
    var purposes by mutableStateOf(emptyMap<ModelPurpose,String>());private set
    var saving by mutableStateOf(false);private set
    var testing by mutableStateOf(false);private set
    val busy get()=saving||testing
    var status by mutableStateOf("");private set
    var statusError by mutableStateOf(false);private set
    var models by mutableStateOf(emptyList<String>());private set
    var probe by mutableStateOf(ProbeDisplay());private set
    var connections by mutableStateOf(emptyMap<String,ProbeDisplay>());private set
    private var requestVersion=0L
    private var requestJob:Job?=null
    private var probeStamp:String?=null
    init {viewModelScope.launch {try {reload()}catch(_:Exception){message("无法读取加密配置，请重试。",true)}}}
    private fun message(text:String,error:Boolean=false){status=text;statusError=error}
    private suspend fun reload(){val value=withContext(Dispatchers.IO){Triple(vault.list(),vault.defaultId(),ModelPurpose.entries.associateWith{vault.purposeId(it)})};configs=value.first;defaultId=value.second;purposes=value.third}
    /** Invalidate UI requests on edits, back, disposal and background; keep an explicit disk commit. */
    fun clearResult(){requestVersion++;requestJob?.cancel();requestJob=null;testing=false;probe=ProbeDisplay();probeStamp=null;models=emptyList();status=""}
    private suspend fun secret(config:ModelConfig,entered:String):String {
        if(entered.isNotEmpty())return entered
        val old=configs.find{it.id==config.id}
        return if(old?.baseUrl==config.baseUrl&&old.providerId==config.providerId)withContext(Dispatchers.IO){vault.secret(config.id)} else ""
    }
    // Ephemeral fingerprint, never persisted or logged.
    private fun stamp(config:ModelConfig,key:String)=MessageDigest.getInstance("SHA-256").digest(listOf(config.id,config.name,config.providerId,config.baseUrl,config.modelId,key).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
    fun save(config:ModelConfig,key:String,onSaved:()->Unit){
        if(busy)return;saving=true;status=""
        viewModelScope.launch {try {
            val resolved=secret(config,key);val invalid=ConfigRules.validate(config,resolved)
            if(invalid!=null){message(invalid,true);return@launch}
            val keep=probeStamp==stamp(config,resolved)&&probe.phase in setOf(ProbePhase.REACHABLE,ProbePhase.FAILED)
            withContext(Dispatchers.IO){vault.save(config,resolved)};reload()
            connections=if(keep)connections+(config.id to probe) else connections-config.id
            message("已保存。配置留在这台设备，不会自动发送聊天请求。")
            onSaved()
        }catch(e:CancellationException){throw e}
        catch(_:Exception){message("保存失败，请重试。",true)}finally{saving=false}}
    }
    fun makeDefault(config:ModelConfig){if(busy)return;saving=true;viewModelScope.launch {try{withContext(Dispatchers.IO){vault.setDefault(config.id)};reload();message("已设为默认，没有发起网络请求。 ")}catch(_:Exception){message("默认配置保存失败。",true)}finally{saving=false}}}
    fun selectPurpose(purpose:ModelPurpose,id:String){
        if(busy)return;saving=true
        viewModelScope.launch{try{withContext(Dispatchers.IO){vault.setPurpose(purpose,id)};reload();message("用途选择已保存，没有发起网络请求。")}
        catch(_:Exception){message("用途保存失败，请重试。",true)}finally{saving=false}}
    }
    fun delete(config:ModelConfig){if(busy)return;clearResult();saving=true;viewModelScope.launch {try{withContext(Dispatchers.IO){vault.delete(config.id)};reload();connections=connections-config.id;message("配置和关联密钥已删除。")}catch(_:Exception){message("删除未完成，请重试。",true)}finally{saving=false}}}
    fun request(config:ModelConfig,key:String,fetch:Boolean){
        if(busy)return;clearResult();val version=requestVersion;testing=true
        if(!fetch)probe=ProbeDisplay(ProbePhase.TESTING,"正在测试…") else message("正在获取模型…")
        requestJob=viewModelScope.launch {try {
            val resolved=secret(config,key)
            if(version!=requestVersion)return@launch
            val invalid=ConfigRules.urlError(config.baseUrl)?:if(resolved.isBlank())"请填写 API Key；地址或服务商改变后需重新输入。" else null
            if(invalid!=null){if(fetch)message(invalid,true) else probe=ProbeDisplay(ProbePhase.FAILED,invalid);return@launch}
            val result=client.request(config,resolved,fetch)
            if(version!=requestVersion)return@launch
            when(result){
                is ConfigResult.Success->if(fetch){models=result.modelIds;message(if(models.isEmpty())"目录为空，可直接填写模型 ID。" else "已取得 ${models.size} 个条目，点选填入。")} else {
                    probe=ProbeDisplay(ProbePhase.REACHABLE,"目录/认证测试可达");probeStamp=stamp(config,resolved)
                    if(key.isEmpty()&&configs.any{it==config})connections=connections+(config.id to probe)
                }
                is ConfigResult.Failure->if(fetch)message(result.error.messageText,true) else {
                    probe=ProbeDisplay(ProbePhase.FAILED,result.error.messageText);probeStamp=stamp(config,resolved)
                    if(key.isEmpty()&&configs.any{it==config})connections=connections+(config.id to probe)
                }
            }
        }catch(e:CancellationException){throw e}
        catch(_:Exception){if(version==requestVersion){if(fetch)message("获取失败，请重试。",true) else probe=ProbeDisplay(ProbePhase.FAILED,"连接失败，请重试。")}}
        finally{if(version==requestVersion)testing=false}}
    }
}
