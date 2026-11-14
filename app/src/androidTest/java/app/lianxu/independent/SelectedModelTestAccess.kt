package app.lianxu.independent

import org.json.JSONObject

/** Instrumentation-only adapter to our existing single-record AES reader. Never ships in the app.
 * Reflection keeps this test access out of the production API. A changed reader fails closed.
 * The key stays in the target process and only the selected record is decrypted; no cache is loaded. */
internal object SelectedModelTestAccess {
    fun credential(vault:ModelVault,purpose:ModelPurpose):ModelCredential?=AppMutationGate.read {
        val id=vault.purposeId(purpose);val stamp=vault.selectionStamp(purpose)
        if(id.isBlank()||stamp.isBlank())return@read null
        val j=try{
            val method=ModelVault::class.java.getDeclaredMethod("read",String::class.java).apply{isAccessible=true}
            method.invoke(vault,id) as JSONObject
        }catch(_:Exception){error("Selected encrypted record could not be read; no request sent")}
        val key=j.getString("key");if(key.isBlank())return@read null
        val provider=j.getString("provider");val base=j.getString("base");val model=j.getString("model")
        val (context,output)=ModelTokenPolicy.liftLegacy(j.optInt("contextTokens",ModelTokenPolicy.DEFAULT_CONTEXT),j.optInt("outputTokens",ModelTokenPolicy.DEFAULT_CHAT),provider,base,model)
        val config=ModelConfig(id,j.getString("name"),provider,base,model,context,output,j.optInt("memoryOutputTokens",0))
        ModelCredential(config,key,stamp)
    }
}
