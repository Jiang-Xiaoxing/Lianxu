package app.lianxu.independent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VoiceOfferDeviceTest {
    @Test fun accountQualificationDistinguishesEachLocalGate() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val name="voice-offer-${UUID.randomUUID()}"
        val root=File(app.cacheDir,name).apply { mkdirs() }
        val ctx=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File=root
            override fun getSharedPreferences(ignored:String,mode:Int)=app.getSharedPreferences(name,mode)
        }
        val prefs=ctx.getSharedPreferences(name,0)
        val account=BreezeVoiceAccount(ctx)
        try {
            assertEquals(VoiceReason.ROLE_DISABLED,account.eligibility("role").reason)
            account.enable("role",true)
            assertEquals(VoiceReason.MISSING_CREDENTIAL,account.eligibility("role").reason)
            prefs.edit().putString("account","qa").commit()
            val record=File(root,"breezeblue-speech/tts-qa.enc")
            record.parentFile!!.mkdirs();record.createNewFile() // qualification checks existence only
            assertEquals(VoiceReason.SERVICE_NOT_READY,account.eligibility("role").reason)
            prefs.edit().putBoolean("ready",true).commit()
            assertEquals(VoiceReason.NO_VOICE,account.eligibility("role").reason)
            prefs.edit().putString("voices","[{\"id\":\"v\",\"name\":\"test\"}]").commit()
            account.bind("role",SavedVoice("v","test"),"qa")
            assertEquals("v",account.eligibility("role").target?.voice)
            prefs.edit().putString("blocked:qa:role","v").commit()
            assertEquals(VoiceReason.ROLE_VOICE_BLOCKED,account.eligibility("role").reason)
        } finally { app.deleteSharedPreferences(name);root.deleteRecursively() }
    }
}
