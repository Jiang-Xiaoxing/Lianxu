package app.lianxu.independent

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModelVaultCacheTest {
    private val namespace="test-vault"
    private val dir="/tmp/vault-a"
    private val config=ModelConfig("id-1","n","deepseek","https://api.deepseek.com","m")
    private fun snap(path:String=dir,secret:String="secret-one")=ModelVaultCache.Snapshot(path,listOf(config),mapOf(config.id to secret))

    @Before fun setUp(){ModelVaultCache.resetForTest()}
    @After fun tearDown(){ModelVaultCache.resetForTest()}

    @Test fun cacheHitDoesNotDecryptAgain(){
        var loads=0
        val first=ModelVaultCache.getOrLoad(namespace,dir,onMainThread=false){loads++;snap()}
        val second=ModelVaultCache.getOrLoad(namespace,dir,onMainThread=false){loads++;snap(secret="must-not-load")}
        assertEquals(1,loads)
        assertEquals(1,ModelVaultCache.loadCount(namespace))
        assertEquals(first,second)
        assertEquals("secret-one",second.secrets[config.id])
        assertSame(first,ModelVaultCache.peek(namespace,dir))
    }

    @Test fun invalidateAfterSettingsChangeForcesReload(){
        var loads=0
        ModelVaultCache.getOrLoad(namespace,dir,onMainThread=false){loads++;snap()}
        ModelVaultCache.invalidate(namespace)
        assertNull(ModelVaultCache.peek(namespace,dir))
        val reloaded=ModelVaultCache.getOrLoad(namespace,dir,onMainThread=false){loads++;snap(secret="secret-two")}
        assertEquals(2,loads)
        assertEquals("secret-two",reloaded.secrets[config.id])
    }

    @Test fun directoryChangeIsAMiss(){
        ModelVaultCache.getOrLoad(namespace,dir,onMainThread=false){snap()}
        assertNull(ModelVaultCache.peek(namespace,"/tmp/vault-b"))
        var loads=0
        val next=ModelVaultCache.getOrLoad(namespace,"/tmp/vault-b",onMainThread=false){loads++;snap(path="/tmp/vault-b",secret="restored")}
        assertEquals(1,loads)
        assertEquals("restored",next.secrets[config.id])
        assertNull(ModelVaultCache.peek(namespace,dir))
    }

    @Test fun mainThreadMustNotDecryptOnMiss(){
        try {
            ModelVaultCache.getOrLoad(namespace,dir,onMainThread=true){snap()}
            fail("expected main-thread decrypt to be rejected")
        } catch(e:IllegalStateException){
            assertTrue(e.message.orEmpty().contains("main thread"))
        }
        assertEquals(0,ModelVaultCache.loadCount(namespace))
        assertNull(ModelVaultCache.peek(namespace,dir))
    }

    @Test fun mainThreadMayReadAWarmCache(){
        val loaded=ModelVaultCache.getOrLoad(namespace,dir,onMainThread=false){snap()}
        val hit=ModelVaultCache.getOrLoad(namespace,dir,onMainThread=true){error("must not decrypt")}
        assertEquals(loaded,hit)
    }

    @Test fun snapshotToStringHidesSecrets(){
        val text=snap().toString()
        assertFalse(text.contains("secret-one"))
        assertTrue(text.contains("redacted"))
    }
}
