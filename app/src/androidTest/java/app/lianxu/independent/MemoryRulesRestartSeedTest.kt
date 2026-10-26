package app.lianxu.independent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class MemoryRulesRestartSeedTest{
    @Test fun persistGlobalDefaultsForExternalProcessRestart()=runBlocking{
        val s=Store(InstrumentationRegistry.getInstrumentation().targetContext,"lianxu.db");val r=ProcessingRepository(s)
        r.saveMemoryRules(MemoryRules());assertEquals(MemoryRules(40,20,15),r.memoryRules().first);assertTrue(r.memoryRules().second>0);s.close()
    }
}
