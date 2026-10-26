package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Instrumentation-only data/client injection; no credentials or fake reply in the production default. */
class MockChatFixture(private val clientFactory:(()->TextChatClient)?=null,private val base:String="https://example.invalid/v1"):ExternalResource(){
    private val id=System.nanoTime().toString()
    val namespace="mock-chat-$id"
    val database="mock-chat-$id.db"
    override fun before(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        AppServices.databaseName=database;AppServices.vaultNamespace=namespace
        ModelVault(context,namespace).save(ModelConfig("fixture","合成测试服务","custom",base,"synthetic-model"),"synthetic-key-only")
        AppServices.createTextClient=clientFactory?:{object:TextChatClient{
            override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult {
                check(key=="synthetic-key-only")
                return TextChatResult.Success("【Mock API 测试】"+request.messages.last{it.role=="user"}.content)
            }
        }}
    }
    override fun after(){
        AppServices.createTextClient={HttpsTextChatClient()};AppServices.databaseName="lianxu.db";AppServices.vaultNamespace="model-configs"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ModelVault(context,namespace).let{v->v.list().forEach{v.delete(it.id)}}
        context.deleteDatabase(database)
    }
}
