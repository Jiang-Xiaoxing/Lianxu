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
class ModelVault(context:Context,private val namespace:String="model-configs") {
    private val directory=File(context.noBackupFilesDir,namespace).apply {mkdirs()}
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
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8))
    }
    fun list():List<ModelConfig> = AppMutationGate.read { directory.listFiles().orEmpty().filter {it.extension=="enc"}.map {f ->
        val j=read(f.nameWithoutExtension)
        ModelConfig(f.nameWithoutExtension,j.getString("name"),j.getString("provider"),j.getString("base"),j.getString("model"),j.optInt("contextTokens",16384),j.optInt("outputTokens",2048))
    }.sortedBy {it.name} }
    fun secret(id:String)=AppMutationGate.read {read(id).getString("key")}
    fun save(config:ModelConfig,secret:String):Unit = AppMutationGate.write {
        require(ConfigRules.validate(config,secret)==null)
        val data=JSONObject().put("name",config.name).put("provider",config.providerId).put("base",config.baseUrl).put("model",config.modelId).put("key",secret).put("contextTokens",config.contextTokens).put("outputTokens",config.outputTokens).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(config.id));cipher.updateAAD(config.id.toByteArray(Charsets.UTF_8))
        val encrypted=byteArrayOf(1)+cipher.iv+cipher.doFinal(data); data.fill(0)
        val atomic=AtomicFile(file(config.id));val stream=atomic.startWrite()
        try {stream.write(encrypted);atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
        if(defaultId().isEmpty()) setDefault(config.id)
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
}
