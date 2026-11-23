package app.lianxu.independent

import android.app.Application

class LianxuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Workers can start the process without creating an activity.
        ProcessDiagnostics.start(this, java.io.File(filesDir,"diagnostics/${AppServices.vaultNamespace}"))
        Diagnostics.startLocalRecording(this, AppServices.vaultNamespace)
        DiagnosticEnvironment.start(this)
    }
}
