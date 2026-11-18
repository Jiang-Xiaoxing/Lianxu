package app.lianxu.independent

import android.database.sqlite.SQLiteFullException
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Only owned synthetic DBs/resources. No real user data, providers or physical storage filling. */
class StreamingBackupAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store:Store;private lateinit var vault:ModelVault;private lateinit var backup:BackupRepository;private lateinit var name:String
    private val resources=mutableListOf<Closeable>();private val files=mutableListOf<File>()
    @Before fun setup():Unit=runBlocking{name="stream-${System.nanoTime()}.db";store=Store(context,name);vault=ModelVault(context,"stream-${System.nanoTime()}",store);backup=BackupRepository(store,vault);store.add(listOf(Friend("r","合成备份角色","合成资料"),Friend("keep","保留角色","隔离资料")),false);store.exchange(store.friends().single{it.id=="keep"},"保留原话","保留回复")}
    @After fun cleanup(){resources.asReversed().forEach{it.close()};files.forEach{it.delete()};vault.list().forEach{vault.delete(it.id)};store.close();context.deleteDatabase(name)}
    private fun <T:Closeable> own(v:T):T=v.also{resources+=it}
    private fun resource(path:String="attachments/legacy.bin",size:Int){store.writableDatabase.execSQL("INSERT INTO role_assets(friend_id,path,kind,mime,data) VALUES(?,?,?, ?,zeroblob(?))",arrayOf<Any>("r",path,"attachment","application/octet-stream",size))}
    private fun hash(role:String,path:String):String{val db=store.readableDatabase;val n=db.rawQuery("SELECT length(data) FROM role_assets WHERE friend_id=? AND path=?",arrayOf(role,path)).use{assertTrue(it.moveToFirst());it.getInt(0)};return RoleAssets.stream(db,role,path,n).use{BackupAssetIO.digest(it)}}
    private suspend fun pack():BackupArchive.Package {
        val e=own(backup.export("r"));assertTrue(e.files is ZipBackupAssets);val zip=File.createTempFile("stream-test-",".zip",context.cacheDir).also{files+=it}
        zip.outputStream().use{BackupArchive.write(e.root,e.files,it)}
        return own(zip.inputStream().use{BackupArchive.read(it,context.cacheDir)})
    }
    private suspend fun unchanged():String=store.friends().toString()+store.messages().toString()+hash("r","attachments/legacy.bin")+vault.stamp()
    private fun record(label:String,j:JSONObject){val dir=File(context.getExternalFilesDir(null),"backup-stream-evidence").apply{mkdirs()};File(dir,"$label.json").writeText(j.toString(2))}
    private fun statusValue(key:String)=File("/proc/self/status").readLines().firstOrNull{it.startsWith("$key:")}?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)?:-1
    @Test fun near40MbLegacyResourceReportsTimeNativePeakAndDiskCost():Unit=runBlocking{
        val size=39_999_997;resource(size=size);val expected=hash("r","attachments/legacy.bin");val p=pack();val plan=backup.preview(p,"ADD")
        val baselineHwm=statusValue("VmHWM");var peakNative=0L;var peakJava=0L;var peakDbBytes=0L;val stop=AtomicBoolean(false)
        val observer=Thread{while(!stop.get()){
            peakNative=maxOf(peakNative,android.os.Debug.getNativeHeapAllocatedSize());val runtime=Runtime.getRuntime();peakJava=maxOf(peakJava,runtime.totalMemory()-runtime.freeMemory())
            val base=context.getDatabasePath(name);peakDbBytes=maxOf(peakDbBytes,listOf(base,File(base.path+"-journal"),File(base.path+"-wal")).filter{it.exists()}.sumOf{it.length()});Thread.sleep(5)
        }}.apply{isDaemon=true;start()}
        val start=android.os.SystemClock.elapsedRealtime();val restored=try{backup.commit(plan,true).single()}finally{stop.set(true);observer.join(3000)}
        val elapsed=android.os.SystemClock.elapsedRealtime()-start;assertEquals(expected,hash(restored,"attachments/legacy.bin"));assertEquals(expected,hash("r","attachments/legacy.bin"))
        assertEquals("ok",store.readableDatabase.rawQuery("PRAGMA quick_check",null).use{it.moveToFirst();it.getString(0)})
        record("legacy-near40",JSONObject().put("resourceBytes",size).put("restoreMillis",elapsed).put("baselineProcessVmHwmBytes",baselineHwm).put("finalProcessVmHwmBytes",statusValue("VmHWM")).put("sampledPeakNativeAllocatedBytes",peakNative).put("sampledPeakJavaUsedBytes",peakJava).put("sampledPeakDbAndJournalBytes",peakDbBytes).put("samplePeriodMs",5).put("hash",expected).put("legacySqlConcatenation",true).put("claim","Legacy BLOB may repeatedly copy accumulated content; this is not an end-to-end constant-memory claim. Bounds are per legacy resource, not whole backup."))
    }
    @Test fun archiveAbove64MbCompressedStreamsAndRestoresEveryResource():Unit=runBlocking{
        val data=ByteArray(512*1024);var state=123456789
        for(i in data.indices){state=state xor (state shl 13);state=state xor (state ushr 17);state=state xor (state shl 5);data[i]=state.toByte()}
        val expected=RoleArchive.hash(data);val db=store.writableDatabase;db.beginTransaction();try{repeat(144){i->db.insertOrThrow("role_assets",null,android.content.ContentValues().apply{put("friend_id","r");put("path","attachments/$i.bin");put("kind","attachment");put("mime","application/octet-stream");put("data",data)})};db.setTransactionSuccessful()}finally{db.endTransaction()}
        val exported=own(backup.export("r"));assertTrue(exported.files is ZipBackupAssets);val zip=File.createTempFile("stream-over64-",".zip",context.cacheDir).also{files+=it}
        zip.outputStream().use{BackupArchive.write(exported.root,exported.files,it)};assertTrue(zip.length()>64L*1024*1024)
        val pack=own(zip.inputStream().use{BackupArchive.read(it,context.cacheDir)});assertEquals(144,pack.files.size);val restored=backup.commit(backup.preview(pack,"ADD"),true).single()
        for(i in 0 until 144)assertEquals(expected,hash(restored,"attachments/$i.bin"))
        record("above64",JSONObject().put("zipBytes",zip.length()).put("resourceBytes",144L*data.size).put("entries",144).put("resourceSha256",expected).put("sourceAndRestoreMatch",true).put("filesRemainDiskBacked",pack.files is ZipBackupAssets))
    }
    @Test fun insufficientSpaceRejectsBeforeAnyReplacement():Unit=runBlocking{
        resource(size=2*1024*1024);val before=unchanged();val p=pack();val fail=BackupRepository(store,vault){0L};val plan=fail.preview(p,"REPLACE","r")
        val error=runCatching{fail.commit(plan,true)}.exceptionOrNull();assertTrue(error?.message.orEmpty().contains("存储空间不足"));assertEquals(before,unchanged())
    }
    private suspend fun abortDuringActualBlobRestore(diskFull:Boolean){
        resource(size=39_999_997);val before=unchanged();val p=pack();val plan=backup.preview(p,"ADD");val lease=ChatAttemptLease();var reached=false
        val result=runCatching{backup.commit(plan,true,lease,checkpoint={
            val size=store.writableDatabase.rawQuery("SELECT COALESCE(sum(length(data)),0) FROM role_assets WHERE friend_id NOT IN('r','keep')",null).use{it.moveToFirst();it.getLong(0)}
            if(size>=35L*1024*1024){reached=true;if(diskFull)throw SQLiteFullException("synthetic storage full")else lease.cancel()}
        })}
        assertTrue(reached);assertTrue(result.isFailure);if(diskFull)assertTrue(result.exceptionOrNull() is SQLiteFullException);assertFalse(lease.wasCommitted());assertEquals(before,unchanged());assertEquals(2,store.friends().size)
    }
    @Test fun injectedStorageFullDuringChunkWriteRollsBack():Unit=runBlocking{abortDuringActualBlobRestore(true)}
    @Test fun cancellingAfterActualChunkWritesRollsBack():Unit=runBlocking{abortDuringActualBlobRestore(false)}
    @Test fun closingOrChangingPreviewNeverImportsPartialData():Unit=runBlocking{
        resource(size=1024*1024);val before=unchanged();val p=pack();val plan=backup.preview(p,"ADD")
        p.close();assertTrue(runCatching{backup.commit(plan,true)}.isFailure);assertEquals(before,unchanged())
        val changed=backup.preview(pack(),"ADD");changed.pack.root["roles"].asJsonArray[0].asJsonObject["character"].asJsonObject.addProperty("name","篡改后")
        assertTrue(runCatching{backup.commit(changed,true)}.isFailure);assertEquals(before,unchanged())
    }
}
