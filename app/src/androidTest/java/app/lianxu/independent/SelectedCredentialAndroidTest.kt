package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** All records and keys are synthetic; proves a broken unselected record is never decrypted. */
class SelectedCredentialAndroidTest {
    @Test fun readsOnlyTheSelectedRecordAndPreservesConfigurationFiles(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val namespace="selected-only-${System.nanoTime()}";val vault=ModelVault(context,namespace)
        try{
            val config=ModelConfig("selected","Synthetic selected","custom","https://example.invalid/v1","synthetic-selected",64000,2048)
            vault.save(config,"synthetic-selected-key");vault.save(config.copy(id="unselected",modelId="synthetic-other"),"synthetic-other-key");vault.setPurpose(ModelPurpose.CHAT,config.id)
            val folder=File(context.noBackupFilesDir,namespace);val other=File(folder,"unselected.enc");other.writeBytes(byteArrayOf(1,2,3))
            val before=folder.listFiles()!!.filter{it.isFile}.associate{it.name to it.readBytes().toList()}
            val cachedBefore=ModelVaultCache.peek(namespace,folder.canonicalPath);val loadsBefore=ModelVaultCache.loadCount(namespace)
            val credential=requireNotNull(SelectedModelTestAccess.credential(vault,ModelPurpose.CHAT))
            assertEquals(config,credential.config);assertEquals("synthetic-selected-key",credential.key);assertEquals(vault.selectionStamp(ModelPurpose.CHAT),credential.stamp)
            assertNull(SelectedModelTestAccess.credential(vault,ModelPurpose.EMBEDDING));assertFalse(credential.toString().contains("synthetic-selected-key"))
            assertSame(cachedBefore,ModelVaultCache.peek(namespace,folder.canonicalPath));assertEquals(loadsBefore,ModelVaultCache.loadCount(namespace))
            assertEquals(before,folder.listFiles()!!.filter{it.isFile}.associate{it.name to it.readBytes().toList()})
        }finally{vault.delete("selected");vault.delete("unselected")}
    }
}
