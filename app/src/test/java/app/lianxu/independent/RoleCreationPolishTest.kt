package app.lianxu.independent

import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RoleCreationPolishTest {
    @Test fun aModelChangedAfterCompletionCannotBeAppliedToTheDraft()=runBlocking{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val valid=AtomicBoolean(true)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success(json())}
            val state=RoleCreationPolish(scope,client,{credential()},{valid.get()});prepare(state);state.start(draft());settled(state)
            val result=requireNotNull(state.result);valid.set(false);var applied=false
            assertFalse(state.consumeCurrent(result){applied=true});assertFalse(applied);assertNull(state.result);assertTrue(state.message.contains("已变化"))
        }finally{scope.cancel()}
    }
    private val text=RoleCreationText("合成名字","喜欢看书； 话不多。","旧识","用短句。","","保持原设定。")
    private val config=ModelConfig("synthetic-chat","合成聊天模型","custom","https://example.invalid/v1","synthetic-model",64000,2048)
    private fun json(value:RoleCreationText=text)=Gson().toJson(value)
    private fun draft()=RoleCreationDraft(name=text.name,definition=text.definition,relationship=text.relationship,expression=text.expression,opening=text.opening,extra=text.extra)
    private fun credential()=ModelCredential(config,"synthetic-not-a-real-credential","stamp-a")
    private suspend fun settled(state:RoleCreationPolish){withTimeout(5000){while(state.running)delay(5)}}
    private suspend fun prepare(state:RoleCreationPolish){state.refreshModel();withTimeout(5000){while(!state.checked)delay(5)}}

    @Test fun requestContainsOnlyTheSixEnteredFieldsAndNoCredential(){
        val request=RolePolishProtocol.request(text,config)
        assertEquals(listOf("system","user"),request.messages.map{it.role});assertTrue(request.tools.isEmpty())
        assertEquals(json(text),request.messages.last().content)
        assertFalse(request.messages.any{it.content.contains(credential().key)})
        assertTrue(request.messages.first().content.contains("不补写空白字段"))
    }
    @Test fun capacityFailureDoesNotTrimTheUserFields(){
        val low=config.copy(contextTokens=2048,outputTokens=2048)
        assertTrue(runCatching{RolePolishProtocol.request(text,low)}.isFailure)
        assertEquals("喜欢看书； 话不多。",text.definition)
    }
    @Test fun aWellFormedResultKeepsNameAndEmptyOptionalFields(){
        val value=text.copy(definition="喜欢看书，话不多。")
        assertEquals(value,RolePolishProtocol.parse(json(value),text))
    }
    @Test fun inferredEmptyFieldOrRenamedCharacterIsRejected(){
        assertTrue(runCatching{RolePolishProtocol.parse(json(text.copy(opening="你好。")),text)}.isFailure)
        assertTrue(runCatching{RolePolishProtocol.parse(json(text.copy(name="改了名字")),text)}.isFailure)
        assertTrue(runCatching{RolePolishProtocol.parse(json(text.copy(relationship="")),text)}.isFailure)
    }
    @Test fun missingUnknownDuplicateOrNonStringFieldsAreRejected(){
        val raw=json()
        for(value in listOf(raw.replace("\"name\":\"合成名字\",",""),raw.dropLast(1)+",\"unknown\":\"x\"}",raw.dropLast(1)+",\"name\":\"合成名字\"}",raw.replace("\"name\":\"合成名字\"","\"name\":null"),"[$raw]",raw+"{}")){
            assertTrue(value,runCatching{RolePolishProtocol.parse(value,text)}.isFailure)
        }
    }
    @Test fun oversizeAndBrokenUnicodeCannotBecomeAPreview(){
        assertTrue(runCatching{RolePolishProtocol.parse(json(text.copy(definition="字".repeat(8001))),text)}.isFailure)
        assertTrue(runCatching{RolePolishProtocol.parse(json(text.copy(definition="\uD83D")),text)}.isFailure)
        assertTrue(runCatching{RolePolishProtocol.parse(json(text.copy(definition="a\u0000b")),text)}.isFailure)
    }
    @Test fun noCredentialDoesNotSendARequest()=runBlocking{
        val calls=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();error("Must not send")}}
            val state=RoleCreationPolish(scope,client,{null},{true})
            prepare(state)
            state.start(draft());settled(state);assertEquals(0,calls.get());assertNull(state.result);assertTrue(state.message.contains("原文"))
        }finally{scope.cancel()}
    }
    @Test fun repeatedStartIsOneRequestAndDoesNotMutateTheOriginal()=runBlocking{
        val calls=AtomicInteger();val gate=CompletableDeferred<TextChatResult>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return gate.await()}}
            val state=RoleCreationPolish(scope,client,{credential()},{true});val source=draft()
            prepare(state)
            state.start(source);repeat(8){state.start(source)}
            withTimeout(5000){while(calls.get()!=1)delay(5)}
            gate.complete(TextChatResult.Success(json(text.copy(definition="喜欢看书，话不多。"))))
            settled(state);assertEquals(1,calls.get());assertEquals(text,state.result!!.source);assertEquals(text,source.text)
        }finally{gate.complete(TextChatResult.Failure(ChatFailure.NETWORK));scope.cancel()}
    }
    @Test fun aCancelledLateResultCannotReplaceANewerRequest()=runBlocking{
        val count=AtomicInteger();val first=CompletableDeferred<TextChatResult>();val second=CompletableDeferred<TextChatResult>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                val gate=if(count.incrementAndGet()==1)first else second
                return withContext(NonCancellable){gate.await()}
            }}
            val state=RoleCreationPolish(scope,client,{credential()},{true});prepare(state);state.start(draft())
            withTimeout(5000){while(count.get()!=1)delay(5)};state.cancel();state.start(draft())
            withTimeout(5000){while(count.get()!=2)delay(5)}
            second.complete(TextChatResult.Success(json(text.copy(definition="第二次整理。"))));settled(state)
            val accepted=state.result;first.complete(TextChatResult.Success(json(text.copy(definition="迟到结果。"))));delay(100)
            assertSame(accepted,state.result);assertEquals("第二次整理。",state.result!!.value.definition)
        }finally{first.complete(TextChatResult.Failure(ChatFailure.NETWORK));second.complete(TextChatResult.Failure(ChatFailure.NETWORK));scope.cancel()}
    }
    @Test fun changedGlobalSelectionRejectsTheOldCredentialResponse()=runBlocking{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val unchanged=AtomicBoolean(true)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{unchanged.set(false);return TextChatResult.Success(json())}}
            val state=RoleCreationPolish(scope,client,{credential()},{unchanged.get()});prepare(state);state.start(draft());settled(state)
            assertNull(state.result);assertTrue(state.message.contains("已变化"))
        }finally{scope.cancel()}
    }
    @Test fun failureDetailsAreNotExposedAndRetryCanSucceed()=runBlocking{
        val attempts=AtomicInteger();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=
                if(attempts.incrementAndGet()==1)TextChatResult.Failure(ChatFailure.UNAUTHORIZED,401,"private server detail")else TextChatResult.Success(json())}
            val state=RoleCreationPolish(scope,client,{credential()},{true});prepare(state);state.start(draft());settled(state)
            assertFalse(state.message.contains("private server detail"));assertTrue(state.message.contains("原文"))
            state.start(draft());settled(state);assertNotNull(state.result);assertEquals(2,attempts.get())
        }finally{scope.cancel()}
    }
    @Test fun aDifferentModelBeforeTheClickRequiresAnotherConfirmation()=runBlocking{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val active=AtomicReference(credential());val calls=AtomicInteger()
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return TextChatResult.Success(json())}}
            val state=RoleCreationPolish(scope,client,{active.get()},{it==active.get().stamp});prepare(state)
            active.set(ModelCredential(config.copy(id="synthetic-b",name="合成模型B"),credential().key,"stamp-b"))
            state.start(draft());settled(state)
            assertEquals(0,calls.get());assertTrue(state.message.contains("没有发送"));assertNull(state.result)
            withTimeout(5000){while(!state.checked)delay(5)}
            state.start(draft());settled(state);assertEquals(1,calls.get());assertNotNull(state.result)
        }finally{scope.cancel()}
    }
    @Test fun aProviderCannotEchoTheCredentialIntoThePreview()=runBlocking{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success(json(text.copy(definition=key)))}
            val state=RoleCreationPolish(scope,client,{credential()},{true});prepare(state);state.start(draft());settled(state)
            assertNull(state.result);assertFalse(state.message.contains(credential().key));assertTrue(state.message.contains("原文"))
        }finally{scope.cancel()}
    }
    @Test fun malformedTextOrUnexpectedToolsLeavesOnlyTheOriginalOption()=runBlocking{
        for(reply in listOf(TextChatResult.Success("not JSON"),TextChatResult.Success(json(),listOf(ChatToolCall("synthetic","unexpected","{}"))))){
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            try{
                val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=reply}
                val state=RoleCreationPolish(scope,client,{credential()},{true});prepare(state);state.start(draft());settled(state)
                assertNull(state.result);assertTrue(state.message.contains("原文"))
            }finally{scope.cancel()}
        }
    }
    @Test fun removingTheSelectedModelClearsItsOldDisplayWithoutSending()=runBlocking{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val active=AtomicReference<ModelCredential?>(credential());val calls=AtomicInteger()
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return TextChatResult.Success(json())}}
            val state=RoleCreationPolish(scope,client,{active.get()},{false});prepare(state);assertNotNull(state.modelLabel)
            active.set(null);state.start(draft());settled(state);withTimeout(5000){while(!state.checked)delay(5)}
            assertNull(state.modelLabel);assertEquals(0,calls.get());assertNull(state.result)
        }finally{scope.cancel()}
    }
    @Test fun aReadFailureIsDistinctFromAnUnconfiguredModel()=runBlocking{
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=error("No request allowed")}
            val state=RoleCreationPolish(scope,client,{error("private read detail")},{true});prepare(state)
            assertTrue(state.modelReadFailed);assertNull(state.modelLabel);assertFalse(state.message.contains("private read detail"))
            assertTrue(state.message.contains("原文"));assertNull(state.result)
        }finally{scope.cancel()}
    }
    @Test fun aFailedClickRecheckClearsThePreviousModelDisplay()=runBlocking{
        for(failCredential in listOf(true,false)){
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val fail=AtomicBoolean(false);val calls=AtomicInteger()
            try{
                val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls.incrementAndGet();return TextChatResult.Success(json())}}
                val state=RoleCreationPolish(scope,client,{if(fail.get()&&failCredential)error("private read detail")else credential()},{if(fail.get())error("private read detail")else true})
                prepare(state);assertNotNull(state.modelLabel);fail.set(true);state.start(draft());settled(state)
                assertTrue(state.modelReadFailed);assertNull(state.modelLabel);assertEquals(0,calls.get());assertNull(state.result)
                assertFalse(state.message.contains("private read detail"));assertTrue(state.message.contains("原文"))
                fail.set(false);prepare(state);assertFalse(state.modelReadFailed);assertNotNull(state.modelLabel);assertEquals("",state.message)
            }finally{scope.cancel()}
        }
    }
}
