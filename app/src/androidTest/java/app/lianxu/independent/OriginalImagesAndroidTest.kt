package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Synthetic originals only. No real user data, credentials, devices or external models. */
class OriginalImagesAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var vault:ModelVault;private lateinit var ledger:ChatLedger;private lateinit var database:String
    @Before fun setup()=runBlocking{database="originals-${System.nanoTime()}.db";store=Store(context,database);ledger=ChatLedger(store);store.add(listOf(Friend("r","合成原图角色","合成测试")),false);vault=ModelVault(context,"originals-${System.nanoTime()}",store)}
    @After fun cleanup(){vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(database)}
    private fun config(id:String="chat",capacity:Int=128000,base:String="https://example.invalid/v1")=ModelConfig(id,"合成$id","custom",base,id,capacity,2048)
    private fun configure(aux:Boolean=false,capacity:Int=128000){vault.save(config(capacity=capacity),"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,"chat");vault.save(config("vision",capacity),"synthetic-key-only");vault.setPurpose(ModelPurpose.AUX_VISION,"vision");ChatImages.setEnabled(store.writableDatabase,aux)}
    private fun request(t:ChatTurn,refs:List<ChatImageRef>)=TextChatRequest(listOf(WireMessage("user",runBlocking{ledger.context(t.id)}.input,imageRefs=refs)))
    private fun route(t:ChatTurn,client:TextChatClient,capacity:Int=128000)=ChatImageRoute(store,vault,client,t,config(capacity=capacity),AppMutationGate.epoch()){true}
    private fun countObservations()=store.readableDatabase.rawQuery("SELECT count(*) FROM image_observations",null).use{it.moveToFirst();it.getInt(0)}
    private fun noTemps(){val parent=File(context.cacheDir,"chat-image-wire");assertTrue(parent.listFiles().orEmpty().isEmpty())}
    private fun evidence(name:String,fields:Map<String,Any>){val dir=File(context.getExternalFilesDir(null),"original-image-evidence").apply{mkdirs()};File(dir,"$name.json").writeText(org.json.JSONObject(fields).toString(2))}

    @Test fun exactByteBoundaryPreservesOriginalsAndOnlyAboveBoundaryCreatesParts():Unit=runBlocking{
        configure()
        for(size in listOf(19_999_999,20_000_000,20_000_001)){
            val raw=OriginalImageFixtures.paddedJpeg(size);val image=ChatImages.prepare(raw.inputStream());assertArrayEquals(raw,image.bytes)
            val t=ledger.begin("r","边界$size","chat",image=image);val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!
            assertEquals(size,ChatImages.read(store.readableDatabase,ref).size);assertEquals(RoleArchive.hash(raw),ref.hash)
            ImageParts.prepare(context,listOf(ref),{ChatImages.read(store.readableDatabase,it)},{ }).use{plan->
                assertEquals(if(size<=20_000_000)1 else 2,plan.parts.size)
                if(size<=20_000_000)assertArrayEquals(raw,plan.parts.single().wire().bytes)
                else{assertTrue(plan.parts.all{it.mime=="image/png"});assertTrue(plan.parts.last().overview);assertTrue(plan.parts.first().label.contains("[0,0,120,100)"))}
                evidence("boundary-$size",mapOf("sourceBytes" to size,"storedHash" to ref.hash,"originalByteEquality" to true,"parts" to plan.parts.size,"overviewLast" to plan.parts.last().overview))
            }
            noTemps();assertTrue(ledger.finishWithMemory(t.id,1,"合成结束",onCompleted={}))
        }
    }
    @Test fun transparentCommonFormatsStayByteExactAndUnsupportedOrAnimatedIsRejected(){
        for(format in listOf(Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.WEBP_LOSSLESS)){
            val raw=OriginalImageFixtures.picture(format=format);val result=ChatImages.prepare(raw.inputStream());assertArrayEquals(raw,result.bytes)
            val b=ImagePixels.preview(result.bytes,512);if(format!=Bitmap.CompressFormat.JPEG)assertEquals(0,android.graphics.Color.alpha(b.getPixel(110,70)));b.recycle()
        }
        for(bytes in listOf(byteArrayOf(1,2,3),"II*\u0000fake-tiff".toByteArray(),java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==")))assertTrue(runCatching{ChatImages.prepare(bytes.inputStream())}.isFailure)
    }
    @Test fun sixteenBitOriginalIsPreservedButOversizeNeverSilentlyBecomesEightBit(){
        val small=OriginalImageFixtures.png16();assertArrayEquals(small,ChatImages.prepare(small.inputStream()).bytes)
        assertTrue(runCatching{ChatImages.prepare(OriginalImageFixtures.png16(animated=true).inputStream())}.isFailure)
        val raw=OriginalImageFixtures.png16(20_000_000);val info=ChatImages.inspect(raw);assertTrue(raw.size>20_000_000)
        val ref=ChatImageRef("r",1,"synthetic16.png",info.mime,RoleArchive.hash(raw),info.width,info.height,storageKind="original")
        val failure=runCatching{ImageParts.prepare(context,listOf(ref),{raw},{ })}.exceptionOrNull();assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("16位PNG"));noTemps()
    }
    @Test fun exifAndSyntheticGpsRemainInOriginalAndAllEightDisplayOrientationsAreCorrect(){
        val file=File(context.cacheDir,"synthetic-exif-${System.nanoTime()}.jpg")
        try{file.writeBytes(OriginalImageFixtures.picture(format=Bitmap.CompressFormat.JPEG));ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");setAttribute(ExifInterface.TAG_GPS_LATITUDE,"30/1,0/1,0/1");setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N");setAttribute(ExifInterface.TAG_ARTIST,"synthetic-only");saveAttributes()}
            val raw=file.readBytes();val image=ChatImages.prepare(raw.inputStream());assertArrayEquals(raw,image.bytes);assertEquals("synthetic-only",ExifInterface(image.bytes.inputStream()).getAttribute(ExifInterface.TAG_ARTIST))
            val info=ChatImages.inspect(raw);assertEquals(6,info.orientation);val preview=ImagePixels.preview(raw,512);assertEquals(100,preview.width);assertEquals(120,preview.height);preview.recycle()
            val region=ImagePixels.viewRegion(raw,info,RectF(0f,0f,100f,120f));assertEquals(100,region.bitmap.width);assertEquals(120,region.bitmap.height);region.bitmap.recycle()
        }finally{file.delete()}
        for(o in 1..8){val bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);for(y in 0..1)for(x in 0..2)bitmap.setPixel(x,y,android.graphics.Color.rgb(x*50,y*70,10))
            val rendered=ImagePixels.oriented(bitmap,o)
            for(y in 0 until rendered.height)for(x in 0 until rendered.width){val source=when(o){1->x to y;2->2-x to y;3->2-x to 1-y;4->x to 1-y;5->y to x;6->y to 1-x;7->2-y to 1-x;else->2-y to x};assertEquals("orientation $o",android.graphics.Color.rgb(source.first*50,source.second*70,10),rendered.getPixel(x,y))};rendered.recycle()
        }
    }
    @Test fun oversizedImageTilesRetainNativePixelsCoordinatesOverlapAndOverviewLast():Unit=runBlocking{
        val raw=OriginalImageFixtures.paddedJpeg(20_000_001,2300,5000);val image=ChatImages.prepare(raw.inputStream());val t=ledger.begin("r","切块","chat",image=image);val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!
        ImageParts.prepare(context,listOf(ref),{ChatImages.read(store.readableDatabase,it)},{ }).use{plan->
            val regions=ImagePolicy.regions(2300,5000);assertEquals(regions.size+1,plan.parts.size);assertTrue(plan.parts.last().overview)
            val source=BitmapRegionDecoder.newInstance(raw,0,raw.size,false)!!
            try{regions.forEachIndexed{i,r->val block=plan.parts[i];assertTrue(block.label.contains("局部${i+1}/${regions.size}"));assertTrue(block.label.contains("[${r.left},${r.top},${r.right},${r.bottom})"))
                val bytes=block.wire().bytes;val actual=BitmapFactory.decodeByteArray(bytes,0,bytes.size);val expected=source.decodeRegion(Rect(r.left,r.top,r.right,r.bottom),BitmapFactory.Options())!!
                assertEquals(r.width,actual.width);assertEquals(r.height,actual.height)
                for((x,y) in listOf(0 to 0,(r.width-1) to (r.height-1),(r.width/2) to (r.height/2)))assertEquals(expected.getPixel(x,y),actual.getPixel(x,y))
                actual.recycle();expected.recycle()
            }}finally{source.recycle()}
        };noTemps()
    }
    @Test fun unknownExifDirectionDoesNotInventARotationOrRewriteOriginal(){
        val file=File(context.cacheDir,"synthetic-unknown-direction-${System.nanoTime()}.jpg")
        try{file.writeBytes(OriginalImageFixtures.picture(format=Bitmap.CompressFormat.JPEG));ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"9");saveAttributes()}
            val raw=file.readBytes();val image=ChatImages.prepare(raw.inputStream());assertArrayEquals(raw,image.bytes);assertEquals(1,ChatImages.inspect(raw).orientation)
            val preview=ImagePixels.preview(raw,512);assertEquals(120,preview.width);assertEquals(100,preview.height);preview.recycle()
        }finally{file.delete()}
    }
    @Test fun oversizedTransparentPngDoesNotLoseAlphaOrUnpremultipliedPixelsInLosslessTiles(){
        val raw=OriginalImageFixtures.noisyTransparentPng();assertTrue("fixture=${raw.size}",raw.size>20_000_000);assertTrue(raw.size<40_000_000)
        val info=ChatImages.inspect(raw);val ref=ChatImageRef("r",1,"synthetic.png",info.mime,RoleArchive.hash(raw),info.width,info.height,storageKind="original")
        ImageParts.prepare(context,listOf(ref),{raw},{ }).use{plan->
            val source=BitmapRegionDecoder.newInstance(raw,0,raw.size,false)!!
            try{ImagePolicy.regions(info.width,info.height).forEachIndexed{i,r->val bytes=plan.parts[i].wire().bytes
                val expected=source.decodeRegion(Rect(r.left,r.top,r.right,r.bottom),BitmapFactory.Options().apply{inPremultiplied=false})!!
                val actual=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inPremultiplied=false})!!
                for(y in 0 until actual.height step 67)for(x in 0 until actual.width step 61)assertEquals("tile $i pixel $x,$y",expected.getPixel(x,y),actual.getPixel(x,y))
                actual.recycle();expected.recycle()
            }}finally{source.recycle()}
            assertTrue(plan.parts.last().overview)
            evidence("transparent-png-tiles",mapOf("sourceBytes" to raw.size,"width" to info.width,"height" to info.height,"parts" to plan.parts.size,"unpremultipliedSamplePixelsEqual" to true,"overviewLast" to true))
        };noTemps()
    }
    @Test fun exifTilingUsesUprightReadingOrderAndRetainsRawCoordinates(){
        val file=File(context.cacheDir,"synthetic-direction-${System.nanoTime()}.jpg")
        try{file.writeBytes(OriginalImageFixtures.picture(120,7000,Bitmap.CompressFormat.JPEG));ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
            val raw=OriginalImageFixtures.padJpeg(file.readBytes(),20_000_001);val info=ChatImages.inspect(raw);val ref=ChatImageRef("r",1,"synthetic.jpg",info.mime,RoleArchive.hash(raw),info.width,info.height,storageKind="original")
            ImageParts.prepare(context,listOf(ref),{raw},{ }).use{plan->
                val regions=ImagePolicy.regions(7000,120);assertEquals(regions.size+1,plan.parts.size)
                regions.forEachIndexed{i,r->assertTrue(plan.parts[i].label.contains("正向坐标[${r.left},${r.top},${r.right},${r.bottom})"));val bytes=plan.parts[i].wire().bytes;val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size);assertEquals(r.width,bitmap.width);assertEquals(r.height,bitmap.height);bitmap.recycle()}
                assertTrue(plan.parts.first().label.contains("原始坐标[0,4952,120,7000)"));assertTrue(plan.parts.last().overview)
            }
        }finally{file.delete()};noTemps()
    }
    @Test fun actualHttpsTwentyMbOriginalUsesExactCountAndStreamingWithoutWholeBase64Copy():Unit=runBlocking{
        val raw=OriginalImageFixtures.paddedJpeg(20_000_000);var received=0;var receivedHash=""
        TextTlsMock(maxBodyBytes=ImagePolicy.REQUEST_LIMIT,requestSink={input,size->val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);var left=size;while(left>0){val n=input.read(buffer,0,minOf(buffer.size,left));check(n>0);digest.update(buffer,0,n);left-=n};received=size;receivedHash=digest.digest().joinToString(""){"%02x".format(it)}}).use{server->
            val req=TextChatRequest(listOf(WireMessage("user","合成原件",images=listOf(WireImage("image/jpeg",raw)))))
            val c=config(base=server.base);val body=ImageWire.body(c,req,false);val digest=MessageDigest.getInstance("SHA-256");ImageWire.write(body,req,DigestOutputStream(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},digest))
            val expected=digest.digest().joinToString(""){"%02x".format(it)}
            assertTrue(server.client(30000).complete(c,"synthetic-key-only",req) is TextChatResult.Success);assertEquals(ImageWire.size(body,req),received.toLong());assertEquals(expected,receivedHash);assertEquals(1,server.calls.get());assertTrue(received>26_666_668)
            println("ORIGINAL_HTTP bytes=${raw.size} requestBytes=$received digestMatches=true")
            evidence("original-http",mapOf("sourceBytes" to raw.size,"requestBytes" to received,"receivedBodySha256" to receivedHash,"expectedBodySha256" to expected,"calls" to server.calls.get(),"transport" to "actual-loopback-TLS"))
        }
    }
    @Test fun requestTotalTriggersCompleteBatchesOnSameRouteAndFollowupRereadsBothOriginals():Unit=runBlocking{
        for(aux in listOf(false,true)){
            configure(aux);val refs=mutableListOf<ChatImageRef>()
            repeat(2){val image=ChatImages.prepare(OriginalImageFixtures.paddedJpeg(13_000_000+it).inputStream());val t=ledger.begin("r","原图$it","chat",image=image);refs.add(ChatImages.ref(store.readableDatabase,t.userMessageId)!!);assertTrue(ledger.finishWithMemory(t.id,1,"前轮合成结束",onCompleted={}))}
            val expected=refs.map{it.hash};val seen=mutableListOf<Pair<String,List<String>>>()
            val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
                val hashes=request.messages.flatMap{it.images}.map{RoleArchive.hash(it.bytes)};seen.add(config.id to hashes)
                if(hashes.isEmpty()){assertTrue(request.messages.any{m->expected.all{it in m.content}});return TextChatResult.Success("蓝色标记和底部红线")}
                assertEquals(if(aux)"vision"else"chat",config.id);assertEquals(1,hashes.size);return TextChatResult.Success("${hashes.single()}：蓝色标记和底部红线")
            }}
            repeat(2){round->val t=ledger.begin("r",if(round==0)"先说主体"else"追问底部此前未说的细节","chat");val r=route(t,client);assertTrue(r.complete(config(),"synthetic-key-only",request(t,refs)) is TextChatResult.Success);assertTrue(ledger.finishWithMemory(t.id,1,"合成回答",onCompleted={r.commit(it)}))}
            assertEquals(6,seen.size);assertEquals(expected+expected,seen.flatMap{it.second});assertEquals(2,seen.count{it.second.isEmpty()});assertTrue(seen.filter{it.second.isNotEmpty()}.all{it.first==if(aux)"vision"else"chat"});noTemps()
            println("ORIGINAL_BATCH auxiliary=$aux calls=${seen.size} imageCalls=4 finalCalls=2 nextQuestionRereads=true")
            evidence("original-batches-$aux",mapOf("auxiliary" to aux,"sourceHashes" to expected,"imageCalls" to 4,"finalCalls" to 2,"calls" to seen.size,"transport" to "synthetic-client","nextQuestionRereadsBoth" to true))
        }
    }
    @Test fun tileBatchFailureNeverBecomesACompleteAnswerAndManualRetryDoesNotDuplicateMessage():Unit=runBlocking{
        configure(capacity=8192);val image=ChatImages.prepare(OriginalImageFixtures.paddedJpeg(20_000_001,300,14000).inputStream());val t=ledger.begin("r","底部细节","chat",image=image);val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!
        var calls=0;var finals=0;var fail=true;val labels=mutableListOf<String>()
        val client=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{
            calls++;val images=request.messages.flatMap{it.images};if(images.isEmpty()){finals++;return TextChatResult.Success("完整合成结果")}
            labels.addAll(images.map{it.description});if(fail&&calls==2)return TextChatResult.Failure(ChatFailure.REJECTED,400,"HTTP 400 synthetic unsupported schema")
            return TextChatResult.Success("本批可见底部红线")
        }}
        val rejected=runCatching{route(t,client,8192).complete(config(capacity=8192),"synthetic-key-only",request(t,listOf(ref)))}.exceptionOrNull() as CompanionModelFailure
        assertEquals(ChatFailure.REJECTED,rejected.failure);assertTrue(rejected.detail.contains("400"));assertEquals(2,calls);assertEquals(0,finals);assertEquals(0,countObservations());noTemps()
        assertTrue(ledger.fail(t.id,1,rejected.failure,rejected.detail));val retry=ledger.retry(t.id,"chat")!!;fail=false;labels.clear();val r=route(retry,client,8192)
        assertTrue(r.complete(config(capacity=8192),"synthetic-key-only",request(retry,listOf(ref))) is TextChatResult.Success);assertTrue(ledger.finishWithMemory(retry.id,retry.attempt,"完整结果",onCompleted={r.commit(it)}))
        assertEquals(1,store.messages().count{it.mine});assertEquals(1,finals);assertEquals(ImagePolicy.regions(300,14000).size+1,labels.size);assertTrue(labels.last().contains("最后一张"));assertEquals(1,countObservations());noTemps()
    }
    @Test fun completeBackupKeepsLargeOriginalMetadataAndOldJpegCompatibility():Unit=runBlocking{
        configure();val raw=OriginalImageFixtures.paddedJpeg(20_000_001);val image=ChatImages.prepare(raw.inputStream());val t=ledger.begin("r","备份原件","chat",image=image);ledger.finishWithMemory(t.id,1,"结束",onCompleted={})
        val backup=BackupRepository(store,vault);val export=backup.export("r");val zip=File(context.cacheDir,"original-backup-${System.nanoTime()}.zip")
        try{zip.outputStream().use{BackupArchive.write(export.root,export.files,it)};val pack=zip.inputStream().use{BackupArchive.read(it)};val restored=backup.commit(backup.preview(pack,"ADD"),true).single();val ref=store.messages().single{it.friendId==restored&&it.mine}.image!!;assertArrayEquals(raw,ChatImages.read(store.readableDatabase,ref));assertNotEquals(t.userMessageId,ref.messageId)}finally{zip.delete()}
        val old=OriginalImageFixtures.picture(format=Bitmap.CompressFormat.JPEG);val legacy=ChatImages.prepare(old.inputStream());val next=ledger.begin("r","历史JPEG","chat",image=legacy);ledger.finishWithMemory(next.id,1,"旧副本仍可读",onCompleted={})
        assertArrayEquals(old,ChatImages.read(store.readableDatabase,ChatImages.ref(store.readableDatabase,next.userMessageId)!!))
    }
    @Test fun version17AndOldBackupWithoutOriginalFlagRemainLegacyCopies():Unit=runBlocking{
        val old=OriginalImageFixtures.picture(format=Bitmap.CompressFormat.JPEG);val t=ledger.begin("r","旧JPEG","chat",image=ChatImages.prepare(old.inputStream()));ledger.finishWithMemory(t.id,1,"旧结束",onCompleted={})
        store.writableDatabase.execSQL("UPDATE chat_images SET storage_kind='legacy-normalized'")
        val backup=BackupRepository(store,vault);val export=backup.export("r");export.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject["chat_images"].asJsonArray[0].asJsonObject.remove("storage_kind")
        val pack=BackupArchive.Package(export.root,export.files,"synthetic-legacy");val added=backup.commit(backup.preview(pack,"ADD"),true).single()
        val ref=store.messages().single{it.friendId==added&&it.mine}.image!!;assertEquals("legacy-normalized",ref.storageKind);assertArrayEquals(old,ChatImages.read(store.readableDatabase,ref))
        store.close();android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(database).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("ALTER TABLE chat_images DROP COLUMN storage_kind");it.version=17}
        val namespace=vault.statusNamespace;store=Store(context,database);vault=ModelVault(context,namespace,store);ledger=ChatLedger(store);assertEquals(18,store.readableDatabase.version);assertEquals("legacy-normalized",ChatImages.ref(store.readableDatabase,t.userMessageId)!!.storageKind);assertEquals(2,store.messages().count{it.mine})
    }
    @Test fun wholePlanCapsCleanPrivateFilesInsteadOfReturningAnIncompletePlan(){
        val small=OriginalImageFixtures.picture();val refs=(1L..65).map{ChatImageRef("r",it,"$it.png","image/png",RoleArchive.hash(small),120,100,storageKind="original")}
        assertTrue(runCatching{ImageParts.prepare(context,refs,{small},{ })}.isFailure);noTemps()
        val raw=OriginalImageFixtures.paddedJpeg(20_000_000);val largeRefs=refs.take(6).map{it.copy(mime="image/jpeg",hash=RoleArchive.hash(raw))}
        val failure=runCatching{ImageParts.prepare(context,largeRefs,{raw},{ })}.exceptionOrNull();assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("96 MiB"));noTemps()
    }
    @Test fun actualTlsTileBatchesBothRoutesRereadEveryPartForNewDetailQuestion():Unit=runBlocking{
        for(aux in listOf(false,true)){
            val image=ChatImages.prepare(OriginalImageFixtures.paddedJpeg(20_000_001,300,14000).inputStream())
            var refs=listOf<ChatImageRef>();val seen=mutableListOf<Pair<String,List<String>>>();val questions=mutableListOf<Boolean>()
            fun success(text:String)=org.json.JSONObject().put("choices",org.json.JSONArray().put(org.json.JSONObject().put("finish_reason","stop").put("message",org.json.JSONObject().put("content",text)))).toString()
            TextTlsMock(maxRequests=32,responder={raw->
                val body=org.json.JSONObject(raw);val messages=body.getJSONArray("messages");val hashes=mutableListOf<String>()
                for(i in 0 until messages.length()){val blocks=messages.getJSONObject(i).optJSONArray("content")?:continue;for(j in 0 until blocks.length()){val block=blocks.getJSONObject(j);if(block.optString("type")=="image_url")hashes.add(RoleArchive.hash(java.util.Base64.getDecoder().decode(block.getJSONObject("image_url").getString("url").substringAfter(','))))}}
                seen.add(body.getString("model") to hashes);val detail=raw.contains("追问底部此前未说的细节");if(hashes.isNotEmpty())questions.add(detail)
                success(if(hashes.isNotEmpty())if(detail)"底部有红色横线"else"主体是绿色图形"else if(raw.contains("底部有红色横线"))"底部有红色横线"else"主体是绿色图形")
            }).use{server->
                val chat=config(capacity=8192,base=server.base);vault.save(chat,"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,"chat");vault.save(config("vision",8192,server.base),"synthetic-key-only");vault.setPurpose(ModelPurpose.AUX_VISION,"vision");ChatImages.setEnabled(store.writableDatabase,aux)
                var expected=listOf<String>()
                repeat(2){round->val t=ledger.begin("r",if(round==0)"先说主体"else"追问底部此前未说的细节","chat",image=if(round==0)image else null)
                    if(round==0){refs=listOf(ChatImages.ref(store.readableDatabase,t.userMessageId)!!);ImageParts.prepare(context,refs,{ChatImages.read(store.readableDatabase,it)},{ }).use{plan->expected=plan.parts.map{RoleArchive.hash(it.wire().bytes)}}}
                    val r=ChatImageRoute(store,vault,server.client(10000),t,chat,AppMutationGate.epoch()){true};val result=r.complete(chat,"synthetic-key-only",request(t,refs)) as TextChatResult.Success
                    if(round==1)assertTrue(result.text.contains("底部有红色横线"));assertTrue(ledger.finishWithMemory(t.id,t.attempt,result.text,onCompleted={r.commit(it)}))
                }
                assertEquals(expected+expected,seen.flatMap{it.second});assertEquals(2,seen.count{it.second.isEmpty()});assertTrue(seen.filter{it.second.isNotEmpty()}.all{it.first==if(aux)"vision"else"chat"});assertTrue(questions.any{it}&&questions.any{!it});noTemps()
                println("TILE_TLS auxiliary=$aux partsPerQuestion=${expected.size} totalCalls=${seen.size} allPartsRepeated=true")
                evidence("tile-tls-$aux",mapOf("auxiliary" to aux,"partsPerQuestion" to expected.size,"partHashes" to expected,"calls" to server.calls.get(),"imageCalls" to seen.count{it.second.isNotEmpty()},"finalCalls" to seen.count{it.second.isEmpty()},"transport" to "actual-loopback-TLS","allPartsRereadForNewQuestion" to true))
            }
        }
    }
}
