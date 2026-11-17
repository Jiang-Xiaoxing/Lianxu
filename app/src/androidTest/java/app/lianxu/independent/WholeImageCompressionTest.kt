package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Base64

class WholeImageCompressionTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var vault:ModelVault;private lateinit var ledger:ChatLedger;private lateinit var name:String
    @Before fun setup()=runBlocking{name="compression-${System.nanoTime()}.db";store=Store(context,name);ledger=ChatLedger(store);store.add(listOf(Friend("r","合成角色","合成测试")),false);vault=ModelVault(context,"compression-${System.nanoTime()}",store)}
    @After fun cleanup(){vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name)}
    private fun config(id:String="chat",base:String="https://example.invalid/v1")=ModelConfig(id,"合成$id","custom",base,id,128000,2048)
    private fun setupModels(base:String,aux:Boolean){vault.save(config(base=base),"synthetic-key-only");vault.setPurpose(ModelPurpose.CHAT,"chat");vault.save(config("vision",base),"synthetic-key-only");vault.setPurpose(ModelPurpose.AUX_VISION,"vision");ChatImages.setEnabled(store.writableDatabase,aux)}
    private fun image(bytes:Int,width:Int=120,height:Int=100)=ChatImages.prepare(OriginalImageFixtures.paddedJpeg(bytes,width,height).inputStream(),context.cacheDir)
    private fun request(t:ChatTurn,refs:List<ChatImageRef>)=TextChatRequest(listOf(WireMessage("user",runBlocking{ledger.context(t.id)}.input,imageRefs=refs)))
    private fun evidence(name:String,j:JSONObject){val dir=File(context.getExternalFilesDir(null),"compression-evidence").apply{mkdirs()};File(dir,"$name.json").writeText(j.toString(2))}
    @Test fun formatsExifAndOldBackupsKeepTheirOriginalMeaning():Unit=runBlocking{
        for(format in listOf(Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.WEBP_LOSSLESS)){
            val raw=OriginalImageFixtures.picture(format=format);ChatImages.prepare(raw.inputStream(),context.cacheDir).use{assertArrayEquals(raw,it.bytes)}
        }
        assertTrue(runCatching{ChatImages.prepare(OriginalImageFixtures.png16(animated=true).inputStream(),context.cacheDir)}.isFailure)
        assertTrue(runCatching{ChatImages.prepare("unsupported".byteInputStream(),context.cacheDir)}.isFailure)
        val file=File.createTempFile("exif-",".jpg",context.cacheDir)
        try{
            file.writeBytes(OriginalImageFixtures.picture(120,200,Bitmap.CompressFormat.JPEG))
            ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");setAttribute(ExifInterface.TAG_ARTIST,"synthetic-only");saveAttributes()}
            val raw=OriginalImageFixtures.padJpeg(file.readBytes(),20_000_001);val p=ChatImages.prepare(raw.inputStream(),context.cacheDir);val t=ledger.begin("r","方向","chat",image=p);p.close();val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!
            WholeImageDelivery.prepare(context,listOf(ref),{ChatImages.source(store.readableDatabase,it)},{ }).use{d->val copy=d.images.values.single();val info=ChatImages.inspect(copy.source);assertEquals(200,info.width);assertEquals(120,info.height);assertEquals(1,info.orientation);assertNull(copy.source.input().use{ExifInterface(it).getAttribute(ExifInterface.TAG_ARTIST)})}
            assertEquals("synthetic-only",ChatImages.source(store.readableDatabase,ref).input().use{ExifInterface(it).getAttribute(ExifInterface.TAG_ARTIST)});ledger.finishWithMemory(t.id,1,"结束",onCompleted={})
        }finally{file.delete()}
    }
    @Test fun oldBackupAndVersion17MigrationRemainHistoricalCopies():Unit=runBlocking{
        val old=OriginalImageFixtures.picture(format=Bitmap.CompressFormat.JPEG);val p=ChatImages.prepare(old.inputStream(),context.cacheDir);val t=ledger.begin("r","旧副本","chat",image=p);p.close();ledger.finishWithMemory(t.id,1,"结束",onCompleted={})
        store.writableDatabase.execSQL("UPDATE chat_images SET storage_kind='legacy-normalized' WHERE message_id=?",arrayOf(t.userMessageId))
        val backup=BackupRepository(store,vault);val out=backup.export("r");out.root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject["chat_images"].asJsonArray.forEach{if(it.asJsonObject["message_id"].asLong==t.userMessageId)it.asJsonObject.remove("storage_kind")}
        val added=backup.commit(backup.preview(BackupArchive.Package(out.root,out.files,"synthetic-old"),"ADD"),true).single();val legacy=store.messages().single{it.friendId==added&&it.mine&&it.text=="旧副本"}.image!!;assertEquals("legacy-normalized",legacy.storageKind);assertArrayEquals(old,ChatImages.read(store.readableDatabase,legacy))
        store.close();android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use{it.execSQL("ALTER TABLE chat_images DROP COLUMN storage_kind");it.version=17}
        val namespace=vault.statusNamespace;store=Store(context,name);vault=ModelVault(context,namespace,store);ledger=ChatLedger(store);assertEquals(18,store.readableDatabase.version);assertEquals("legacy-normalized",ChatImages.ref(store.readableDatabase,t.userMessageId)!!.storageKind);assertEquals(2,store.messages().count{it.mine})
    }
    @Test fun chunkFailureRollsBackAndCorruptChunkCannotRestoreAnOriginal():Unit=runBlocking{
        val p=image(20_000_001);store.writableDatabase.execSQL("CREATE TEMP TRIGGER reject_image_part BEFORE INSERT ON role_assets WHEN NEW.path LIKE '%part-00001.bin' BEGIN SELECT RAISE(ABORT,'synthetic disk write failure'); END")
        assertTrue(runCatching{ledger.begin("r","不完整","chat",image=p)}.isFailure);assertTrue(store.messages().isEmpty());assertEquals(0,store.readableDatabase.rawQuery("SELECT count(*) FROM role_assets",null).use{it.moveToFirst();it.getInt(0)})
        store.writableDatabase.execSQL("DROP TRIGGER reject_image_part");val t=ledger.begin("r","完整","chat",image=p);p.close();val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!;assertEquals(ref.hash,ChatImages.source(store.readableDatabase,ref).hash());ImageDisk.clearReadCache()
        store.writableDatabase.execSQL("UPDATE role_assets SET data=zeroblob(length(data)) WHERE path LIKE '%part-00001.bin'")
        assertTrue(runCatching{ChatImages.source(store.readableDatabase,ref)}.isFailure);assertFalse(File(ImageDisk.root(),"${ref.hash}.image").exists())
    }
    @Test fun interruptedImportAndCancelledCompressionRemoveOnlyTheirPrivateTemps(){
        val root=ImageDisk.root(context.cacheDir);val before=root.listFiles()!!.map{it.name}.toSet()
        val input=object:java.io.InputStream(){var n=0;override fun read():Int{if(n++>1000)throw java.io.IOException("synthetic read failure");return 1}}
        assertTrue(runCatching{ChatImages.prepare(input,context.cacheDir)}.isFailure);assertEquals(before,root.listFiles()!!.map{it.name}.toSet())
        val p=image(20_000_001);try{val ref=ChatImageRef("r",1,"synthetic",p.mime,p.hash,p.width,p.height,storageKind="original");var checks=0
            val error=runCatching{WholeImageDelivery.prepare(context,listOf(ref),{p.source},{if(++checks>=2)throw kotlinx.coroutines.CancellationException("synthetic cancel")})}.exceptionOrNull();assertTrue(error is kotlinx.coroutines.CancellationException);assertTrue(p.source.file!!.exists());assertFalse(root.listFiles()!!.any{it.name.startsWith("send-")})
        }finally{p.close()}
    }
    @Test fun exactThresholdStoresOriginalAndProducesExactlyOneWireImage():Unit=runBlocking{
        for(size in listOf(19_999_999,20_000_000,20_000_001)){
            val p=image(size);val hash=p.hash;val t=ledger.begin("r","边界$size","chat",image=p);p.close();val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!;val original=ChatImages.source(store.readableDatabase,ref);assertEquals(size.toLong(),original.size);assertEquals(hash,original.hash())
            WholeImageDelivery.prepare(context,listOf(ref),{original},{ }).use{delivery->val wire=delivery.images.values.single();assertTrue(wire.source.size<=20_000_000);if(size<=20_000_000)assertEquals(hash,wire.source.hash())else{assertNotEquals(hash,wire.source.hash());assertTrue(wire.label.contains("完整压缩副本"))};assertEquals(hash,original.hash());evidence("boundary-$size",JSONObject().put("originalBytes",size).put("wireBytes",wire.source.size).put("wireImages",delivery.images.size).put("originalHash",hash).put("wireHash",wire.source.hash()))}
            assertTrue(ledger.finishWithMemory(t.id,1,"结束",onCompleted={}))
        }
    }
    @Test fun aboveFortyMbUsesChunkedStorageWithoutReplacingOriginal():Unit=runBlocking{
        val p=image(45_000_001,2400,1600);val hash=p.hash;val t=ledger.begin("r","大原件","chat",image=p);p.close();val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!;assertEquals("chunked-original",ref.storageKind)
        val original=ChatImages.source(store.readableDatabase,ref);assertEquals(45_000_001L,original.size);assertEquals(hash,original.hash())
        WholeImageDelivery.prepare(context,listOf(ref),{original},{ }).use{d->val copy=d.images.values.single();assertTrue(copy.source.size<=20_000_000);assertEquals(hash,original.hash());val info=ChatImages.inspect(copy.source);assertEquals(2400,info.width);assertEquals(1600,info.height);evidence("above40",JSONObject().put("originalBytes",original.size).put("wireBytes",copy.source.size).put("originalPreserved",true).put("width",info.width).put("height",info.height))}
        val max=store.readableDatabase.rawQuery("SELECT max(length(data)) FROM role_assets",null).use{it.moveToFirst();it.getLong(0)};assertTrue(max<=OriginalChunks.CHUNK)
    }
    @Test fun exactTwentyMbOriginalActuallyStreamsOverTlsWithExactBodyHash():Unit=runBlocking{
        val p=image(20_000_000);val t=ledger.begin("r","20 MB原件","chat",image=p);p.close();val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!;var received=0;var receivedHash=""
        fun digestText(d:java.security.MessageDigest)=d.digest().joinToString(""){"%02x".format(it)}
        TextTlsMock(maxBodyBytes=ImagePolicy.REQUEST_LIMIT,requestSink={input,size->val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);var left=size;while(left>0){val n=input.read(buffer,0,minOf(buffer.size,left));check(n>0);digest.update(buffer,0,n);left-=n};received=size;receivedHash=digestText(digest)}).use{server->
            WholeImageDelivery.prepare(context,listOf(ref),{ChatImages.source(store.readableDatabase,it)},{ }).use{d->
                val wire=d.images.values.single();assertEquals(ref.hash,wire.source.hash());val req=TextChatRequest(listOf(WireMessage("user","合成原件",images=listOf(wire.wire()))));val cfg=config(base=server.base);val body=ImageWire.body(cfg,req,false)
                val expected=java.security.MessageDigest.getInstance("SHA-256");ImageWire.write(body,req,object:java.io.OutputStream(){override fun write(b:Int){expected.update(b.toByte())};override fun write(b:ByteArray,off:Int,len:Int){expected.update(b,off,len)}})
                val expectedHash=digestText(expected);assertTrue(server.client(15000).complete(cfg,"synthetic-key-only",req) is TextChatResult.Success);assertEquals(1,server.calls.get());assertEquals(ImageWire.size(body,req),received.toLong());assertEquals(expectedHash,receivedHash)
                evidence("original-http",JSONObject().put("sourceBytes",wire.source.size).put("requestBytes",received).put("expectedBodySha256",expectedHash).put("receivedBodySha256",receivedHash).put("originalHash",ref.hash))
            }
        }
    }
    @Test fun alphaAndSixteenBitCopiesKeepOneWholeFrameAndDeclareConversion():Unit=runBlocking{
        for(raw in listOf(OriginalImageFixtures.noisyTransparentPng(),OriginalImageFixtures.png16(20_000_000))){
            val p=ChatImages.prepare(raw.inputStream(),context.cacheDir);val t=ledger.begin("r","透明图","chat",image=p);p.close();val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!;val src=ChatImages.source(store.readableDatabase,ref)
            WholeImageDelivery.prepare(context,listOf(ref),{src},{ }).use{d->val copy=d.images.values.single();assertEquals("image/png",copy.mime);assertTrue(copy.source.size<=20_000_000);val b=copy.source.decode(BitmapFactory.Options())!!;assertTrue(b.hasAlpha());assertTrue(copy.label.contains("8位"));assertEquals(ref.hash,src.hash());b.recycle()};ledger.finishWithMemory(t.id,1,"结束",onCompleted={})
        }
    }
    @Test fun actualHttpBothRoutesAndNewQuestionUseOneFullCopyWithoutExtraJudgement():Unit=runBlocking{
        for(aux in listOf(false,true)){
            val seen=mutableListOf<Pair<String,List<String>>>();var expected=""
            fun reply(text:String)=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",text)))).toString()
            TextTlsMock(maxRequests=4,responder={raw->val j=JSONObject(raw);val hashes=mutableListOf<String>();val ms=j.getJSONArray("messages");for(i in 0 until ms.length()){val c=ms.getJSONObject(i).optJSONArray("content")?:continue;for(k in 0 until c.length()){val b=c.getJSONObject(k);if(b.optString("type")=="image_url")hashes.add(RoleArchive.hash(Base64.getDecoder().decode(b.getJSONObject("image_url").getString("url").substringAfter(','))))}}
                assertTrue(hashes.size<=1);if(hashes.isNotEmpty())assertEquals(expected,hashes.single());assertFalse(raw.contains("局部1/")||raw.contains("本批"));seen.add(j.getString("model") to hashes)
                reply(if(raw.contains("细节")||raw.contains("蓝色三角形7"))"蓝色三角形7"else"主体绿色")
            }).use{server->setupModels(server.base,aux);var ref:ChatImageRef?=null
                repeat(2){round->val p=if(round==0)image(45_000_001)else null;val t=ledger.begin("r",if(round==0)"先说主体"else"追问此前没提的细节","chat",image=p);p?.close();if(round==0){ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!;WholeImageDelivery.prepare(context,listOf(ref!!),{ChatImages.source(store.readableDatabase,it)},{ }).use{expected=it.images.values.single().source.hash()}}
                    val route=ChatImageRoute(store,vault,server.client(15000),t,config(base=server.base),AppMutationGate.epoch()){true};val answer=route.complete(config(base=server.base),"synthetic-key-only",request(t,listOf(ref!!))) as TextChatResult.Success;if(round==1)assertTrue(answer.text.contains("蓝色三角形7"));assertTrue(ledger.finishWithMemory(t.id,1,answer.text,onCompleted={route.commit(it)}))
                }
                assertEquals(if(aux)4 else 2,server.calls.get());assertTrue(seen.filter{it.second.isNotEmpty()}.all{it.first==if(aux)"vision"else"chat"});assertEquals(2,seen.count{it.second.isNotEmpty()});if(aux)assertTrue(seen.filter{it.first=="chat"}.all{it.second.isEmpty()})
                evidence("http-$aux",JSONObject().put("auxiliary",aux).put("calls",server.calls.get()).put("imageCalls",2).put("wireImagesPerImageCall",1).put("wireHash",expected).put("nextQuestionRereads",true))
            }
        }
    }
    @Test fun fullRequestOverLimitNeverDropsImagesOrMakesAuxiliaryCalls():Unit=runBlocking{
        val refs=mutableListOf<ChatImageRef>();repeat(2){val p=image(13_000_000+it);val t=ledger.begin("r","原件","chat",image=p);p.close();refs.add(ChatImages.ref(store.readableDatabase,t.userMessageId)!!);ledger.finishWithMemory(t.id,1,"结束",onCompleted={})}
        for(aux in listOf(false,true)){setupModels("https://example.invalid/v1",aux);val t=ledger.begin("r","本轮","chat");var calls=0;val c=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;return TextChatResult.Success("不应发生")}}
            val route=ChatImageRoute(store,vault,c,t,config(),AppMutationGate.epoch()){true};val e=runCatching{route.complete(config(),"synthetic-key-only",request(t,refs))}.exceptionOrNull() as CompanionModelFailure;assertEquals(ChatFailure.IMAGE_LIMIT,e.failure);assertEquals(0,calls);ledger.fail(t.id,1,e.failure)
        }
    }
    @Test fun originalChunkBackupRoundTripAndMissingChunkAreValidated():Unit=runBlocking{
        val p=image(45_000_001);val hash=p.hash;val t=ledger.begin("r","备份原件","chat",image=p);p.close();ledger.finishWithMemory(t.id,1,"结束",onCompleted={})
        val backup=BackupRepository(store,vault);val out=backup.export("r");val zip=File.createTempFile("compression-backup-",".zip",context.cacheDir)
        try{zip.outputStream().use{BackupArchive.write(out.root,out.files,it)};val pack=zip.inputStream().use{BackupArchive.read(it)};val target=backup.commit(backup.preview(pack,"ADD"),true).single();val ref=store.messages().single{it.friendId==target&&it.mine}.image!!;assertEquals(hash,ChatImages.source(store.readableDatabase,ref).hash());assertEquals("chunked-original",ref.storageKind)
            val broken=pack.copy(files=pack.files.toMutableMap().apply{remove(keys.first())});assertTrue(runCatching{backup.preview(broken,"ADD")}.isFailure)
        }finally{zip.delete()}
    }
    @Test fun sourceChangeDuringAuxiliaryReplyAndRetryKeepOriginalAndSingleMessage():Unit=runBlocking{
        setupModels("https://example.invalid/v1",true);val p=image(20_000_001);val t=ledger.begin("r","原问题","chat",image=p);p.close();val ref=ChatImages.ref(store.readableDatabase,t.userMessageId)!!;var calls=0
        val c=object:TextChatClient{override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult{calls++;ChatImages.setEnabled(store.writableDatabase,false);ChatImages.setEnabled(store.writableDatabase,true);return TextChatResult.Success("迟到结果")}}
        val route=ChatImageRoute(store,vault,c,t,config(),AppMutationGate.epoch()){true};val e=runCatching{route.complete(config(),"synthetic-key-only",request(t,listOf(ref)))}.exceptionOrNull() as CompanionModelFailure;assertEquals(ChatFailure.SOURCE_CHANGED,e.failure);assertEquals(1,calls);ledger.fail(t.id,1,e.failure)
        val retry=ledger.retry(t.id,"chat")!!;assertEquals(t.userMessageId,retry.userMessageId);assertEquals(ref.hash,ChatImages.source(store.readableDatabase,ref).hash());assertEquals(1,store.messages().count{it.mine})
    }
}
