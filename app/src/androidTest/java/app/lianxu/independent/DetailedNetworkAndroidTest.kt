package app.lianxu.independent

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DetailedNetworkAndroidTest {
    @get:Rule(order=0) val fixture=MockChatFixture()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun enable():AppState{
        lateinit var vm:AppState
        ui.activityRule.scenario.onActivity{vm=ViewModelProvider(it)[AppState::class.java]}
        ui.waitUntil(20000){vm.ready}
        assertTrue(Diagnostics.detailed)
        return vm
    }
    private fun rows():List<JsonObject> = runBlocking{Diagnostics.export(true)}.toString(Charsets.UTF_8)
        .lineSequence().filter{it.isNotBlank()}.drop(1).map{JsonParser.parseString(it).asJsonObject}.toList()
    private fun name(row:JsonObject)=row["event"].asString
    private fun data(row:JsonObject)=row.getAsJsonObject("data")

    @Test fun backgroundNetworkAndInstalledBuildContinueWithSimpleExport(){
        val vm=enable()
        ui.waitUntil(15000){rows().any{name(it)=="diagnostic.build" && data(it)["apk_sha256"]?.asString?.length==64}}
        ui.waitUntil(10000){rows().any{name(it)=="diagnostic.network"}}
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val events=rows().filter{name(it)=="diagnostic.lifecycle"}
        assertTrue(events.any{data(it)["activity_visibility"].asString=="background"})
        assertEquals("foreground",data(events.last())["activity_visibility"].asString)
        for(row in events){
            assertTrue(row["at"].asLong>0);assertTrue(row["monotonic_ns"].asLong>0)
            assertTrue(data(row)["elapsed_realtime_ns"].asLong>0)
            assertEquals(BuildConfig.VERSION_CODE,data(row)["version_code"].asInt)
        }
        ui.runOnIdle{vm.setDiagnosticDetailed(false)}
        val atOff=rows().count{name(it) in setOf("diagnostic.network","diagnostic.lifecycle","diagnostic.build")}
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue(rows().count{name(it) in setOf("diagnostic.network","diagnostic.lifecycle","diagnostic.build")}>atOff)
    }

    @Test fun recreatedActivityAndLateNetworkCallbacksRespectTheCurrentSession() {
        val vm=enable()
        ui.activityRule.scenario.recreate()
        val cm=ui.activity.getSystemService(android.net.ConnectivityManager::class.java)
        val network=requireNotNull(cm.activeNetwork)
        val caps=requireNotNull(cm.getNetworkCapabilities(network))
        val observer=DiagnosticEnvironment.networkObserver(requireNotNull(Diagnostics.detailSession))
        val trace="synthetic-network-callback"
        val state=DiagnosticTraceState(trace,"fixture",null,"synthetic")
        val observedBlocked=mutableListOf<Boolean?>()
        ui.runOnIdle {
            Diagnostics.onThread(state) {
                observer.onAvailable(network)
                observedBlocked += DiagnosticEnvironment.networkBlockedState()
                observer.onCapabilitiesChanged(network,caps)
                observer.onBlockedStatusChanged(network,true)
                observedBlocked += DiagnosticEnvironment.networkBlockedState()
                // A new default network becoming available must clear previous blocked=true before its callback arrives.
                observer.onAvailable(network)
                observedBlocked += DiagnosticEnvironment.networkBlockedState()
                observer.onBlockedStatusChanged(network,false)
                observedBlocked += DiagnosticEnvironment.networkBlockedState()
                observer.onLost(network)
                observedBlocked += DiagnosticEnvironment.networkBlockedState()
                observer.onCapabilitiesChanged(network,caps)
                vm.setDiagnosticDetailed(false)
                vm.setDiagnosticDetailed(true)
                observer.onAvailable(network)
                observer.onLost(network)
            }
        }
        assertEquals(listOf(null,true,null,false,null),observedBlocked)
        val observed=rows().filter { it["trace"].asString==trace && name(it)=="diagnostic.network" && data(it).has("network_event") }
            .map { data(it)["network_event"].asString }.filter { it!="snapshot" }
        assertEquals(listOf("available","capabilities","blocked_changed","available","blocked_changed","lost","available","lost"),observed)
        val blockedStates=rows().filter { it["trace"].asString==trace && name(it)=="diagnostic.network" && data(it)["network_event"]?.asString=="blocked_changed" }
            .map { data(it)["network_blocked"].asBoolean }
        assertEquals(listOf(true,false),blockedStates)
    }

    @Test fun completedWritesAndPartialReadsMatchSyntheticPeer() {
        enable()
        for (partial in listOf(false,true)) {
            val reply = if (partial) "合成的不完整回复" else """{"choices":[{"finish_reason":"stop","message":{"content":"合成完成"}}]}"""
            TextTlsMock(response=reply, declaredResponseBytes=if(partial) 2000 else null).use { server ->
                val result=runBlocking { LoggedTextChatClient(server.client()).complete(
                    ModelConfig("local","合成","custom",server.base,"synthetic"),"fake-local-key",
                    TextChatRequest(listOf(WireMessage("user","合成上传")))) }
                assertEquals(!partial,result is TextChatResult.Success)
                val events=rows()
                val id=data(events.last { name(it)=="network.request" })["request_id"].asString
                val end=data(events.last { name(it)=="http.transfer_end" && data(it)["request_id"].asString==id })
                assertEquals(server.body.toByteArray().size.toLong(),end["client_write_completed_bytes"].asLong)
                assertEquals(reply.toByteArray().size.toLong(),end["response_bytes"].asLong)
                assertEquals(1,server.calls.get())
            }
        }
    }

    @Test fun verifiedLoopbackPeerClosesDuringUpload(){
        enable()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureFile=File.createTempFile("synthetic-upload",".mp4",context.cacheDir)
        try{
            java.io.RandomAccessFile(fixtureFile,"rw").use{it.setLength(16L*1024*1024)}
            val received=AtomicInteger()
            TextTlsMock(maxBodyBytes=32*1024*1024,requestSink={input,_->
                DataInputStream(input).readFully(ByteArray(32768))
                received.set(32768)
                throw EOFException("synthetic peer closes before consuming remaining bytes")
            }).use{server->
                val request=TextChatRequest(listOf(WireMessage("user","合成上传正文",videos=listOf(WireVideo("video/mp4",fixtureFile,"合成视频")))))
                val result=runBlocking{LoggedTextChatClient(server.client(30000)).complete(
                    ModelConfig("synthetic","合成","custom",server.base,"synthetic-model"),"fake-upload-credential",request)}
                assertTrue(result is TextChatResult.Failure)
                assertEquals(1,server.calls.get());assertEquals(32768,received.get())
                val events=rows()
                val fail=events.single{name(it) in setOf("http.network_failed","http.tls_failed")}
                val d=data(fail);val id=d["request_id"].asString
                val same=events.filter{data(it)?.get("request_id")?.asString==id}
                val planned=data(same.single{name(it)=="http.phase" && data(it).has("planned_body_bytes")})["planned_body_bytes"].asLong
                val written=d["client_write_completed_bytes"].asLong
                assertEquals("write_body",d["transport_phase"].asString)
                assertTrue(written>0 && written<planned)
                assertTrue(d["write_attempted_bytes"].asLong>written)
                assertEquals("none",d["cancel_source"].asString)
                assertEquals(0L,d["response_bytes"].asLong)
                assertFalse(same.any{name(it)=="http.request_sent"})
                assertTrue(d["exception_stack"].asString.isNotBlank())
                val sample="peer_application_read_bytes=${received.get()} client_write_completed_bytes=$written client_write_attempted_bytes=${d["write_attempted_bytes"]} planned_body_bytes=$planned phase=${d["transport_phase"]}"
                File(context.getExternalFilesDir(null),"synthetic-upload-result.txt").writeText(sample)
                val exported=same.joinToString()
                assertTrue(exported.contains("合成上传正文"));assertFalse(exported.contains("fake-upload-credential"))
                assertFalse(exported.contains("AAAA".repeat(16)))
            }
        }finally{fixtureFile.delete()}
    }
}
