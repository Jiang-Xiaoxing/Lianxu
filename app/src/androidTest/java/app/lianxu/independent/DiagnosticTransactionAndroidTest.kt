package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Isolated synthetic database and journal only; never reads a real vault or provider. */
class DiagnosticTransactionAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var journal:DiagnosticJournal;private lateinit var directory:File;private lateinit var name:String
    @Before fun setup()=runBlocking{
        name="diagnostic-db-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store)
        store.add(listOf(Friend("a","合成角色","成年测试角色")),false)
        directory=File(context.filesDir,"diagnostic-test-${System.nanoTime()}");journal=DiagnosticJournal(directory,"test");Diagnostics.installForTest(journal)
    }
    @After fun cleanup(){Diagnostics.installForTest(null);journal.close();store.close();context.deleteDatabase(name);require(directory.name.startsWith("diagnostic-test-"));directory.deleteRecursively()}
    private fun events()=journal.export(true).toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.drop(1).map{JsonParser.parseString(it).asJsonObject}.toList()
    @Test fun independentDatabaseOperationKeepsOneTrace()=runBlocking{
        repo.manualSave("a",null,"合成记忆正文",MemoryCategory.STATE_FACT,true,true,MemoryAbout.USER)
        val rows=events();assertTrue(rows.any{it.get("event").asString=="memory.write.staged"})
        val trace=rows.first{it.get("event").asString=="memory.write.staged"}.get("trace").asString
        assertTrue(rows.any{it.get("trace").asString==trace&&it.get("event").asString=="db.transaction.begin"})
        assertTrue(rows.any{it.get("trace").asString==trace&&it.get("event").asString=="db.transaction.commit"})
    }
    @Test fun failedTransactionLogsRollbackAndNeverPretendsWriteCommitted()=runBlocking{
        val fake="fake-db-stack-credential";Diagnostics.registerSecret(fake)
        withContext(Diagnostics.context("test","rollback-trace")){
            assertTrue(runCatching{repo.tx{db->repo.saveMemory(db,StoredMemory("m","a",MemoryCategory.STATE_FACT,"合成事务正文",manual=true));error("synthetic error $fake")}}.isFailure)
        }
        assertTrue(repo.memories("a").isEmpty())
        val rows=events().filter{it.get("trace").asString=="rollback-trace"}
        assertTrue(rows.any{it.get("event").asString=="memory.write.staged"});assertTrue(rows.any{it.get("event").asString=="db.transaction.rollback"})
        assertFalse(rows.any{it.get("event").asString=="db.transaction.commit"});assertFalse(rows.joinToString().contains(fake))
    }
}
