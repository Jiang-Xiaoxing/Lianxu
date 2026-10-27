package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Only for the dedicated synthetic emulator: seed under released v16, verify after install -r v17. */
class ReleaseUpgradeTest {
    @Suppress("DEPRECATION")
    @Test fun syntheticDataAndEncryptedConfigSurviveCoverInstall():Unit=runBlocking {
        val stage=InstrumentationRegistry.getArguments().getString("releaseUpgradeStage").orEmpty()
        assumeTrue("Only run with an explicit release-upgrade stage",stage in setOf("seed16","verify17"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.lianxu.independent",context.packageName)
        assertEquals(if(stage=="seed16")16 else 17,context.packageManager.getPackageInfo(context.packageName,0).versionCode)
        val friend=Friend("release-upgrade-v017","升级验收角色","仅含合成数据","这是覆盖升级验收用的人设。")
        val model=ModelConfig("upgrade-model-v017","升级验收模型","custom","https://upgrade.invalid/v1","synthetic-upgrade-model")
        val namespace="release-upgrade-v017"
        val proof=File(context.filesDir,"release-upgrade-v017-proof.json")
        val encrypted=File(context.noBackupFilesDir,"$namespace/${model.id}.enc")
        fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val store=Store(context)
        try{
            val vault=ModelVault(context,namespace,store)
            if(stage=="seed16"&&!proof.exists()){
                assertTrue(store.friends().none{it.id==friend.id})
                store.add(listOf(friend),demoGreeting=false)
                store.exchange(friend,"升级前保存的合成原话","升级前保存的合成回复")
                store.setPreference("release-upgrade-v017-setting","合成偏好保留")
                vault.save(model,"synthetic-upgrade-key-only");vault.setDefault(model.id)
                proof.writeText(JSONObject().put("messageIds",store.messages().filter{it.friendId==friend.id}.joinToString(","){it.id.toString()}).put("encryptedSHA256",digest(encrypted.readBytes())).toString())
            }
            assertTrue(proof.exists())
            val expected=JSONObject(proof.readText())
            assertEquals(friend,store.friends().single{it.id==friend.id})
            val messages=store.messages().filter{it.friendId==friend.id}
            assertEquals(2,messages.size)
            assertEquals(listOf("升级前保存的合成原话","升级前保存的合成回复"),messages.map{it.text})
            assertEquals(listOf(true,false),messages.map{it.mine})
            assertEquals(expected.getString("messageIds"),messages.joinToString(","){it.id.toString()})
            assertEquals("合成偏好保留",store.preference("release-upgrade-v017-setting",""))
            assertEquals(model,vault.list().single{it.id==model.id})
            assertEquals(model.id,vault.defaultId())
            assertEquals("synthetic-upgrade-key-only",vault.secret(model.id))
            assertEquals(expected.getString("encryptedSHA256"),digest(encrypted.readBytes()))
            assertFalse(String(encrypted.readBytes(),Charsets.ISO_8859_1).contains("synthetic-upgrade-key-only"))
        }finally{store.close()}
    }
}
