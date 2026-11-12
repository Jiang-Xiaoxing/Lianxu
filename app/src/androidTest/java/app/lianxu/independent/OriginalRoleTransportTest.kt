package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production generator and HTTP transport against loopback TLS only. */
class OriginalRoleTransportTest {
    private val config=ModelConfig("synthetic","合成小米协议","mimo","https://api.xiaomimimo.com/v1","mimo-v2.6-flash",16384,4096)
    private val payload=Gson().toJson(mapOf("roles" to (1..3).map {
        mapOf("name" to "合成角色$it","summary" to "合成简介$it","definition" to "合成设定$it","expression" to "自然简短","opening" to "")
    }))
    private fun response()=JSONObject().put("choices",org.json.JSONArray().put(JSONObject()
        .put("finish_reason","stop").put("message",JSONObject().put("content",payload)))).toString()
    private fun generator(client:TextChatClient)=OriginalRoleGenerator(client,{ModelCredential(config,"synthetic-key-only","stamp")},{true})

    @Test fun slowMiMoBatchBeyondOneMinuteCompletesWithOneRequest():Unit=runBlocking {
        TextTlsMock(response=response(),delay=61_000).use { server ->
            val started=System.nanoTime()
            val roles=generator(server.routedProviderClient{}).generate("合成慢响应要求")
            assertTrue((System.nanoTime()-started)/1_000_000>=61_000)
            assertEquals(3,roles.size);assertEquals(1,server.calls.get())
            val body=JSONObject(server.body)
            assertEquals("mimo-v2.6-flash",body.getString("model"))
            assertFalse(body.getBoolean("stream"));assertEquals(4096,body.getInt("max_completion_tokens"))
        }
    }

    @Test fun slowGenerationRemainsCancellableWithoutAcceptingLateResult():Unit=runBlocking {
        TextTlsMock(response=response(),delay=1500).use { server ->
            var accepted=false
            val job=launch { generator(server.routedProviderClient{}).generate("合成取消要求");accepted=true }
            withTimeout(5000){while(server.body.isEmpty())delay(20)}
            withTimeout(1000){job.cancelAndJoin()}
            delay(1700)
            assertFalse(accepted);assertEquals(1,server.calls.get())
        }
    }
}
