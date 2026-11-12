package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 详细诊断：失败或取消时已经流进来的思考不能被记成「未返回」；思考过长被截断时，
 * 轮次与请求的关联字段不能跟着被截掉。
 */
class DiagnosticReasoningStatusTest {
    private val config=ModelConfig("c","合成","custom","https://example.invalid/v1","m")
    private val request=TextChatRequest(listOf(WireMessage("user","合成请求")))
    private fun rows(bytes:ByteArray)=bytes.toString(Charsets.UTF_8).lineSequence()
        .mapNotNull{runCatching{JsonParser.parseString(it).asJsonObject}.getOrNull()}.toList()
    private inline fun withJournal(block:(DiagnosticJournal)->Unit){
        val dir=Files.createTempDirectory("diag-reasoning-status-").toFile();val journal=DiagnosticJournal(dir,"test")
        Diagnostics.installForTest(journal)
        try{block(journal)}finally{Diagnostics.installForTest(null);journal.close();require(dir.name.startsWith("diag-reasoning-status-"));dir.deleteRecursively()}
    }
    private fun rowNamed(journal:DiagnosticJournal,name:String,detailed:Boolean=true):JsonObject{
        val matches=rows(journal.export(detailed)).filter{it.get("event")?.takeIf{!it.isJsonNull}?.asString==name}
        assertTrue("缺少事件 $name",matches.isNotEmpty())
        return matches.last()
    }
    private fun dataOf(row:JsonObject)=row["data"].asJsonObject

