package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Explicit 1.0.2 -> 1.0.3 cover-install fixture; never opens the application's real/default database. */
class Release103UpgradeAndroidTest {
    @Test fun syntheticDataAndThreePurposeKeysSurviveCoverInstall():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments();val stage=args.getString("release103Stage").orEmpty()
        assumeTrue("Explicit synthetic upgrade stage required",stage in setOf("seed61","verify62","cleanup"))
        val namespace=args.getString("release103Namespace").orEmpty()
        require(namespace.matches(Regex("release103-upgrade-[a-f0-9-]{36}")))
        val context=instrumentation.targetContext;assertEquals("app.lianxu.independent",context.packageName)
        val installed=InstalledBuild.read(context)
        if(stage!="cleanup")assertEquals(if(stage=="seed61")61L else 62L,installed.versionCode)
        val folder=File(context.noBackupFilesDir,namespace);val proof=File(context.filesDir,"$namespace-proof.json")
        val store=Store(context,"$namespace.db")
        try{
            val vault=ModelVault(context,namespace,store)
            if(stage=="cleanup"){
                vault.list().forEach{vault.delete(it.id)};return@runBlocking
            }
            val friend=Friend("upgrade-103-role","合成升级角色","只供升级验证","升级前的合成设定")
            val prompts=RolePrompts(characterDefinition="升级前的合成角色设定",expression="短句",initialRelationship="合成旧识")
            val purposes=listOf(ModelPurpose.CHAT,ModelPurpose.MEMORY,ModelPurpose.AUX_VISION)
            val configs=purposes.associateWith{p->ModelConfig("upgrade-${p.key}","合成${p.title}","custom","https://example.invalid/v1","synthetic-upgrade-model",64000,2048)}
            fun key(p:ModelPurpose)="synthetic-upgrade-${p.key}-not-a-real-credential"
            fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            fun encryptedFiles()=folder.listFiles().orEmpty().filter{it.isFile}.associate{it.name to digest(it.readBytes())}
            if(stage=="seed61"){
                assertFalse(proof.exists());assertTrue(store.friends().isEmpty())
                store.addConfigured(friend,prompts);store.exchange(friend,"升级前合成原话","升级前合成回复")
                store.savePreference("release103-synthetic-preference","升级设置保留")
                purposes.forEach{p->vault.save(configs.getValue(p),key(p));vault.setPurpose(p,configs.getValue(p).id)}
                proof.writeText(JSONObject().put("files",JSONObject(encryptedFiles())).put("messageIds",store.messages().map{it.id}.joinToString(",")).toString())
            }
            assertTrue(proof.exists());val expected=JSONObject(proof.readText())
            assertEquals(friend,store.friends().single());assertEquals(prompts,RolePromptStore.decode(store.preference(RolePromptStore.key(friend.id),"")))
            assertEquals(listOf("升级前合成原话","升级前合成回复"),store.messages().map{it.text})
            assertEquals(expected.getString("messageIds"),store.messages().map{it.id}.joinToString(","))
            assertEquals("升级设置保留",store.preference("release103-synthetic-preference",""))
            purposes.forEach{p->assertEquals(configs.getValue(p).id,vault.purposeId(p));assertEquals(configs.getValue(p),vault.list().single{it.id==configs.getValue(p).id});assertTrue("Synthetic credential must survive",vault.secret(configs.getValue(p).id)==key(p))}
            val expectedFiles=expected.getJSONObject("files");assertEquals(expectedFiles.keys().asSequence().toSet(),encryptedFiles().keys)
            encryptedFiles().forEach{(name,hash)->assertEquals(expectedFiles.getString(name),hash)}
        }finally{
            store.close()
            if(stage=="cleanup"){context.deleteDatabase("$namespace.db");folder.deleteRecursively();proof.delete()}
        }
    }
}
