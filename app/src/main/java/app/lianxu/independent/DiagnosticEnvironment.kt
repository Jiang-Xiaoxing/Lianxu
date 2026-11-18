package app.lianxu.independent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Detailed-mode observations of the app's activity and the system default network. */
internal object DiagnosticEnvironment {
    private val main=Handler(Looper.getMainLooper())
    private val disk=Executors.newSingleThreadExecutor()
    private val activities=WeakHashMap<MainActivity,LifecycleEventObserver?>()
    private var current:Any?=null
    private var registration:Pair<ConnectivityManager,ConnectivityManager.NetworkCallback>?=null
    @Volatile private var fields=emptyMap<String,Any?>()
    @Volatile private var apkHash:String?=null

    fun start(app:Context){
        Diagnostics.modeChanged={if(Looper.myLooper()==Looper.getMainLooper())refresh(app) else main.post{refresh(app)}}
        refresh(app)
    }
    fun attach(activity:MainActivity){
        activities[activity]=null
        refresh(activity.applicationContext)
    }
    fun detach(activity:MainActivity){
        activities.remove(activity)?.let{activity.lifecycle.removeObserver(it)}
        publishVisibility()
    }
    private fun refresh(app:Context){
        val session=Diagnostics.detailSession
        if(current!==session){
            current=session
            Diagnostics.environment=null
            registration?.let{(manager,callback)->runCatching{manager.unregisterNetworkCallback(callback)}}
            registration=null
            activities.forEach{(activity,observer)->observer?.let{activity.lifecycle.removeObserver(it)}}
            activities.keys.toList().forEach{activities[it]=null}
            fields=emptyMap()
            if(session!=null){
                val build=runCatching{InstalledBuild.read(app)}.getOrNull()
                fields=mapOf("package_name" to app.packageName,"version_name" to build?.versionName,"version_code" to build?.versionCode,
                    "build_type" to BuildConfig.BUILD_TYPE,"android_api" to Build.VERSION.SDK_INT,"apk_sha256" to apkHash,
                    "default_network" to "awaiting_callback")
                Diagnostics.environment={if(Diagnostics.detailSession===session)fields+("elapsed_realtime_ns" to SystemClock.elapsedRealtimeNanos()) else emptyMap()}
                startNetwork(app,session)
                disk.execute{
                    if(Diagnostics.detailSession!==session)return@execute
                    val hash=apkHash?:runCatching{
                        val digest=MessageDigest.getInstance("SHA-256")
                        File(app.applicationInfo.sourceDir).inputStream().use{input->
                            val buffer=ByteArray(65536)
                            while(true){
                                if(Diagnostics.detailSession!==session)return@execute
                                val n=input.read(buffer);if(n<0)break
                                digest.update(buffer,0,n)
                            }
                        }
                        digest.digest().joinToString(""){"%02x".format(it)}
                    }.getOrNull()
                    main.post{if(current===session&&Diagnostics.detailSession===session){
                        apkHash=hash;fields=fields+("apk_sha256" to hash)
                        Diagnostics.detail(session,"diagnostic.build"){mapOf("fingerprint_scope" to "installed_base_apk","hash_available" to (hash!=null))}
                    }}
                }
            }
        }
        if(session!=null){
            activities.keys.toList().forEach{activity->
                if(activities[activity]==null){
                    val observer=LifecycleEventObserver{_,event->
                        if(event==Lifecycle.Event.ON_START||event==Lifecycle.Event.ON_STOP)publishVisibility()
                    }
                    activities[activity]=observer
                    activity.lifecycle.addObserver(observer)
                }
            }
            publishVisibility()
        }
    }
    private fun publishVisibility(){
        val session=current?:return
        val visibility=if(activities.keys.any{it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)})"foreground" else "background"
        if(fields["activity_visibility"]!=visibility){
            fields=fields+("activity_visibility" to visibility)
            Diagnostics.detail(session,"diagnostic.lifecycle")
        }
    }
    internal fun networkObserver(session:Any):ConnectivityManager.NetworkCallback {
        var active:Network?=null
        return object:ConnectivityManager.NetworkCallback(){
            private fun update(event:String,value:String){
                if(current!==session||Diagnostics.detailSession!==session)return
                fields=fields+("default_network" to value)
                Diagnostics.detail(session,"diagnostic.network"){mapOf("network_event" to event)}
            }
            override fun onAvailable(network:Network){active=network;update("available","available")}
            override fun onCapabilitiesChanged(network:Network,c:NetworkCapabilities){
                if(network!=active)return
                val transports=listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi",NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",NetworkCapabilities.TRANSPORT_VPN to "vpn")
                    .filter{c.hasTransport(it.first)}.joinToString("+"){it.second}.ifEmpty{"other"}
                update("capabilities","$transports/validated=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}/metered=${!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}")
            }
            override fun onLost(network:Network){if(active==network){active=null;update("lost","none")}}
        }
    }
    private fun startNetwork(app:Context,session:Any){
        val manager=app.getSystemService(ConnectivityManager::class.java)?:return
        val callback=networkObserver(session)
        runCatching{
            manager.registerDefaultNetworkCallback(callback,main)
            registration=manager to callback
            if(manager.activeNetwork==null){fields=fields+("default_network" to "none");Diagnostics.detail(session,"diagnostic.network"){mapOf("network_event" to "snapshot")}}
        }.onFailure{
            fields=fields+("default_network" to "unavailable")
            Diagnostics.detail(session,"diagnostic.network"){mapOf("error_type" to it.javaClass.name)}
        }
    }
}
