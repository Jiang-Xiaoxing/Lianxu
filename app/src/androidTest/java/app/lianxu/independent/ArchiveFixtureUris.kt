package app.lianxu.independent

import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry

/** Resolve this test APK's actual provider, including isolated release-test authorities. */
internal fun archiveFixtureAuthority():String {
    val context=InstrumentationRegistry.getInstrumentation().context
    @Suppress("DEPRECATION")
    return context.packageManager.getProviderInfo(ComponentName(context,ArchiveFixtureProvider::class.java),0).authority
}
