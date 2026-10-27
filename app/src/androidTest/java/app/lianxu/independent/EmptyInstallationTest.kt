package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated databases only; never opens the user's application database. */
class EmptyInstallationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun newInstallationAndReopenStayEmpty()=runBlocking {
        val name="empty-install-${System.nanoTime()}.db"
        try {
            Store(context,name).use{assertTrue(it.friends().isEmpty());assertTrue(it.messages().isEmpty())}
            Store(context,name).use{assertTrue(it.friends().isEmpty());assertTrue(it.messages().isEmpty())}
        }finally{context.deleteDatabase(name)}
    }
    @Test fun existingSameNamedRolesAreNeverAutomaticallyRemoved()=runBlocking {
        val name="retain-existing-${System.nanoTime()}.db"
        val friends=listOf(Friend("seed-lin","林间","已有合成资料"),Friend("seed-an","时安","已有合成资料"))
        try {
            Store(context,name).use{it.add(friends,false);it.exchange(friends.first(),"合成旧聊天","保留原数据")}
            Store(context,name).use{assertEquals(friends,it.friends());assertEquals(2,it.messages().size)}
        }finally{context.deleteDatabase(name)}
    }
}
