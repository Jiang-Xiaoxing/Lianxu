package app.lianxu.independent

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.net.InetAddress
import javax.net.ssl.*
import kotlin.concurrent.thread

/** Synthetic TLS certificate and key only. Never changes the app trust store or network security policy. */
class LocalTlsMock(private val status:Int=200,private val body:String="{\"data\":[{\"id\":\"synthetic-model\"}]}",private val delay:Long=0):AutoCloseable {
    private val ks=KeyStore.getInstance("PKCS12").apply {InstrumentationRegistry.getInstrumentation().context.assets.open("localhost-test.p12").use {load(it,"synthetic-test-only".toCharArray())}}
    private val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(ks,"synthetic-test-only".toCharArray())}
    private val serverContext=SSLContext.getInstance("TLS").apply {init(km.keyManagers,null,null)}
    private val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(ks)}
    val clientContext:SSLContext=SSLContext.getInstance("TLS").apply {init(null,trust.trustManagers,null)}
    private val server=serverContext.serverSocketFactory.createServerSocket(0,5,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val base="https://localhost:${server.localPort}/v1"
    @Volatile var calls=0;private set
    @Volatile var bearerSeen=false;private set
    @Volatile var apiKeySeen=false;private set
    @Volatile var requestLine="";private set
    private val worker=thread(isDaemon=true,name="synthetic-tls-mock") {
        try {server.accept().use {socket->
            calls++;val reader=socket.getInputStream().bufferedReader();var line=reader.readLine();requestLine=line.orEmpty()
            while(!line.isNullOrEmpty()) {if(line.equals("Authorization: Bearer synthetic-key-only",ignoreCase=true))bearerSeen=true;if(line.equals("api-key: synthetic-key-only",ignoreCase=true))apiKeySeen=true;line=reader.readLine()}
            if(delay>0)Thread.sleep(delay)
            val bytes=body.toByteArray();socket.getOutputStream().apply {write(("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nLocation: https://example.invalid/stolen\r\nConnection: close\r\n\r\n").toByteArray());write(bytes);flush()}
        }} catch(_:Exception) { /* expected when testing timeout and rejected certificates */ }
    }
    fun client(timeout:Int=2000)=HttpsModelClient(HttpsConnectionFactory {url->(url.openConnection() as HttpsURLConnection).apply {sslSocketFactory=clientContext.socketFactory}},timeout)
    override fun close(){server.close();worker.join(2000)}
}

