package app.lianxu.independent

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run after "adb shell am crash <package>" on an isolated device, supplying the killed main PID. */
class ProcessExitExportDeviceTest {
    @Test fun priorInjectedCrashPresentInBothExports() = runBlocking {
        val pid=InstrumentationRegistry.getArguments().getString("crashed_pid")?.toIntOrNull()
        assumeTrue("Requires a previously injected crash",pid!=null)
        assertNotEquals(Process.myPid(),pid)
        for(detailed in listOf(false,true)) {
            val json=Diagnostics.export(detailed).decodeToString()
            val rows=json.lineSequence().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }
                .filter { it["pid"]?.asInt==pid }.toList()
            val crash=rows.last { it["event"].asString=="process.uncaught" }
            val exit=rows.single { it["event"].asString=="process.exit" && it["trace"]==crash["trace"] }
            assertEquals("JAVA_CRASH",exit["reason"].asString)
            assertEquals("home",crash["page"].asString)
            assertFalse(crash.has("message"));assertFalse(crash.has("stack"))
            assertFalse(json.contains("shell-induced crash"))
            println("export detailed=$detailed previousPid=$pid page=${crash["page"]} type=${crash["exception_type"]} OS=${exit["reason"]}")
        }
    }
}
