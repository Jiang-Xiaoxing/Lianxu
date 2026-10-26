package app.lianxu.independent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URL
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

fun interface HttpsConnectionFactory { fun open(url:URL):HttpsURLConnection }
class HttpsModelClient(
    private val factory:HttpsConnectionFactory=HttpsConnectionFactory {it.openConnection() as HttpsURLConnection},
    private val timeoutMillis:Int=10000
):ModelDirectoryClient {
    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult = withContext(Dispatchers.IO) {
        Diagnostics.registerSecret(key)
        Diagnostics.event("model.http.request",mapOf("endpoint" to config.baseUrl,"provider" to config.providerId,"fetch_models" to fetchModels,"timeout_ms" to timeoutMillis),secrets=listOf(key))
        if(ConfigRules.urlError(config.baseUrl)!=null || key.isBlank() || key.any {it.isWhitespace() || it.code<32}) return@withContext ConfigResult.Failure(ConfigError.INVALID)
        val provider=ProviderPresets.get(config.providerId)
        val path=(if(fetchModels) provider.modelsPath else provider.testPath) ?: return@withContext ConfigResult.Failure(ConfigError.UNSUPPORTED)
        var connection:HttpsURLConnection?=null
        val deadline=System.nanoTime()+timeoutMillis*3L*1_000_000L
        try {
            connection=factory.open(URL(config.baseUrl.trimEnd('/')+"/"+path))
            connection.instanceFollowRedirects=false
            connection.connectTimeout=timeoutMillis; connection.readTimeout=timeoutMillis;connection.requestMethod="GET"
            connection.setRequestProperty(provider.authHeader,if(provider.authHeader=="Authorization") "Bearer $key" else key)
            connection.setRequestProperty("Accept","application/json")
            val status=connection.responseCode
            Diagnostics.event("model.http.status",mapOf("status" to status),secrets=listOf(key))
            if(status !in 200..299){DiagnosticHttp.errorBody(connection,deadline,key);return@withContext ConfigResult.Failure(when(status) {401->ConfigError.UNAUTHORIZED;403->ConfigError.FORBIDDEN;429->ConfigError.RATE_LIMIT;in 300..399->ConfigError.REDIRECT;else->ConfigError.HTTP},status)}
            // Bound untrusted directory responses; never show raw server errors or exception text.
            val bytes=connection.inputStream.use {input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(output.size()<=1024*1024) {
                    val remaining=((deadline-System.nanoTime())/1_000_000L).toInt()
                    if(remaining<=0)throw SocketTimeoutException()
                    connection.readTimeout=minOf(timeoutMillis,remaining)
                    val count=input.read(buffer,0,minOf(buffer.size,1024*1024+1-output.size()));if(count<0)break;output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            if(bytes.size>1024*1024) return@withContext ConfigResult.Failure(ConfigError.FORMAT)
            val body=JSONObject(String(bytes,Charsets.UTF_8))
            Diagnostics.event("model.http.response",mapOf("response_bytes" to bytes.size,"response" to body.toString()),secrets=listOf(key))
            if(!fetchModels) {
                if((path=="key" && body.optJSONObject("data")==null) || (path!="key" && body.optJSONArray("data")==null)) ConfigResult.Failure(ConfigError.FORMAT) else ConfigResult.Success()
            } else {
                val data=body.optJSONArray("data") ?: return@withContext ConfigResult.Failure(ConfigError.FORMAT)
                val ids=(0 until data.length()).mapNotNull {data.optJSONObject(it)?.optString("id")?.takeIf {id -> id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}")) && !id.contains(key)}}.distinct().take(500)
                ConfigResult.Success(ids)
            }
        } catch(e:SocketTimeoutException) {Diagnostics.failure("model.http.timeout",e,secrets=listOf(key));ConfigResult.Failure(ConfigError.TIMEOUT)}
        catch(e:SSLException) {Diagnostics.failure("model.http.tls_failed",e,secrets=listOf(key));ConfigResult.Failure(ConfigError.TLS)}
        catch(e:org.json.JSONException) {Diagnostics.failure("model.http.format_failed",e,secrets=listOf(key));ConfigResult.Failure(ConfigError.FORMAT)}
        catch(e:Exception) {Diagnostics.failure("model.http.failed",e,secrets=listOf(key));ConfigResult.Failure(ConfigError.NETWORK)}
        finally {connection?.disconnect()}
    }
}