    @Test fun failureAfterReasoningKeepsTextAsPartialNotAbsent()=runBlocking{
        withJournal{journal->
            Diagnostics.detailed=true
            val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                    onEvent(TextStreamEvent.Reasoning("已经收到的第一段"))
                    onEvent(TextStreamEvent.Reasoning("加上第二段"))
                    return TextChatResult.Failure(ChatFailure.TIMEOUT)
                }
            })
            client.streamEvents(config,"synthetic",request){}
            val data=dataOf(rowNamed(journal,"network.response"))
            assertEquals("failure",data["status"].asString)
            assertEquals("失败前已流出的思考要标成 partial","partial",data["reasoning_status"].asString)
            assertEquals("已经收到的第一段加上第二段",data["reasoning_text"].asString)
            assertEquals("已经收到的第一段加上第二段".length,data["reasoning_chars"].asInt)
            assertNotNull("请求编号要能对上",data["request_id"].let{if(it==null||it.isJsonNull)null else it.asString})
        }
    }

    @Test fun cancelledAfterReasoningKeepsTextAsPartial()=runBlocking{
        withJournal{journal->
            Diagnostics.detailed=true
            val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                    onEvent(TextStreamEvent.Reasoning("取消前想到一半"))
                    throw CancellationException("synthetic cancel")
                }
            })
            try{client.streamEvents(config,"synthetic",request){}}catch(_:CancellationException){}
            val data=dataOf(rowNamed(journal,"network.cancelled"))
            assertEquals("partial",data["reasoning_status"].asString)
            assertEquals("取消前想到一半",data["reasoning_text"].asString)
        }
    }

    @Test fun successWithoutReasoningStaysAbsent()=runBlocking{
        withJournal{journal->
            Diagnostics.detailed=true
            val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                    onEvent(TextStreamEvent.Content("只有正文"))
                    return TextChatResult.Success("只有正文")
                }
            })
            client.streamEvents(config,"synthetic",request){}
            val data=dataOf(rowNamed(journal,"network.response"))
            assertEquals("absent",data["reasoning_status"].asString)
            assertEquals(0,data["reasoning_chars"].asInt)
            assertFalse("没返回思考时不能编一段原文",data.has("reasoning_text"))
        }
    }

    @Test fun detailedOffStillReportsPartialWithCharacterCount()=runBlocking{
        withJournal{journal->
            Diagnostics.detailed=false
            val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                    onEvent(TextStreamEvent.Reasoning("关闭详细时也不该丢状态"))
                    return TextChatResult.Failure(ChatFailure.TIMEOUT)
                }
            })
            client.streamEvents(config,"synthetic",request){}
            val log=journal.export(true).toString(Charsets.UTF_8)
            val data=dataOf(rowNamed(journal,"network.response"))
            assertEquals("partial",data["reasoning_status"].asString)
            assertEquals("关闭详细时也不该丢状态".length,data["reasoning_chars"].asInt)
            assertEquals("[详细诊断未开启]",data["reasoning_text"].asString)
            assertFalse("原文不能落盘",log.contains("关闭详细时也不该丢状态"))
        }
    }

    @Test fun oversizedFirstChunkKeepsBoundedPrefixAndFullCount()=runBlocking{
        withJournal{journal->
            Diagnostics.detailed=true
            val longText="长".repeat(40000)
            val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                    onEvent(TextStreamEvent.Reasoning(longText))
                    return TextChatResult.Failure(ChatFailure.TIMEOUT)
                }
            })
            client.streamEvents(config,"synthetic",request){}
            val row=rowNamed(journal,"network.response")
            val data=dataOf(row)
            assertEquals("一块就超限也必须算收到，而不是未返回","partial",data["reasoning_status"].asString)
            assertEquals("总字数要按实际收到算",40000,data["reasoning_chars"].asInt)
            assertTrue("超限要标截断",data["reasoning_truncated"].asBoolean)
            val excerpt=row["data_excerpt"].asString
            assertTrue("限额内的前缀要留下",excerpt.count{it=='长'}>=30000)
            assertTrue("前缀不能超过限额",excerpt.count{it=='长'}<=Diagnostics.REASONING_LIMIT)
        }
    }

    @Test fun laterChunksFillTheRemainingBudget()=runBlocking{
        withJournal{journal->
            Diagnostics.detailed=true
            val client=LoggedTextChatClient(object:TextChatClient{
                override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("typed stream only")
                override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult{
                    onEvent(TextStreamEvent.Reasoning("甲".repeat(30000)))
                    onEvent(TextStreamEvent.Reasoning("乙".repeat(4000)))
                    return TextChatResult.Failure(ChatFailure.TIMEOUT)
                }
            })
            client.streamEvents(config,"synthetic",request){}
            val row=rowNamed(journal,"network.response")
            val excerpt=row["data_excerpt"].asString
            assertTrue("前一块要完整保留",excerpt.count{it=='甲'}>=30000)
            val later=excerpt.count{it=='乙'}
            assertTrue("后一块要填进剩余额度，而不是整块丢掉",later>=2000)
            assertTrue("后一块不能超过剩余额度",later<=2768)
        }
    }

    @Test fun longReasoningKeepsTurnAndRequestCorrelation()=runBlocking{
        withJournal{journal->
            Diagnostics.detailed=true
            val longText="长".repeat(40000)
            withContext(Diagnostics.context("network")){
                Diagnostics.event("network.response",mapOf("request_id" to "req-1","status" to "success","turn" to "t-1","attempt" to 2,"step" to 1,
                    "call_id" to "c9","reasoning_status" to "returned","reasoning_chars" to longText.length,"reasoning_text" to longText))
            }
            val row=rowNamed(journal,"network.response")
            assertTrue("整行必须标明截断",row["truncated"].asBoolean)
            val data=dataOf(row)
            assertEquals("截断后仍要能对上请求","req-1",data["request_id"].asString)
            assertEquals("截断后仍要能对上轮次","t-1",data["turn"].asString)
            assertEquals("截断后仍要能对上第几次","2",data["attempt"].asString)
            assertEquals("截断后仍要能对上步骤","1",data["step"].asString)
            assertEquals("工具调用编号也要留下","c9",data["call_id"].asString)
            assertEquals("截断后仍要能对上批次","success",data["status"].asString)
            assertTrue("批次 span 要留下",data.has("span")&&!data["span"].isJsonNull)
            assertTrue("原始正文得有摘录",row["data_excerpt"].asString.isNotEmpty())
        }
    }
}
