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

    private open class Fake(url:URL,val status:Int?,val body:String="",val failOnBody:Boolean=false,val failConnect:IOException?=null):HttpsURLConnection(url){
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
    @Test fun abortWhileWaitingForHeadersIsConnectionInterruptedNotUnknown(){
        val result=run{object:HttpsURLConnection(it){
            override fun getCipherSuite()=""
            override fun getLocalCertificates():Array<Certificate>?=null
            override fun getServerCertificates():Array<Certificate> = emptyArray()
            override fun connect(){}
            override fun disconnect(){}
            override fun usingProxy()=false
            override fun getOutputStream():OutputStream=ByteArrayOutputStream()
            override fun getResponseCode():Int=throw java.net.SocketException("Software caused connection abort")
            override fun getContentType()="application/json"
            override fun getInputStream():InputStream=ByteArrayInputStream(byteArrayOf())
        }}
        assertEquals(ChatFailure.CONNECTION_INTERRUPTED,result.reason)
        assertEquals("await_response_headers",result.meta?.stage)
        assertTrue(result.detail,result.detail.contains("等待服务商响应时连接中断"))
        assertTrue(result.detail,result.detail.contains("Software caused connection abort"))
    }
    @Test fun nonConnectionExceptionAfterWriteStaysUnknownNotConnectionInterrupted(){
        val result=run{object:HttpsURLConnection(it){
            override fun getCipherSuite()=""
            override fun getLocalCertificates():Array<Certificate>?=null
            override fun getServerCertificates():Array<Certificate> = emptyArray()
            override fun connect(){}
            override fun disconnect(){}
            override fun usingProxy()=false
            override fun getOutputStream():OutputStream=ByteArrayOutputStream()
            override fun getResponseCode():Int=throw IllegalStateException("unexpected state after body write")
            override fun getContentType()="application/json"
            override fun getInputStream():InputStream=ByteArrayInputStream(byteArrayOf())
        }}
        assertEquals(ChatFailure.UNKNOWN,result.reason)
        assertEquals("await_response_headers",result.meta?.stage)
        assertTrue(result.detail,result.detail.contains("IllegalStateException: unexpected state after body write"))
    }
    @Test fun socketAbortDuringWriteSetsConnectOrWriteStage(){
        val result=run{Fake(it,null,failConnect=java.net.SocketException("Broken pipe"))}
        assertEquals(ChatFailure.CONNECTION_INTERRUPTED,result.reason)
        assertEquals("connect_or_write",result.meta?.stage)
        assertTrue(result.detail,result.detail.contains("发送请求时连接中断"))
    }
    @Test fun truncatedResponseExplainsOutputLimit(){
        val body="""{"choices":[{"finish_reason":"length","message":{"content":"写到一半"}}]}"""
        val result=run{Fake(it,200,body)}
        assertEquals(ChatFailure.TRUNCATED,result.reason)
        assertTrue(result.detail,result.detail.contains("256"))
    }
    @Test fun partialNonStreamingVoiceToolReportsTheProviderStopReason(){
        for(reason in listOf("length","max_tokens","max_output_tokens")){
            val body="""{"choices":[{"finish_reason":"$reason","message":{"tool_calls":[{"function":{"name":"send_short_voice","arguments":"{"}}]}}]}"""
            val result=run{Fake(it,200,body)}
            assertEquals(ChatFailure.TRUNCATED,result.reason)
            assertTrue(result.rejectedVoiceCall)
            assertEquals(200,result.httpStatus)
            assertTrue(result.detail.contains(reason))
            assertEquals(false,result.meta?.streamReplies)
        }
    }
    @Test fun emptyCompletedStreamRetainsTheResponseStage()=runBlocking {
        val stream="data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val client=HttpsTextChatClient(HttpsConnectionFactory{object:Fake(it,200,stream){
            override fun getContentType()="text/event-stream"
        }})
        val result=client.streamEvents(config,key,request){} as TextChatResult.Failure
        assertEquals(ChatFailure.FORMAT,result.reason)
        assertEquals(200,result.httpStatus)
        assertEquals("read_response_body",result.meta?.stage)
        assertEquals(true,result.meta?.streamReplies)
    }
    @Test fun http200BadJsonAndBrokenStreamAreLocalDetailsNotProviderBody(){
        val badJson=run{Fake(it,200,"not-valid-json")}
        assertEquals(ChatFailure.FORMAT,badJson.reason)
        assertEquals("local",badJson.meta?.detailSource)
        val badJsonText=ChatFailureText.full(badJson.reason,badJson.detail,badJson.meta)
        assertTrue(badJsonText,badJsonText.contains("详细原因：\nHTTP 200"))
        assertFalse(badJsonText,badJsonText.contains("服务返回的原文："))

        val broken=run{Fake(it,200,failOnBody=true)}
        assertEquals(ChatFailure.RESPONSE_BROKEN,broken.reason)
        assertEquals("local",broken.meta?.detailSource)
        val brokenText=ChatFailureText.full(broken.reason,broken.detail,broken.meta)
        assertTrue(brokenText,brokenText.contains("详细原因：\nHTTP 200"))
        assertFalse(brokenText,brokenText.contains("服务返回的原文："))

        val providerErr=run{Fake(it,402,"{\"error\":\"no quota\"}")}
        assertEquals("provider_http",providerErr.meta?.detailSource)
        assertTrue(ChatFailureText.full(providerErr.reason,providerErr.detail,providerErr.meta).contains("服务返回的原文：\nHTTP 402"))
    }
}
