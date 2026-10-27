package app.lianxu.independent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-config AES-GCM keys stay in AndroidKeyStore. Entire records live encrypted in noBackupFilesDir. */
class ModelVault(context:Context,private val namespace:String="model-configs",private val store:Store?=null) {
    private val root=File(context.noBackupFilesDir,namespace).apply {mkdirs()}
    internal val generationKey get()="model-vault-generation:$namespace"
    private val directory:File get(){
        val generation=store?.readableDatabase?.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(generationKey))?.use{if(it.moveToFirst())it.getString(0) else ""}.orEmpty()
        require(generation.isEmpty()||generation.matches(Regex("[a-f0-9-]{36}")))
        return if(generation.isEmpty())root else File(root,"generations/$generation")
    }
    private val keyStore=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private fun file(id:String):File { require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))); return File(directory,"$id.enc") }
    private fun alias(id:String)="lianxu.$namespace.$id"
    private fun key(id:String):SecretKey = (keyStore.getKey(alias(id),null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias(id),KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    }.generateKey()
    private fun read(id:String):JSONObject {
        val bytes=AtomicFile(file(id)).readFully()
        require(bytes.size>28 && bytes[0].toInt()==1)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        val stored=keyStore.getKey(alias(id),null) as? SecretKey ?: error("Key unavailable")
        cipher.init(Cipher.DECRYPT_MODE,stored,GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8)).also{Diagnostics.registerSecret(it.getString("key"))}
    }
    fun list():List<ModelConfig> = AppMutationGate.read { directory.listFiles().orEmpty().filter {it.extension=="enc"}.map {f ->
        val j=read(f.nameWithoutExtension)
        ModelConfig(f.nameWithoutExtension,j.getString("name"),j.getString("provider"),j.getString("base"),j.getString("model"),j.optInt("contextTokens",16384),j.optInt("outputTokens",2048),j.optInt("memoryOutputTokens",0))
    }.sortedBy {it.name} }
    fun secret(id:String)=AppMutationGate.read {read(id).getString("key").also{Diagnostics.registerSecret(it)}}
    fun save(config:ModelConfig,secret:String):Unit = AppMutationGate.write {
        Diagnostics.registerSecret(secret)
        require(ConfigRules.validate(config,secret)==null)
        writeAt(directory,config,secret)
        if(defaultId().isEmpty()) setDefault(config.id)
    }
    private fun writeAt(destination:File,config:ModelConfig,secret:String){
        Diagnostics.registerSecret(secret)
        destination.mkdirs()
        val data=JSONObject().put("name",config.name).put("provider",config.providerId).put("base",config.baseUrl).put("model",config.modelId).put("key",secret).put("contextTokens",config.contextTokens).put("outputTokens",config.outputTokens).put("memoryOutputTokens",config.memoryOutputTokens).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(config.id));cipher.updateAAD(config.id.toByteArray(Charsets.UTF_8))
        val encrypted=byteArrayOf(1)+cipher.iv+cipher.doFinal(data); data.fill(0)
        val atomic=AtomicFile(File(destination,"${config.id}.enc"));val stream=atomic.startWrite()
        try {stream.write(encrypted);atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
    }
    fun delete(id:String) = AppMutationGate.write {
        keyStore.deleteEntry(alias(id)); AtomicFile(file(id)).delete()
        val selections=ModelPurpose.entries.associateWith{purposeId(it)}
        selections.filterValues{it==id}.keys.forEach{setPurpose(it, "")}
    }
    // Existing default-id is read as the chat purpose until first explicit purpose write.
    // Saving/selecting never contacts a provider. A deleted selection is cleared, not switched silently.
    private fun purposeValues():JSONObject {
        val file=File(directory,"purpose-selections")
        return if(file.exists())JSONObject(String(AtomicFile(file).readFully(),Charsets.UTF_8))
        else JSONObject().put(ModelPurpose.CHAT.key,File(directory,"default-id").takeIf{it.exists()}?.readText().orEmpty())
    }
    fun purposeId(purpose:ModelPurpose):String = AppMutationGate.read {purposeValues().optString(purpose.key)}
    fun setPurpose(purpose:ModelPurpose,id:String) = AppMutationGate.write {
        require(id.isEmpty() || file(id).exists())
        val value=purposeValues().put(purpose.key,id)
        val atomic=AtomicFile(File(directory,"purpose-selections"));val stream=atomic.startWrite()
        try{stream.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
    }
    fun defaultId():String = purposeId(ModelPurpose.CHAT)
    fun setDefault(id:String) {setPurpose(ModelPurpose.CHAT,id)}
    fun selectionStamp(purpose:ModelPurpose):String = AppMutationGate.read {
        val id=purposeId(purpose);if(id.isEmpty()||!file(id).exists()) "" else stableDigest(id+":"+stableDigest(java.util.Base64.getEncoder().encodeToString(AtomicFile(file(id)).readFully())))
    }
    fun hasKey(id:String)=AppMutationGate.read {keyStore.containsAlias(alias(id))}
    internal fun backup(includeKeys:Boolean):Pair<List<BackupModel>,Map<ModelPurpose,String>> = AppMutationGate.read {
        list().map{BackupModel(it,if(includeKeys)secret(it.id) else null)} to ModelPurpose.entries.associateWith{purposeId(it)}
    }
    internal class Prepared(val generation:String,val directory:File,val createdAliases:List<String>)
    internal fun prepareRestore(incoming:List<BackupModel>,selections:Map<ModelPurpose,String>,includeKeys:Boolean):Prepared = AppMutationGate.read {
        check(store!=null){"全量恢复需要绑定数据库"}
        val generation=java.util.UUID.randomUUID().toString();val dir=File(root,"generations/$generation")
        val old=list();val aliases=mutableListOf<String>()
        try {
            val restored=incoming.map{row->
                val match=old.firstOrNull{it.id==row.config.id&&it.providerId==row.config.providerId&&it.baseUrl==row.config.baseUrl}
                BackupModel(row.config,if(includeKeys)row.secret.orEmpty() else match?.let{secret(it.id)}.orEmpty())
            }.toMutableList()
            if(!includeKeys)old.filter{oldConfig->incoming.none{it.config.id==oldConfig.id&&it.config.providerId==oldConfig.providerId&&it.config.baseUrl==oldConfig.baseUrl}}.forEach{oldConfig->
                val key=secret(oldConfig.id)
                if(key.isNotEmpty())restored+=BackupModel(oldConfig.copy(id="retained-"+java.util.UUID.randomUUID(),name=oldConfig.name+"（保留原密钥，未自动选择）"),key)
            }
            restored.forEach{row->if(!keyStore.containsAlias(alias(row.config.id)))aliases+=alias(row.config.id);writeAt(dir,row.config,row.secret.orEmpty())}
            val selected=JSONObject();ModelPurpose.entries.forEach{p->val id=selections[p].orEmpty();selected.put(p.key,if(incoming.any{it.config.id==id})id else "")}
            File(dir,"purpose-selections").writeText(selected.toString(),Charsets.UTF_8)
            Prepared(generation,dir,aliases)
        }catch(e:Exception){dir.deleteRecursively();aliases.forEach{keyStore.deleteEntry(it)};throw e}
    }
    internal fun isActive(prepared:Prepared):Boolean? = runCatching{
        if(store?.readableDatabase?.inTransaction()==true)null else directory.canonicalFile==prepared.directory.canonicalFile
    }.getOrNull()
    internal fun abort(prepared:Prepared){
        check(isActive(prepared)==false){"不能清理已启用或提交状态不确定的密钥记录"}
        prepared.directory.deleteRecursively();prepared.createdAliases.forEach{keyStore.deleteEntry(it)}
    }
    internal fun finishRestore(prepared:Prepared){
        check(isActive(prepared)==true){"密钥记录尚未完成切换"}
        // Cleanup is best-effort after publication. Failure cannot turn a successful restore into failure.
        runCatching{
            val active=prepared.directory.canonicalFile
            File(root,"generations").listFiles().orEmpty().filter{it.isDirectory&&it.canonicalFile!=active}.forEach{it.deleteRecursively()}
            root.listFiles().orEmpty().filter{it.isFile&&(it.name.endsWith(".enc")||it.name.endsWith(".enc.bak")||it.name in setOf("default-id","purpose-selections","purpose-selections.bak"))}.forEach{it.delete()}
        }.onFailure{Diagnostics.failure("backup.vault.cleanup.failed",it)}
    }
    internal fun stamp():String=AppMutationGate.read {stableDigest(list().joinToString("|"){it.toString()+":"+java.util.Base64.getEncoder().encodeToString(AtomicFile(file(it.id)).readFully())}+purposeValues().toString())}

}

/** A portable backup may intentionally contain plaintext credentials; never log this object. */
internal class BackupModel(val config:ModelConfig,val secret:String?) {override fun toString()="BackupModel(redacted)"}
