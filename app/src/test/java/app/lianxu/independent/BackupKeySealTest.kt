package app.lianxu.independent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

/** 备份带密钥时用密码加密：导出设密码，导入输同一密码，输错说清楚。 */
class BackupKeySealTest {
    private val secret="sk-synthetic-backup-key-0001"
    private fun root(keys:Boolean)=JsonObject().apply{
        addProperty("scope","all");add("roles",JsonArray());add("preferences",JsonObject());add("selections",JsonObject());addProperty("includesKeys",keys)
        add("models",JsonArray().apply{add(JsonObject().apply{add("config",JsonObject().apply{addProperty("id","chat-1");addProperty("name","合成")});if(keys)addProperty("key",secret)})
            add(JsonObject().apply{add("config",JsonObject().apply{addProperty("id","empty-2")});if(keys)addProperty("key","")})})
    }
    @Test fun sealedBackupNeverHoldsThePlainKeyAndOpensWithTheSamePassword(){
        val sealed=BackupKeySeal.seal(root(true),"correct horse".toCharArray(),iterations=100_000)
        assertFalse(sealed.toString().contains(secret))
        assertTrue(BackupKeySeal.sealed(sealed))
        assertTrue(sealed["models"].asJsonArray.all{!it.asJsonObject.has("key")})
        val opened=BackupKeySeal.open(sealed,"correct horse".toCharArray())
        assertEquals(root(true),opened)
    }
    @Test fun wrongPasswordSaysSo(){
        val sealed=BackupKeySeal.seal(root(true),"correct horse".toCharArray(),iterations=100_000)
        try{BackupKeySeal.open(sealed,"wrong horse".toCharArray());fail()}catch(e:BackupKeySeal.WrongPassword){assertEquals("备份密码不对，请输入导出这份备份时设的密码。",e.message)}
    }
    @Test fun saltMakesEveryExportDifferent(){
        val a=BackupKeySeal.seal(root(true),"correct horse".toCharArray(),iterations=100_000)
        val b=BackupKeySeal.seal(root(true),"correct horse".toCharArray(),iterations=100_000)
        assertNotEquals(a[BackupKeySeal.FIELD].asJsonObject["salt"],b[BackupKeySeal.FIELD].asJsonObject["salt"])
        assertNotEquals(a[BackupKeySeal.FIELD].asJsonObject["data"],b[BackupKeySeal.FIELD].asJsonObject["data"])
    }
    @Test fun plainBackupsNeedNoPasswordAndOldPlainKeysStillRead(){
        assertEquals(root(false),BackupKeySeal.open(root(false),CharArray(0)))
        val old=root(true);assertEquals(old,BackupKeySeal.open(old,CharArray(0)))
    }
    @Test fun passwordIsSetTwiceAndLongEnough(){
        assertEquals("密码至少 6 位",BackupKeySeal.passwordProblem("12345".toCharArray(),"12345".toCharArray()))
        assertEquals("两次输入的密码不一样",BackupKeySeal.passwordProblem("123456".toCharArray(),"123457".toCharArray()))
        assertNull(BackupKeySeal.passwordProblem("123456".toCharArray(),"123456".toCharArray()))
    }
    @Test fun archiveAcceptsTheSealOnlyOnAKeyBackup(){
        val sealed=BackupKeySeal.seal(root(true),"correct horse".toCharArray(),iterations=100_000)
        val out=java.io.ByteArrayOutputStream();BackupArchive.write(sealed,emptyMap(),out)
        val back=BackupArchive.read(java.io.ByteArrayInputStream(out.toByteArray()))
        assertTrue(back.includesKeys&&BackupKeySeal.sealed(back.root))
        val bogus=root(false).apply{add(BackupKeySeal.FIELD,JsonObject())}
        try{BackupArchive.write(bogus,emptyMap(),java.io.ByteArrayOutputStream());fail()}catch(e:IllegalArgumentException){assertEquals("备份密钥锁无效",e.message)}
    }
}
