package app.lianxu.independent

import android.app.NotificationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Two real system notifications and service lifecycle, no transport or model. */
class ChatRequestServiceAndroidTest {
    @Test fun stoppingOneRequestKeepsTheOtherThenTimeoutClearsIt(){
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use{runBlocking{
            val manager=app.getSystemService(NotificationManager::class.java)
            withContext(Dispatchers.Main.immediate){
                val reasons=mutableListOf<String>()
                fun request():ChatRequestOwner.Attempt {
                    lateinit var attempt:ChatRequestOwner.Attempt
                    attempt=ChatRequestOwner.begin(app){reason->reasons+=reason;ChatRequestOwner.release(attempt)}
                    return attempt
                }
                val a=request();val b=request()
                try{
                    withTimeout(8000){assertTrue(a.started.await());assertTrue(b.started.await())}
                    fun notifications()=manager.activeNotifications.filter{it.id==a.notificationId||it.id==b.notificationId}
                    withTimeout(8000){while(notifications().size!=2)delay(30)}
                    notifications().single{it.id==a.notificationId}.notification.actions.single().actionIntent.send()
                    withTimeout(8000){while(notifications().map{it.id}!=listOf(b.notificationId))delay(30)}
                    assertEquals(setOf(b.id),ChatRequestOwner.active.keys)
                    requireNotNull(ChatRequestOwner.service).onTimeout(1,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    withTimeout(8000){while(notifications().isNotEmpty()||ChatRequestOwner.service!=null)delay(30)}
                    assertTrue(ChatRequestOwner.active.isEmpty());assertEquals(listOf("notification_stop","service_timeout"),reasons)
                }finally{ChatRequestOwner.release(a);ChatRequestOwner.release(b)}
            }
        }}
    }
}
