package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class StableTestUpgradeTest {
    @Test fun syntheticRecordsSurvivePackageReplacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mode = InstrumentationRegistry.getArguments().getString("upgradeProbe")
        assumeTrue(mode in listOf("seed", "verify"))
        check(context.packageName == "app.lianxu.independent.testing")
        check(android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        Store(context).use { store ->
            val vault = ModelVault(context, AppServices.vaultNamespace, store)
            val role = Friend("upgrade-probe", "合成升级角色", "覆盖安装验证")
            val config = ModelConfig("upgrade-model","合成配置","custom","https://example.invalid/v1","offline",32000,1024)
            if (mode == "seed") {
                check(store.friends().isEmpty() && vault.list().isEmpty())
                store.add(listOf(role), false)
                store.exchange(role, "合成升级前消息", "合成升级前回复")
                store.savePreference("diagnostic-detailed", "0")
                vault.save(config, "synthetic-upgrade-value")
                vault.setPurpose(ModelPurpose.CHAT, config.id)
            }
            assertEquals(role.name, store.friends().single { it.id == role.id }.name)
            assertTrue(store.messages().any { it.friendId == role.id && it.text == "合成升级前回复" })
            assertEquals(config.id, vault.purposeId(ModelPurpose.CHAT))
            assertEquals(config.baseUrl, vault.list().single { it.id == config.id }.baseUrl)
            assertTrue(vault.secret(config.id) == "synthetic-upgrade-value")
        }
    }
}
