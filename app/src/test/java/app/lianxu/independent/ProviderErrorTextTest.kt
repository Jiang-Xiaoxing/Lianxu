package app.lianxu.independent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** 报错原样显示：服务商回了什么就给人看什么（遮掉密钥），只有真连不上服务商才说网络连不上，超时和其他错误直接显示原始报错。 */
class ProviderErrorTextTest {
    private val key="sk-synthetic-secret-123456789"
    private val config=ModelConfig("fake","合成","custom","https://example.invalid/v1","fake-model",16000,2048)
    private val request=TextChatRequest(listOf(WireMessage("user","你好")),256)

    private class Fake(url:URL,val status:Int?,val body:String="",val failOnBody:Boolean=false,val failConnect:IOException?=null):HttpsURLConnection(url){
        override fun getCipherSuite()=""
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun connect(){}
        override fun disconnect(){}
        override fun usingProxy()=false
        override fun getOutputStream():OutputStream{failConnect?.let{throw it};return ByteArrayOutputStream()}
        override fun getResponseCode():Int=status?:throw IOException("no status")
        override fun getResponseMessage()=if(status==402)"Payment Required" else null
        override fun getContentType()="application/json"
        override fun getErrorStream():InputStream=ByteArrayInputStream(body.toByteArray())
        override fun getInputStream():InputStream=if(failOnBody)object:InputStream(){override fun read():Int=throw IOException("Connection reset")} else ByteArrayInputStream(body.toByteArray())
    }
    private fun run(open:(URL)->HttpsURLConnection)=runBlocking{HttpsTextChatClient(HttpsConnectionFactory{open(it)}).complete(config,key,request)} as TextChatResult.Failure

    @Test fun httpErrorShowsStatusAndBodyVerbatimWithKeyMasked(){
        val body="""{"error":{"message":"Insufficient balance for key $key","type":"billing_error","code":"insufficient_quota"}}"""
        val result=run{Fake(it,402,body)}
        assertEquals(ChatFailure.PAYMENT,result.reason);assertEquals(402,result.httpStatus)
        assertTrue(result.detail,result.detail.startsWith("HTTP 402 Payment Required\n"))
        assertTrue(result.detail,result.detail.contains("Insufficient balance for key"))
        assertTrue(result.detail.contains("insufficient_quota"))
        assertFalse("the key never reaches the screen",result.detail.contains(key))
    }
    @Test fun noAnswerAtAllIsTheOnlyNetworkCase(){
        val result=run{Fake(it,null,failConnect=java.net.ConnectException("Failed to connect to example.invalid"))}
        assertEquals(ChatFailure.NETWORK,result.reason);assertEquals("网络连不上，可手动重试。",result.reason.display)
        assertTrue(result.detail.contains("ConnectException"))
    }
    @Test fun unknownHostAndNoRouteAreNetworkToo(){
        for(e in listOf(java.net.UnknownHostException("example.invalid"),java.net.NoRouteToHostException("No route to host")))
            assertEquals(ChatFailure.NETWORK,run{Fake(it,null,failConnect=e)}.reason)
    }
    @Test fun otherErrorsWithoutAnAnswerShowTheRawErrorInsteadOfNetwork(){
        val result=run{Fake(it,null,failConnect=IOException("unexpected end of stream on example.invalid"))}
        assertEquals(ChatFailure.UNKNOWN,result.reason)
        assertEquals("IOException: unexpected end of stream on example.invalid",result.detail)
    }
    @Test fun aTimeoutIsATimeoutNotNetwork(){
        val result=run{Fake(it,null,failConnect=java.net.SocketTimeoutException("timeout"))}
        assertEquals(ChatFailure.TIMEOUT,result.reason)
        assertTrue(result.detail,result.detail.contains("SocketTimeoutException: timeout"))
    }
    @Test fun runnerFallbackOnlyCallsRealConnectionFailuresNetwork(){
        assertTrue(unreachable(java.net.ConnectException("refused")))
        assertTrue(unreachable(IOException("wrapped",java.net.UnknownHostException("x"))))
        assertFalse(unreachable(java.net.SocketTimeoutException("timeout")))
        assertFalse(unreachable(IOException("Connection reset")))
        assertFalse(unreachable(IllegalStateException("bug")))
    }
    @Test fun connectionDroppedAfterTheServiceAnsweredIsNotCalledNetwork(){
        val result=run{Fake(it,200,failOnBody=true)}
        assertEquals(ChatFailure.RESPONSE_BROKEN,result.reason)
        assertTrue(result.detail.startsWith("HTTP 200"))
    }
    @Test fun serverErrorWithoutBodySaysSo(){
        val result=run{Fake(it,503,"")}
        assertEquals(ChatFailure.SERVER,result.reason);assertTrue(result.detail.startsWith("HTTP 503"));assertTrue(result.detail.contains("没有附带错误正文"))
    }
    @Test fun dialogTextIsOurLineThenTheProviderOriginal(){
        assertEquals("服务账户余额不足（HTTP 402），请充值或更换服务商后重试。\n\n服务返回的原文：\nHTTP 402\n{}",ChatFailureText.full(ChatFailure.PAYMENT,"HTTP 402\n{}"))
        assertEquals(ChatFailure.NETWORK.display,ChatFailureText.full(ChatFailure.NETWORK,""))
        assertEquals("出了点意外，本轮没有完成；原消息保留，可手动重试。\n\n详细原因：\nIOException: boom",ChatFailureText.full(ChatFailure.UNKNOWN,"IOException: boom"))
    }
}