@RunWith(AndroidJUnit4::class)
class ModelConfigTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun screenshot(name:String) {
        ui.waitForIdle();android.os.SystemClock.sleep(500)
        val dir=File(context.getExternalFilesDir(null),"evidence-model-config").apply{mkdirs()}
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun vaultEncryptionEditDefaultDeleteAndReopen() {
        val namespace="test-vault-${System.nanoTime()}";val a=ModelConfig("a","合成A","custom","https://example.invalid/v1","synthetic-model")
        var vault=ModelVault(context,namespace);vault.save(a,"synthetic-key-only");vault.save(a.copy(id="b",name="合成B"),"synthetic-b")
        vault.setDefault("b");assertEquals("b",vault.defaultId());assertTrue(vault.hasKey("a"))
        val bytes=File(context.noBackupFilesDir,"$namespace/a.enc").readBytes()
        assertFalse(String(bytes,Charsets.ISO_8859_1).contains("synthetic-key-only"))
        vault=ModelVault(context,namespace);assertEquals(2,vault.list().size);assertEquals("synthetic-key-only",vault.secret("a"))
        vault.save(a.copy(name="编辑A",modelId="new-model"),vault.secret("a"));assertEquals("编辑A",vault.list().first{it.id=="a"}.name)
        vault.delete("b");assertFalse(vault.hasKey("b"));assertEquals("",vault.defaultId());vault.delete("a");assertFalse(vault.hasKey("a"));assertTrue(vault.list().isEmpty())
        assertFalse(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com"))
    }
    @Test fun purposeBudgetsPersistAndLegacyZeroKeepsSavedCustomOutput() {
        val namespace="test-token-budgets-${System.nanoTime()}"
        val vault=ModelVault(context,namespace)
        val old=ModelConfig("legacy","合成旧配置","custom","https://example.invalid/v1","synthetic",65536,3333)
        val separate=old.copy(id="separate",outputTokens=4096,memoryOutputTokens=16384)
        try {
            vault.save(old,"synthetic-key-only");vault.save(separate,"synthetic-key-only")
            val reopened=ModelVault(context,namespace).list().associateBy{it.id}
            assertEquals(old,reopened.getValue(old.id));assertEquals(3333,reopened.getValue(old.id).memoryOutputLimit())
            assertEquals(separate,reopened.getValue(separate.id));assertEquals(16384,reopened.getValue(separate.id).memoryOutputLimit())
            assertEquals(4096,reopened.getValue(separate.id).outputTokens)
        } finally {vault.list().forEach{vault.delete(it.id)}}
    }
    @Test fun actualHttpsMockSuccessErrorsTimeoutAndRedaction():Unit=runBlocking {
        fun config(base:String)=ModelConfig("mock","合成测试","custom",base,"synthetic-model")
        LocalTlsMock().use {s->val result=s.client().request(config(s.base),"synthetic-key-only",true);assertEquals(listOf("synthetic-model"),(result as ConfigResult.Success).modelIds);assertTrue(s.bearerSeen)}
        for((status,expected) in listOf(401 to ConfigError.UNAUTHORIZED,429 to ConfigError.RATE_LIMIT,500 to ConfigError.HTTP,302 to ConfigError.REDIRECT)) {
            LocalTlsMock(status,"synthetic-key-only raw-error https://secret.invalid").use {s->val result=s.client().request(config(s.base),"synthetic-key-only",false);assertEquals(expected,(result as ConfigResult.Failure).error);assertFalse(result.toString().contains("synthetic-key-only"));assertEquals(1,s.calls)}
        }
        LocalTlsMock(delay=400).use {s->assertEquals(ConfigError.TIMEOUT,(s.client(100).request(config(s.base),"synthetic-key-only",true) as ConfigResult.Failure).error)}
        LocalTlsMock().use {s->assertEquals(ConfigError.TLS,(HttpsModelClient(timeoutMillis=1500).request(config(s.base),"synthetic-key-only",true) as ConfigResult.Failure).error)}
        assertEquals(ConfigError.INVALID,(HttpsModelClient().request(config("http://example.com"),"synthetic-key-only",true) as ConfigResult.Failure).error)
        assertEquals(ConfigError.UNSUPPORTED,(HttpsModelClient().request(config("https://example.invalid").copy(providerId="ark-cn"),"synthetic-key-only",true) as ConfigResult.Failure).error)
    }
    @Test fun newlySupportedProvidersUseTheirDeclaredAuthWithoutChangingOrigin():Unit=runBlocking {
        for(id in listOf("mimo","moonshot","minimax-cn","minimax-intl"))LocalTlsMock().use {s->
            val config=ModelConfig("route","合成目录",id,s.base,"unchanged")
            assertEquals(listOf("synthetic-model"),(s.client().request(config,"synthetic-key-only",true) as ConfigResult.Success).modelIds)
            assertEquals("GET /v1/models HTTP/1.1",s.requestLine)
            assertEquals(id=="mimo",s.apiKeySeen);assertEquals(id!="mimo",s.bearerSeen);assertEquals(1,s.calls)
        }
        LocalTlsMock().use {s->
            s.client().request(ModelConfig("route","合成目录","silicon-cn",s.base,"unchanged"),"synthetic-key-only",true)
            assertEquals("GET /v1/models?sub_type=chat HTTP/1.1",s.requestLine)
        }
    }
    @Test fun untrustedDirectoryIdsAreBoundedAndSecretsNeverBecomeOptions():Unit=runBlocking {
        val body="{\"data\":[{\"id\":\"safe-model\"},{\"id\":\"safe-model\"},{\"id\":\"bad model\"},{\"id\":\"synthetic-key-only\"},{\"id\":\"<script>\"},{\"id\":\"${"a".repeat(201)}\"}]}"
        LocalTlsMock(body=body).use {s->
            assertEquals(listOf("safe-model"),(s.client().request(ModelConfig("safe","合成目录","custom",s.base,"unchanged"),"synthetic-key-only",true) as ConfigResult.Success).modelIds)
        }
        LocalTlsMock(body="{\"data\":["+(0..510).joinToString(","){"{\"id\":\"model-$it\"}"}+"]}").use {s->
            assertEquals(500,(s.client().request(ModelConfig("safe","合成目录","custom",s.base,"unchanged"),"synthetic-key-only",true) as ConfigResult.Success).modelIds.size)
        }
    }
}
