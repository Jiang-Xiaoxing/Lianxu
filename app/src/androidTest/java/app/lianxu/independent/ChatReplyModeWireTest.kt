package app.lianxu.independent

import android.app.NotificationManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real loopback TLS POSTs from the ordinary chat binding. All messages, tool calls and pixels are fixtures. */
class ChatReplyModeWireTest {
    private lateinit var server: TextTlsMock
    @get:Rule val fixture = MockChatFixture({ server.client(90_000) }, wrapFinalReply = false)
    private val answer = "合成第一段。\n\n合成第二段。"

    @Test fun streamingPostsTrueAndPersistsSseReply() = exercise(true)
    @Test fun nonStreamingPostsFalseAndReturnsToolResultBeforeSaving() = exercise(false)

    private fun exercise(stream: Boolean) {
        val requests = CopyOnWriteArrayList<JSONObject>()
        val finalRequest = CountDownLatch(1)
        val sendFinal = CountDownLatch(1)
        server = TextTlsMock(maxRequests = if (stream) 1 else 2, contentType = if (stream) "text/event-stream" else "application/json",
            responder = { raw ->
                requests += JSONObject(raw)
                if (!stream && requests.size == 1) {
                    val fn = JSONObject().put("name","agent_memory_read").put("arguments", """{"query":"合成"}""")
                    val call = JSONObject().put("id","test-read").put("type","function").put("function",fn)
                    reply(JSONObject().put("content",JSONObject.NULL).put("tool_calls",JSONArray().put(call)), "tool_calls")
                } else {
                    finalRequest.countDown()
                    check(sendFinal.await(60, TimeUnit.SECONDS))
                    if (stream) answer.split("\n\n").mapIndexed { i, text ->
                        val choice = JSONObject().put("delta",JSONObject().put("content",if(i==0) text+"\n\n" else text))
                        if (i == 1) choice.put("finish_reason", "stop")
                        "data: "+JSONObject().put("choices",JSONArray().put(choice))+"\n\n"
                    }.joinToString("")+"data: [DONE]\n\n"
                    else reply(JSONObject().put("content",answer),"stop")
                }
            })
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val notifications = app.getSystemService(NotificationManager::class.java)
        val vault = ModelVault(app, fixture.namespace)
        val cfg = vault.list().single().copy(baseUrl=server.base,streamReplies=stream)
        vault.save(cfg,"synthetic-key-only")
        val image = OriginalImageFixtures.paddedJpeg(144_000)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppState
                scenario.onActivity { vm=ViewModelProvider(it)[AppState::class.java] }
                ChatImages.prepare(image.inputStream()).use { picture ->
                    runBlocking { withContext(Dispatchers.Main) {
                        withTimeout(60_000){while(!vm.ready) delay(100)}
                        vm.open("chat","seed-lin")
                        assertTrue(vm.sendImage("合成图片提问",picture))
                    } }
                    assertTrue("Expected final HTTP request; received ${requests.size}",finalRequest.await(90,TimeUnit.SECONDS))
                }
                scenario.onActivity {
                    assertTrue(vm.chatPreview?.text.isNullOrEmpty())
                    assertTrue(vm.messages.none { it.text == answer })
                    if (!stream) vm.open("home")
                }
                sendFinal.countDown()
                runBlocking { withContext(Dispatchers.Main) {
                    withTimeout(90_000){while(vm.turns.singleOrNull()?.state==null || vm.isReplying("seed-lin")) delay(100)}
                    val turn=vm.turns.single()
                    assertEquals(turn.failureDetail,TurnState.COMPLETED,turn.state)
                    assertEquals(answer,vm.messages.single{it.id==turn.assistantMessageId}.text)
                    if (!stream) {
                        val id = requireNotNull(turn.assistantMessageId).toInt()
                        try {
                            withTimeout(10_000) {
                                while (notifications.activeNotifications.none { it.tag == ChatReplyNotifications.TAG && it.id == id }) delay(100)
                            }
                            assertTrue(notifications.activeNotifications.none { it.notification.channelId == ChatRequestService.CHANNEL_ID })
                        } finally { notifications.cancel(ChatReplyNotifications.TAG, id) }
                    }
                } }
                assertEquals(if(stream) 1 else 2, requests.size)
                for (request in requests) {
                    assertEquals(stream,request.getBoolean("stream"))
                    val messages = objects(request.getJSONArray("messages"))
                    val media = messages.flatMap { it.optJSONArray("content")?.let(::objects).orEmpty() }
                        .single { it.getString("type")=="image_url" }.getJSONObject("image_url").getString("url")
                    assertArrayEquals(image,Base64.getDecoder().decode(media.substringAfter(',')))
                }
                if (!stream) {
                    assertTrue(objects(requests.first().getJSONArray("tools")).any { it.getJSONObject("function").getString("name")=="agent_memory_read" })
                    val tool=objects(requests.last().getJSONArray("messages")).single{it.getString("role")=="tool"}
                    assertEquals("test-read",tool.getString("tool_call_id"))
                    assertEquals("agent_memory_read",tool.getString("name"))
                    assertTrue(JSONObject(tool.getString("content")).getBoolean("success"))
                }
            }
        } finally {
            sendFinal.countDown()
            server.close()
        }
    }

    private fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
    private fun reply(message: JSONObject, finish: String) = JSONObject().put("choices",
        JSONArray().put(JSONObject().put("message",message).put("finish_reason",finish))).toString()
}
