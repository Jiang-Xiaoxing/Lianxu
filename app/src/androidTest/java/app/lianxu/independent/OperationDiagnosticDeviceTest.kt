package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OperationDiagnosticDeviceTest {
    @Test fun preferenceFailureAndRestorePreflightReachBothExports() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString()
        val dbName = "diagnostic-test-$tag"
        val store = Store(app, dbName)
        val repo = BackupRepository(store, ModelVault(app, dbName, store)) { 0L }
        try {
            withContext(Diagnostics.context("test", tag)) {
                ToolboxPreferences.saveOrder(store, ToolboxPreferences.defaultOrder)
                store.writableDatabase.execSQL("CREATE TEMP TRIGGER ignore_pref BEFORE INSERT ON preferences BEGIN SELECT RAISE(IGNORE); END")
                assertEquals(-1L, ToolboxPreferences.saveOrder(store, ToolboxPreferences.defaultOrder.reversed()))
                assertEquals(ToolboxPreferences.defaultOrder, ToolboxPreferences.loadOrder(store))
                store.writableDatabase.execSQL("DROP TRIGGER ignore_pref")
                store.add(listOf(Friend("fixture","fixture","fixture")),false)
                repo.export("fixture").use { exported ->
                    val bytes = java.io.ByteArrayOutputStream()
                    BackupArchive.write(exported.root,exported.files,bytes)
                    BackupArchive.read(bytes.toByteArray().inputStream(),app.cacheDir).use { pack ->
                        val plan = repo.preview(pack, "ADD")
                        assertTrue(runCatching { repo.commit(plan) }.exceptionOrNull() is IllegalArgumentException)
                        assertEquals(1,store.friends().size)
                    }
                }
            }
            for (detailed in listOf(false,true)) {
                val events = Diagnostics.export(detailed).decodeToString().lineSequence().filter { it.isNotBlank() }
                    .map { JsonParser.parseString(it).asJsonObject }.filter { it["trace"]?.asString == tag }
                    .filter { it["event"]?.asString in LocalStep.events }.map { it["data"].asJsonObject }
                    .map { it["stage"].asString + "/" + it["result"].asString }.toList()
                assertEquals(listOf("TOOLBOX_ORDER/COMPLETED","TOOLBOX_ORDER/WRITE_REJECTED",
                    "SNAPSHOT/COMPLETED","PREVIEW_VALIDATE/COMPLETED","RECHECK/COMPLETED","CHECK_SPACE/SPACE_CHECK_REJECTED"), events)
            }
        } finally { store.close(); app.deleteDatabase(dbName) }
    }
}
