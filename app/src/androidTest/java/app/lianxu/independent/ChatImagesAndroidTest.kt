package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Collections

/** Only owned synthetic pixels, isolated databases/keys and certificate-verified loopback TLS. */
class ChatImagesAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var store:Store;private lateinit var repo:ProcessingRepository
    private lateinit var vault:ModelVault;private lateinit var ledger:ChatLedger;private lateinit var scope:CoroutineScope
    @Before fun setup()=runBlocking{
        name="chat-images-${System.nanoTime()}.db";store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        store.add(listOf(Friend("a","合成图片角色","合成测试角色"),Friend("b","另一角色","隔离合成角色")),false)
        vault=ModelVault(context,"chat-images-${System.nanoTime()}",store);scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    }
    @After fun cleanup(){runBlocking{withTimeout(15000){scope.coroutineContext.job.cancelAndJoin()}};vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name)}
    private fun pixels(format:Bitmap.CompressFormat=Bitmap.CompressFormat.PNG,width:Int=320,height:Int=280):ByteArray{
        val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.rgb(221,232,205));val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.color=Color.YELLOW;c.drawCircle(width*.45f,height*.5f,width*.19f,p);p.color=Color.rgb(80,60,20);c.drawCircle(width*.45f,height*.5f,width*.08f,p)
        p.color=Color.BLUE;c.drawPath(Path().apply{moveTo(width*.82f,12f);lineTo(width-12f,12f);lineTo(width-12f,height*.18f);close()},p)
        p.color=Color.BLACK;p.textSize=height*.12f;c.drawText("7",width*.8f,height*.3f,p)
        return ByteArrayOutputStream().also{b.compress(format,90,it);b.recycle()}.toByteArray()
    }
    private fun image()=ChatImages.prepare(pixels().inputStream())
    private fun config(id:String,base:String)=ModelConfig(id,"合成$id","custom",base,id,128000,2048)
    private fun configure(base:String,aux:Boolean=false){
        vault.save(config("chat",base),"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,"chat")
        if(aux){vault.save(config("vision",base),"synthetic-key-only");vault.setPurpose(ModelPurpose.AUX_VISION,"vision")}
        ChatImages.setEnabled(store.writableDatabase,aux)
    }
    private suspend fun request(t:ChatTurn):TextChatRequest{
        val c=ledger.context(t.id)
        return (CompanionPrompt.build(c.friend,RolePromptStore.decode(c.rawPrompts),c.recent,c.input,inputImage=c.image,currentMessageId=t.userMessageId,capacity=128000) as PromptBuild.Ready).request
    }
    private fun success(text:String)=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",text)))).toString()
    private fun decodedHashes(body:String):List<String>{
        val messages=JSONObject(body).getJSONArray("messages")
        return buildList{for(i in 0 until messages.length()){
            val content=messages.getJSONObject(i).optJSONArray("content")?:continue
            for(j in 0 until content.length()){val block=content.getJSONObject(j);if(block.optString("type")=="image_url")add(RoleArchive.hash(Base64.getDecoder().decode(block.getJSONObject("image_url").getString("url").substringAfter(','))))}
        }}
    }
    @Test fun commonFormatsLargeCorruptAndInputBudgetAreBounded(){
        for(format in listOf(Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.WEBP_LOSSLESS)){
            val original=pixels(format);val output=ChatImages.prepare(original.inputStream());assertEquals(320,output.width);assertArrayEquals(original,output.bytes)
        }
        val original=pixels(width=4000,height=3000);val big=ChatImages.prepare(original.inputStream());assertEquals(4000,big.width);assertEquals(3000,big.height);assertArrayEquals(original,big.bytes)
        assertTrue(runCatching{ChatImages.prepare(byteArrayOf(1,2,3).inputStream())}.isFailure)

    }
    @Test fun pictureAndEmptyCaptionPersistTogetherSurviveReopenAndRollbackTogether()=runBlocking{
        val chosen=image();val turn=ledger.begin("a","","chat",image=chosen)
        assertEquals("",store.messages().first{it.id==turn.userMessageId}.text)
        val ref=ChatImages.ref(store.readableDatabase,turn.userMessageId)!!;assertEquals(chosen.hash,RoleArchive.hash(ChatImages.read(store.readableDatabase,ref)))
        assertTrue(ledger.finish(turn.id,1,"合成回复"));store.close();store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        assertEquals(chosen.hash,store.messages().first{it.id==turn.userMessageId}.image!!.hash)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_picture BEFORE INSERT ON chat_images BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val before=store.messages().size
        assertTrue(runCatching{ledger.begin("a","应当整体回滚","chat",image=chosen)}.isFailure)
        assertEquals(before,store.messages().size);assertEquals(1,RoleAssets(repo).list("a").size)
    }
    @Test fun concurrentVisiblePrefixSaveAndFinalCommitAlwaysSettle():Unit=runBlocking{
        val chosen=image()
        repeat(30){index->
            val t=ledger.begin("a","并发图片$index","chat",image=chosen);val lease=ChatAttemptLease();val start=CompletableDeferred<Unit>()
            val epoch=AppMutationGate.epoch()
            val prefix=async(Dispatchers.IO){start.await();ledger.recordSpoken(t.id,1,"合成回复",epoch,{lease.isActive()},lease)}
            val finish=async(Dispatchers.IO){start.await();ledger.finish(t.id,1,"合成回复",lease=lease)}
            start.complete(Unit)
            withTimeout(8000){prefix.await();assertTrue(finish.await())}
            assertEquals(TurnState.COMPLETED,ledger.all().last().state)
        }
        assertEquals(60,store.messages().size)
    }
    @Test fun changingRouteWhileWaitingForVisibleReplyFailsInsteadOfStalling():Unit=runBlocking{
        configure("https://example.invalid/v1")
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("这段合成回复尚未确认显示")}
        val memory=MemoryEngine(scope,repo,ModelAccess(vault),client,{})
        val runner=ConversationRunner(scope,store,vault,client,repo,memory,{},{},displayReplies=true)
        withContext(Dispatchers.Main){assertTrue(runner.sendImage("a","合成图片",image()){})}
        withTimeout(10000){while(runner.previews["a"]?.bubbles.isNullOrEmpty())delay(10)}
        ChatImages.setEnabled(store.writableDatabase,true)
        withTimeout(5000){while(ledger.all().last().state==TurnState.PENDING||"a" in runner.sending)delay(10)}
        assertEquals(ChatFailure.SOURCE_CHANGED,ledger.all().last().failure)
        assertTrue(store.messages().none{!it.mine})
    }
    @Test fun directRouteResendsExactPictureForUnmentionedSecondTurnDetail():Unit=runBlocking{
        val bodies=Collections.synchronizedList(mutableListOf<String>())
        val chosen=image()
        TextTlsMock(maxRequests=2,responder={raw->bodies+=raw;success(if(bodies.size==1)"一朵黄色的花"else if(decodedHashes(raw)==listOf(chosen.hash))"右上角有蓝色三角形和数字7"else"缺图，无法回答")}).use{server->
            configure(server.base)
            val first=ledger.begin("a","先说主体","chat",image=chosen)
            val route=ChatImageRoute(store,vault,server.client(),first,config("chat",server.base),AppMutationGate.epoch()){true}
            assertEquals("一朵黄色的花",(route.complete(config("chat",server.base),"synthetic-key-only",request(first)) as TextChatResult.Success).text)
            assertTrue(ledger.finishWithMemory(first.id,1,"一朵黄色的花",onCompleted={route.commit(it)}))
            val second=ledger.begin("a","右上角的符号和数字是什么？","chat")
            val next=ChatImageRoute(store,vault,server.client(),second,config("chat",server.base),AppMutationGate.epoch()){true}
            val answer=next.complete(config("chat",server.base),"synthetic-key-only",request(second)) as TextChatResult.Success
            assertTrue(answer.text.contains("蓝色三角形和数字7"));assertEquals(2,server.calls.get())
            assertTrue(bodies.all{decodedHashes(it)==listOf(chosen.hash)});assertTrue(bodies.all{JSONObject(it).getString("model")=="chat"})
            println("IMAGE_ROUTE_DIRECT hashes=${bodies.map{decodedHashes(it)}} second=${answer.text}")
        }
    }
    @Test fun auxiliaryRouteReobservesPictureForSecondQuestionAndChatNeverGetsPixels():Unit=runBlocking{
        val bodies=Collections.synchronizedList(mutableListOf<String>());val chosen=image()
        TextTlsMock(maxRequests=4,responder={raw->
            bodies+=raw;val model=JSONObject(raw).getString("model");val detailed=raw.contains("符号和数字")
            success(if(model=="vision")if(detailed&&decodedHashes(raw)==listOf(chosen.hash))"右上角有蓝色三角形和数字7"else"一朵黄色的花"else if(raw.contains("蓝色三角形和数字7"))"右上角有蓝色三角形和数字7"else"一朵黄色的花")
        }).use{server->
            configure(server.base,true)
            val first=ledger.begin("a","先说主体","chat",image=chosen)
            val route=ChatImageRoute(store,vault,server.client(),first,config("chat",server.base),AppMutationGate.epoch()){true}
            val firstResult=route.complete(config("chat",server.base),"synthetic-key-only",request(first)) as TextChatResult.Success
            assertFalse(firstResult.text.contains("三角形"));assertTrue(ledger.finishWithMemory(first.id,1,firstResult.text,onCompleted={route.commit(it)}))
            val second=ledger.begin("a","右上角的符号和数字是什么？","chat")
            val next=ChatImageRoute(store,vault,server.client(),second,config("chat",server.base),AppMutationGate.epoch()){true}
            val result=next.complete(config("chat",server.base),"synthetic-key-only",request(second)) as TextChatResult.Success
            assertTrue(result.text.contains("蓝色三角形和数字7"));assertTrue(ledger.finishWithMemory(second.id,1,result.text,onCompleted={next.commit(it)}))
            val vision=bodies.filter{JSONObject(it).getString("model")=="vision"};val chat=bodies.filter{JSONObject(it).getString("model")=="chat"}
            assertEquals(2,vision.size);assertEquals(2,chat.size);assertTrue(vision.all{decodedHashes(it)==listOf(chosen.hash)});assertTrue(chat.all{decodedHashes(it).isEmpty()&&!it.contains("data:image")})
            val sources=repo.sources("a");assertTrue(sources.all{it.imageObservations.size==1});assertEquals("先说主体",sources[0].user.text)
            assertEquals("image",MemoryNumberedReferences(sources,emptyList()).sourceRows().getJSONObject(2).getString("side"))
            println("IMAGE_ROUTE_AUX visionHashes=${vision.map{decodedHashes(it)}} chatImages=${chat.map{decodedHashes(it).size}} second=${result.text}")
        }
    }
    @Test fun actualHttpErrorsKeepStatusRedactPixelsAndNeverRetryOrFallback():Unit=runBlocking{
        for(status in listOf(400,401,402,403,413,415,422,429,500,503,302))TextTlsMock(status,response="{\"error\":\"synthetic image rejected data:image/jpeg;base64,PRIVATEPIXELS\"}").use{server->
            val c=config("chat",server.base);val result=server.client().complete(c,"synthetic-key-only",TextChatRequest(listOf(WireMessage("user","",images=listOf(WireImage("image/jpeg",image().bytes)))))) as TextChatResult.Failure
            assertEquals(status,result.httpStatus);assertEquals(1,server.calls.get());assertFalse(result.detail.contains("PRIVATEPIXELS"));assertTrue(result.detail.contains(status.toString()))
            if(status==400)assertEquals(ChatFailure.REJECTED,result.reason)
            if(status==413)assertEquals(ChatFailure.IMAGE_PROVIDER_LIMIT,result.reason)
        }
    }
    @Test fun missingAuxiliaryAndAuxiliaryFailureNeverCallChat():Unit=runBlocking{
        configure("https://example.invalid/v1");ChatImages.setEnabled(store.writableDatabase,true)
        val first=ledger.begin("a","合成问题","chat",image=image());var calls=0
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success("不应到达")}}
        val route=ChatImageRoute(store,vault,client,first,config("chat","https://example.invalid/v1"),AppMutationGate.epoch()){true}
        val failure=runCatching{route.complete(config("chat","https://example.invalid/v1"),"synthetic-key-only",request(first))}.exceptionOrNull()
        assertEquals(ChatFailure.MISSING_VISION_MODEL,(failure as CompanionModelFailure).failure);assertEquals(0,calls)
        TextTlsMock(415,response="unsupported image").use{server->
            vault.save(config("vision",server.base),"synthetic-key-only");vault.setPurpose(ModelPurpose.AUX_VISION,"vision")
            val actual=ChatImageRoute(store,vault,server.client(),first,config("chat","https://example.invalid/v1"),AppMutationGate.epoch()){true}
            val rejected=runCatching{actual.complete(config("chat","https://example.invalid/v1"),"synthetic-key-only",request(first))}.exceptionOrNull() as CompanionModelFailure
            assertEquals(ChatFailure.REJECTED,rejected.failure);assertTrue(rejected.detail.contains("415"));assertEquals(1,server.calls.get())
        }
    }
    @Test fun switchRoundTripOrCancelledLeaseRejectsLateResponseAndObservation():Unit=runBlocking{
        configure("https://example.invalid/v1",true);val t=ledger.begin("a","合成问题","chat",image=image())
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var active=true;var calls=0
        val late=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;started.complete(Unit);release.await();return TextChatResult.Success("晚到观察")}}
        val route=ChatImageRoute(store,vault,late,t,config("chat","https://example.invalid/v1"),AppMutationGate.epoch()){active}
        val run=async(Dispatchers.IO){runCatching{route.complete(config("chat","https://example.invalid/v1"),"synthetic-key-only",request(t))}}
        started.await();ChatImages.setEnabled(store.writableDatabase,false);ChatImages.setEnabled(store.writableDatabase,true);active=false;release.complete(Unit)
        assertEquals(ChatFailure.SOURCE_CHANGED,(run.await().exceptionOrNull() as CompanionModelFailure).failure);assertEquals(1,calls)
        assertEquals(0,repo.read{db->db.rawQuery("SELECT count(*) FROM image_observations",null).use{it.moveToFirst();it.getInt(0)}})
    }
    @Test fun switchAndConfigurationRoundTripsIndependentlyRevokeImageAttempts():Unit=runBlocking{
        configure("https://example.invalid/v1",true);val t=ledger.begin("a","合成图片","chat",image=image())
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("合成观察")}
        val first=ChatImageRoute(store,vault,client,t,config("chat","https://example.invalid/v1"),AppMutationGate.epoch()){true}
        first.complete(config("chat","https://example.invalid/v1"),"synthetic-key-only",request(t))
        ChatImages.setEnabled(store.writableDatabase,false);ChatImages.setEnabled(store.writableDatabase,true);assertFalse(first.current())
        val second=ChatImageRoute(store,vault,client,t,config("chat","https://example.invalid/v1"),AppMutationGate.epoch()){true}
        second.complete(config("chat","https://example.invalid/v1"),"synthetic-key-only",request(t))
        val original=vault.list().first{it.id=="vision"};vault.save(original.copy(modelId="other"),"synthetic-key-only");vault.save(original,"synthetic-key-only");assertFalse(second.current())
        assertFalse(ledger.recordSpoken(t.id,1,"不应晚到写入",AppMutationGate.epoch(),second::current));assertEquals(1,store.messages().size)
    }
    @Test fun pictureIdsCannotAliasDifferentPixelsAndRetractedPicturesDisappear():Unit=runBlocking{
        val chosen=image();val t=ledger.begin("a","","chat",id="same-picture-id",image=chosen)
        assertEquals(t,ledger.begin("a","","chat",id="same-picture-id",image=chosen))
        val other=ChatImages.prepare(pixels(Bitmap.CompressFormat.JPEG).inputStream())
        assertTrue(runCatching{ledger.begin("a","","chat",id="same-picture-id",image=other)}.isFailure)
        assertTrue(ledger.finish(t.id,1,"合成回复"));val source=repo.sources("a").single()
        assertEquals(chosen.hash,source.user.image!!.hash);assertEquals(chosen.hash,SourceDialogue.from(source.json()).user.image!!.hash)
        SourceChanges(repo).confirm(SourceChanges(repo).preview("a",t.userMessageId,"user","",true))
        assertNull(store.messages().first{it.id==t.userMessageId}.image)
        store.close();store=Store(context,name);repo=ProcessingRepository(store);ledger=ChatLedger(store)
        assertNull(store.messages().first{it.id==t.userMessageId}.image)
    }
    @Test fun legacyRecordReplaceRemovesChatPicturesAndPreservesUnrelatedAvatar():Unit=runBlocking{
        val t=ledger.begin("a","合成图片原话","chat",image=image());assertTrue(ledger.finish(t.id,1,"合成回复"))
        RoleAssets(repo).put("a",RoleAssets.Asset("avatar.bin","avatar","application/octet-stream",byteArrayOf(1,2,3)))
        val legacy=RoleArchiveRepository(store)
        assertTrue(runCatching{legacy.export("a",true)}.isFailure)
        val old=RoleArchive.validate(legacy.export("b",true).toString().toByteArray())
        legacy.commit(legacy.preview(old,"a"),true)
        assertTrue(store.messages().none{it.friendId=="a"})
        assertEquals(listOf("avatar.bin"),RoleAssets(repo).list("a").map{it.path})
        val exported=BackupRepository(store,vault).export("a");assertEquals(1,exported.files.size)
    }
    @Test fun imageObservationMemorySurvivesBackupAndImageSourceChangesInvalidateLockedEvidence():Unit=runBlocking{
        configure("https://example.invalid/v1",true)
        val first=ledger.begin("a","这是一张合成花图","chat",image=image());assertTrue(ledger.finish(first.id,1,"合成主体回复"))
        val second=ledger.begin("a","右上角有什么？","chat")
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest)=TextChatResult.Success("图中右上角有蓝色三角形和数字7")}
        val route=ChatImageRoute(store,vault,client,second,config("chat","https://example.invalid/v1"),AppMutationGate.epoch()){true}
        route.complete(config("chat","https://example.invalid/v1"),"synthetic-key-only",request(second));assertTrue(ledger.finishWithMemory(second.id,1,"合成回答",onCompleted={route.commit(it)}))
        val source=repo.sources("a").last();val observation=source.imageObservations.single();val batches=MemoryBatchRepository(repo)
        val work=batches.prepare("a",listOf(source),"synthetic-config");batches.beginAttempt(work,"synthetic")
        val op=JSONObject().put("id","img_memory").put("target","new:image").put("op","ADD").put("category","EXPERIENCE_MEMORY").put("about","CHARACTER").put("text","我在对方分享的图里看到了蓝色三角形和数字7").put("core",false).put("evidence",org.json.JSONArray().put(JSONObject().put("turn",source.source.turnId).put("version",source.source.version).put("side","image").put("quote",observation.text).put("observation_id",observation.evidence.observationId)))
        assertTrue(MemoryOperationFormat.issues(op,1).isEmpty())
        val id=batches.apply(work,op);assertTrue(batches.complete(work,setOf("img_memory"),1,work.id));repo.setLock("a",id,true)
        val saved=repo.memories("a").single();assertEquals("image",saved.evidence.single().side);assertEquals(first.userMessageId,saved.evidence.single().image!!.imageSource.turnId)
        val backup=BackupRepository(store,vault)
        val future=backup.export("a");future.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject["image_observations"].asJsonArray[0].asJsonObject.addProperty("question_revision",99)
        assertTrue(runCatching{backup.preview(pack(future),"ADD")}.isFailure)
        val restoredId=backup.commit(backup.preview(pack(backup.export("a")),"ADD")).single()
        val restored=repo.memories(restoredId).single();assertTrue(repo.read{repo.evidenceValid(it,restored.evidence.single())});assertEquals(restoredId,restored.evidence.single().image!!.imageSource.characterId)
        assertNotEquals(first.userMessageId,restored.evidence.single().image!!.imageSource.turnId)
        repo.manualSave(restoredId,restored.id,"人工历史文字",MemoryCategory.EXPERIENCE_MEMORY,false,false,MemoryAbout.CHARACTER)
        val manualVersion=repo.memories(restoredId).single().version
        CompanionRepository(store).forget(restoredId,restored.evidence.single().image!!.imageSource.turnId)
        assertTrue(runCatching{repo.restore(restoredId,restored.id,manualVersion)}.isFailure)
        val impact=SourceChanges(repo).preview("a",first.userMessageId,"user","修改合成附文",false);assertEquals(listOf(id),impact.memories.map{it.id})
        SourceChanges(repo).confirm(impact);val invalid=repo.memories("a").single();assertFalse(invalid.valid);assertTrue(invalid.locked);assertTrue(repo.candidates("a").isNotEmpty())
    }
    private fun pack(e:BackupRepository.Export)=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(e.root,e.files,it)}.toByteArray().inputStream())
    @Test fun imageBackupRemapsMessagesIncludesSwitchAndRejectsTamperingOrCancelledRestore():Unit=runBlocking{
        val chosen=image();val t=ledger.begin("a","合成图附文","chat",image=chosen);assertTrue(ledger.finish(t.id,1,"合成图片回复"))
        ChatImages.setEnabled(store.writableDatabase,true);val backup=BackupRepository(store,vault);val export=backup.export("a");assertFalse(export.root["preferences"].asJsonObject.has(ChatImages.SWITCH))
        val imported=backup.commit(backup.preview(pack(export),"ADD")).single();assertNotEquals("a",imported)
        val restored=store.messages().single{it.friendId==imported&&it.mine};assertNotEquals(t.userMessageId,restored.id);assertEquals(chosen.hash,restored.image!!.hash);assertEquals(chosen.hash,RoleArchive.hash(ChatImages.read(store.readableDatabase,restored.image!!)))
        assertTrue(ChatImages.enabled(store.readableDatabase))
        val old=backup.export("b");old.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject.apply{remove("chat_images");remove("image_observations")}
        assertEquals(1,backup.commit(backup.preview(pack(old),"ADD")).size)
        val damaged=backup.export("a");damaged.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject["chat_images"].asJsonArray[0].asJsonObject.addProperty("hash","0".repeat(64))
        assertTrue(runCatching{backup.preview(pack(damaged),"ADD")}.isFailure)
        val before=store.friends();val lease=ChatAttemptLease();lease.cancel()
        assertTrue(runCatching{backup.commit(backup.preview(pack(export),"ADD"),lease=lease)}.isFailure);assertEquals(before,store.friends())
        val all=backup.export();assertEquals("1",all.root["preferences"].asJsonObject[ChatImages.SWITCH].asString)
        all.root["preferences"].asJsonObject.remove(ChatImages.SWITCH);all.root["preferences"].asJsonObject.remove(ChatImages.SWITCH_REVISION)
        backup.commit(backup.preview(pack(all),"REPLACE"),true);assertFalse(ChatImages.enabled(store.readableDatabase))
    }
    @Test fun runnerSendsPureImageAndRetryKeepsTheOriginalPicture():Unit=runBlocking{
        configure("https://example.invalid/v1");var reject=true;val hashes=Collections.synchronizedList(mutableListOf<String>())
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            request.messages.flatMap{it.images}.forEach{hashes+=RoleArchive.hash(it.bytes)}
            return if(reject)TextChatResult.Failure(ChatFailure.REJECTED,415,"HTTP 415 合成不支持图片")else TextChatResult.Success("合成图片回复")
        }}
        val memory=MemoryEngine(scope,repo,ModelAccess(vault),client,{})
        val runner=ConversationRunner(scope,store,vault,client,repo,memory,{},{})
        val chosen=image();withContext(Dispatchers.Main){assertTrue(runner.sendImage("a","",chosen) {});chosen.close();chosen.close()}
        withTimeout(20000){while(ledger.all().lastOrNull()?.state!=TurnState.FAILED)delay(20)}
        val failed=ledger.all().last();assertFalse(chosen.source.file!!.exists());assertEquals(ChatFailure.REJECTED,failed.failure);assertEquals("",store.messages().first{it.id==failed.userMessageId}.text)
        reject=false;withContext(Dispatchers.Main){withTimeout(5000){while("a" in runner.sending)delay(10)};runner.retry(failed)}
        withTimeout(20000){while(ledger.all().last().state!=TurnState.COMPLETED)delay(20)}
        assertEquals(2,ledger.all().last().attempt);assertTrue(hashes.size>=2&&hashes.all{it==chosen.hash});assertEquals(1,store.messages().count{it.mine&&it.friendId=="a"})
    }
    @Test fun realRunnerStreamingTransportKeepsBothFollowupRoutes():Unit=runBlocking{
        for(aux in listOf(false,true)){
            val role=if(aux)"b"else"a";val chosen=image();val bodies=Collections.synchronizedList(mutableListOf<String>())
            TextTlsMock(maxRequests=if(aux)4 else 2,responder={raw->
                bodies+=raw;val model=JSONObject(raw).getString("model");val detail=raw.contains("符号和数字")
                val hasPicture=decodedHashes(raw)==listOf(chosen.hash)
                success(if(model=="vision")if(detail&&hasPicture)"蓝色三角形和数字7"else"黄色的花"else if((!aux&&detail&&hasPicture)||(aux&&raw.contains("蓝色三角形和数字7")))"蓝色三角形和数字7"else"黄色的花")
            }).use{server->
                configure(server.base,aux);val client=server.client();val memory=MemoryEngine(scope,repo,ModelAccess(vault),client,{})
                val runner=ConversationRunner(scope,store,vault,client,repo,memory,{},{})
                suspend fun send(text:String,picture:PendingChatImage?):ChatTurn{
                    val before=ledger.all().size;withContext(Dispatchers.Main){assertTrue(runner.sendImage(role,text,picture){})}
                    withTimeout(20000){while(ledger.all().size<=before||ledger.all().last().state==TurnState.PENDING||role in runner.sending)delay(20)}
                    return ledger.all().last().also{assertEquals(it.toString(),TurnState.COMPLETED,it.state)}
                }
                send("先说主体",chosen);send("右上角的符号和数字是什么？",null)
                assertTrue(store.messages().last{it.friendId==role&&!it.mine}.text.contains("蓝色三角形和数字7"))
                val chat=bodies.filter{JSONObject(it).getString("model")=="chat"};val vision=bodies.filter{JSONObject(it).getString("model")=="vision"}
                assertEquals(2,chat.size);assertTrue(chat.all{JSONObject(it).getBoolean("stream")})
                if(aux){assertEquals(2,vision.size);assertTrue(chat.all{decodedHashes(it).isEmpty()});assertTrue(vision.all{decodedHashes(it)==listOf(chosen.hash)})}
                else{assertTrue(vision.isEmpty());assertTrue(chat.all{decodedHashes(it)==listOf(chosen.hash)})}
                println("IMAGE_RUNNER_TWO_TURN auxiliary=$aux chat=${chat.size} aux=${vision.size} chatHashes=${chat.map{decodedHashes(it)}}")
            }
        }
    }
    @Test fun auxiliaryRouteInheritsOutputStreamingAndThinkingConfig():Unit=runBlocking{
        val chosen=image()
        for(stream in listOf(true,false)){
            val expectedOutput=if(stream)3072 else 4096
            val expectedEffort=if(stream)"low" else "high"
            val longObservation="可见细节。".repeat(1700) // > 8000 chars, still within chat context
            val bodies=Collections.synchronizedList(mutableListOf<JSONObject>())
            TextTlsMock(
                maxRequests=2,
                contentType=if(stream)"text/event-stream" else "application/json",
                responder={raw->
                    val j=JSONObject(raw);bodies+=j
                    val content=if(j.getString("model")=="vision")longObservation else "主模型收到观察"
                    if(stream){
                        val delta=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("index",0).put("delta",JSONObject().put("content",content))))
                        val done=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("index",0).put("delta",JSONObject()).put("finish_reason","stop")))
                        "data: $delta\n\ndata: $done\n\ndata: [DONE]\n\n"
                    }else success(content)
                }
            ).use{server->
                val chatCfg=config("chat",server.base).copy(streamReplies=stream)
                val visionCfg=ModelConfig("vision","合成辅助看图","custom",server.base,"vision",contextTokens=128000,outputTokens=expectedOutput,thinkingCustom="""{"reasoning_effort":"$expectedEffort"}""",streamReplies=stream)
                vault.save(chatCfg,"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,"chat")
                vault.save(visionCfg,"synthetic-key-only");vault.setPurpose(ModelPurpose.AUX_VISION,"vision")
                ChatImages.setEnabled(store.writableDatabase,true)
                val turn=ledger.begin("a","帮我看看图里细节（stream=$stream）","chat",image=chosen)
                val route=ChatImageRoute(store,vault,server.client(),turn,chatCfg,AppMutationGate.epoch()){true}
                val result=if(stream)route.streamEvents(chatCfg,"synthetic-key-only",request(turn)){} else route.complete(chatCfg,"synthetic-key-only",request(turn))
                assertEquals("主模型收到观察",(result as TextChatResult.Success).text)
                assertTrue(ledger.finishWithMemory(turn.id,1,result.text,onCompleted={route.commit(it)}))
                val visionBody=bodies.single{it.getString("model")=="vision"}
                assertEquals(expectedOutput,visionBody.getInt("max_tokens"))
                assertEquals(stream,visionBody.getBoolean("stream"))
                assertEquals(expectedEffort,visionBody.getString("reasoning_effort"))
            }
        }
        // Truncated auxiliary vision call should report the auxiliary stage and configured output limit.
        TextTlsMock(maxRequests=1,responder={
            JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason","length").put("message",JSONObject().put("content","只看了一半")))).toString()
        }).use{server->
            val chatCfg=config("chat",server.base).copy(streamReplies=false)
            val visionCfg=ModelConfig("vision","合成辅助看图","custom",server.base,"vision",contextTokens=128000,outputTokens=3072,streamReplies=false)
            vault.save(chatCfg,"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,"chat")
            vault.save(visionCfg,"synthetic-key-only");vault.setPurpose(ModelPurpose.AUX_VISION,"vision")
            ChatImages.setEnabled(store.writableDatabase,true)
            val turn=ledger.begin("a","测截断提示","chat",image=chosen)
            val route=ChatImageRoute(store,vault,server.client(),turn,chatCfg,AppMutationGate.epoch()){true}
            val err=runCatching{route.complete(chatCfg,"synthetic-key-only",request(turn))}.exceptionOrNull() as CompanionModelFailure
            assertEquals(ChatFailure.TRUNCATED,err.failure)
            assertTrue(err.detail,err.detail.contains("辅助看图"))
            assertTrue(err.detail,err.detail.contains("3072"))
        }
    }
}
