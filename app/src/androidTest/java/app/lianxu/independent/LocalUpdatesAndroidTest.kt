package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

class LocalUpdatesAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Fixture(val name:String,val store:Store,val vault:ModelVault){val backup=BackupRepository(store,vault);val updates=LocalUpdateRepository(store)}
    private val fixtures=mutableListOf<Fixture>()
    private fun fixture():Fixture {val name="updates-${System.nanoTime()}";val store=Store(context,"$name.db");return Fixture(name,store,ModelVault(context,name,store)).also{fixtures+=it}}
    @After fun close(){fixtures.forEach{x->x.vault.list().forEach{x.vault.delete(it.id)};x.store.close();context.deleteDatabase("${x.name}.db");java.io.File(context.noBackupFilesDir,x.name).deleteRecursively()}}
    private fun pack(e:BackupRepository.Export)=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(e.root,e.files,it)}.toByteArray().inputStream())
    @Test fun installedVersionComesFromTheActualPackage() {
        @Suppress("DEPRECATION") val expected=context.packageManager.getPackageInfo(context.packageName,0)
        val actual=InstalledBuild.read(context);assertEquals(context.packageName,actual.packageName);assertEquals(expected.versionName,actual.versionName)
        @Suppress("DEPRECATION") val code=if(android.os.Build.VERSION.SDK_INT>=28)expected.longVersionCode else expected.versionCode.toLong()
        assertEquals(code,actual.versionCode)
    }
    @Test fun receiptPersistsOnlyOnAcknowledgementAndUnknownVersionDoesNotWrite()=runBlocking {
        val f=fixture();assertNotNull(f.updates.pending(17));assertEquals("",f.store.preference(LocalUpdateReceipt.KEY,""))
        assertTrue(runCatching{f.updates.acknowledge(99)}.isFailure);assertEquals("",f.store.preference(LocalUpdateReceipt.KEY,""))
        f.updates.acknowledge(17);val saved=f.store.preference(LocalUpdateReceipt.KEY,"");f.updates.acknowledge(17)
        assertEquals(saved,f.store.preference(LocalUpdateReceipt.KEY,""));assertNull(f.updates.pending(17));assertNotNull(LocalReleaseNotes.forVersion(17))
    }
    @Test fun completeBackupRestoresReceiptWhileAddAndRoleBackupPreserveRecipient()=runBlocking {
        val source=fixture();source.store.add(listOf(Friend("a","合成同伴","合成资料")),false);source.updates.acknowledge(17)
        val all=pack(source.backup.export());val target=fixture();target.updates.acknowledge(16)
        target.backup.commit(target.backup.preview(all,"ADD"));assertEquals(16L,LocalUpdateReceipt.decode(target.store.preference(LocalUpdateReceipt.KEY,"")).lastAcknowledged)
        target.backup.commit(target.backup.preview(all,"REPLACE"),true);assertNull(target.updates.pending(17))
        assertFalse(pack(source.backup.export("a")).root["preferences"].asJsonObject.has(LocalUpdateReceipt.KEY))
    }
}
