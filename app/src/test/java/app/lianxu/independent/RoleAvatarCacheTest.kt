package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class RoleAvatarCacheTest {
    @Test fun secondReadUsesCacheUntilInvalidated(){
        val cache=RoleAvatarCache()
        val first=byteArrayOf(1,2,3)
        cache.put("role-a",first)
        assertArrayEquals(first,cache.cached("role-a"))
        assertFalse(cache.knownMissing("role-a"))
        cache.put("role-b",null)
        assertTrue(cache.knownMissing("role-b"))
        assertNull(cache.cached("role-b"))
        assertEquals(setOf("role-a"),cache.snapshot().keys)
        cache.invalidate("role-a")
        assertNull(cache.cached("role-a"))
        assertFalse(cache.knownMissing("role-a"))
        cache.put("role-a",byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9),cache.cached("role-a"))
    }

    @Test fun retainDropsDeletedRolesAndResetClearsAll(){
        val cache=RoleAvatarCache()
        cache.put("keep",byteArrayOf(1))
        cache.put("gone",byteArrayOf(2))
        cache.put("missing",null)
        cache.retain(setOf("keep","missing"))
        assertNotNull(cache.cached("keep"))
        assertNull(cache.cached("gone"))
        assertTrue(cache.knownMissing("missing"))
        cache.reset()
        assertTrue(cache.snapshot().isEmpty())
        assertFalse(cache.knownMissing("missing"))
    }
}
