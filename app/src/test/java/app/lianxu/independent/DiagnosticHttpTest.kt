package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class DiagnosticHttpTest {
    private class Response(private val body:InputStream):HttpsURLConnection(URL("https://example.invalid")){
        override fun getErrorStream()=body
        override fun connect(){}
        override fun disconnect(){}
        override fun usingProxy()=false
        override fun getCipherSuite()="synthetic"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
    }
    private fun checkLog(body:InputStream,deadline:Long,key:String,assertion:(String)->Unit){
        val dir=Files.createTempDirectory("diagnostic-test-").toFile();val j=DiagnosticJournal(dir,"test");Diagnostics.installForTest(j)
        try{DiagnosticHttp.errorBody(Response(body),deadline,key,"http-trace");assertion(j.export(true).toString(Charsets.UTF_8))}
        finally{Diagnostics.installForTest(null);j.close();require(dir.name.startsWith("diagnostic-test-"));dir.deleteRecursively()}
    }
    @Test fun completedErrorBodyIsUsefulAndRedacted(){
        val key="fake-http-credential";val body="synthetic rejection: $key; token=unregistered-token\nCookie: fake-cookie"
        checkLog(ByteArrayInputStream(body.toByteArray()),System.nanoTime()+1_000_000_000,key){text->
            assertTrue(text.contains("synthetic rejection"));assertTrue(text.contains("http-trace"));assertFalse(text.contains(key));assertFalse(text.contains("unregistered-token"));assertFalse(text.contains("fake-cookie"))
        }
    }
    @Test fun cappedBodyOmitsEverythingRatherThanLeakingCutCredentialPrefix(){
        val key="custom-cutoff-credential";val body="x".repeat(16380)+key
        checkLog(ByteArrayInputStream(body.toByteArray()),System.nanoTime()+1_000_000_000,key){text->assertTrue(text.contains("limited"));assertFalse(text.contains("custom"));assertFalse(text.contains("x".repeat(100)))}
    }
    @Test fun expiredBudgetMarksBodyIncompleteAndDoesNotReadStream(){
        var reads=0;val input=object:InputStream(){override fun read():Int{reads++;return 'x'.code}}
        checkLog(input,System.nanoTime()-1,"fake-credential"){text->assertTrue(text.contains("正文已省略"));assertTrue(text.contains("\"limited\":true"))}
        assertEquals(0,reads)
    }
}
