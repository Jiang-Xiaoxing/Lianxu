package app.lianxu.independent

import com.google.gson.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.zip.*

class BackupArchiveTest {
    private fun root()=JsonParser.parseString("""{"scope":"all","roles":[],"preferences":{},"models":[],"selections":{},"includesKeys":false}""").asJsonObject
    private fun bytes(r:JsonObject=root(),files:Map<String,ByteArray> = emptyMap())=ByteArrayOutputStream().also{BackupArchive.write(r,files,it)}.toByteArray()
    @Test fun cancelledOrFailedExportRemovesItsPartialSnapshot() {
        val parent = java.nio.file.Files.createTempDirectory("backup-export-test").toFile()
        val work = File(parent, "backup-work")
        try {
            for (failure in listOf(IOException("storage full"), java.util.concurrent.CancellationException())) {
                val result = runCatching {
                    BackupArchive.snapshot(root(), emptyMap(), parent) {
                        if (work.walkTopDown().any { it.name == "archive.zip" && it.length() > 0 }) throw failure
                    }.use { }
                }
                assertSame(failure, result.exceptionOrNull())
                assertTrue(work.listFiles()!!.isEmpty())
            }
        } finally {
            work.delete()
            parent.delete()
        }
    }

    @Test fun manifestHashesAndAssetBytesRoundTrip(){val data=byteArrayOf(0,1,2,3);val p=BackupArchive.read(bytes(files=mapOf("assets/0.bin" to data)).inputStream());assertArrayEquals(data,p.files.getValue("assets/0.bin"));assertTrue(p.all);assertFalse(p.includesKeys)}
    @Test fun pathTraversalAndUnknownEntriesRejected(){for(name in listOf("../backup.json","/backup.json","C:\\backup.json","assets/../../x","unknown.bin")){val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry(name));z.write(byteArrayOf(1));z.closeEntry()};assertTrue(runCatching{BackupArchive.read(out.toByteArray().inputStream())}.isFailure)}}
    @Test fun changedArchiveContentFailsHash(){val input=ZipInputStream(bytes().inputStream());val entries=linkedMapOf<String,ByteArray>();input.use{z->while(true){val e=z.nextEntry?:break;entries[e.name]=z.readBytes()}};entries["backup.json"]=root().apply{addProperty("scope","role")}.toString().toByteArray();val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->entries.forEach{(name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}};assertTrue(runCatching{BackupArchive.read(out.toByteArray().inputStream())}.isFailure)}
    @Test fun truncatedAndDuplicateJsonRejected(){assertTrue(runCatching{BackupArchive.read(byteArrayOf(1,2,3).inputStream())}.isFailure);assertTrue(runCatching{RoleArchive.parse("""{"scope":"all","scope":"role"}""".toByteArray())}.isFailure)}
    @Test fun boundedReaderFailsBeforeUnlimitedGrowth(){assertTrue(runCatching{BackupArchive.bounded(ByteArray(1025).inputStream(),1024)}.isFailure)}
    @Test fun trailingInputIsRejectedByZipStructureWithoutAnArchiveTotalLimit(){
        val stream=SequenceInputStream(bytes().inputStream(),ByteArray(100).inputStream())
        val failure=runCatching{BackupArchive.read(stream)}.exceptionOrNull()
        assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("ZIP结尾"))
    }
    @Test fun zipBombJsonIsStoppedAtEightMiB(){
        val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("backup.json"));val block=ByteArray(8192){' '.code.toByte()}
            repeat(BackupArchive.MAX_JSON/block.size+1){z.write(block)};z.closeEntry()}
        assertTrue(out.size()<100000)
        assertTrue(runCatching{BackupArchive.read(out.toByteArray().inputStream())}.isFailure)
    }
    @Test fun writerRejectsReservedAndNoncanonicalAssetPathsBeforeWriting(){
        for(name in listOf("backup.json","manifest.json","assets/../0.bin","assets\\0.bin","assets/a.bin")){
            val out=ByteArrayOutputStream();assertTrue(runCatching{BackupArchive.write(root(),mapOf(name to byteArrayOf(1)),out)}.isFailure);assertEquals(0,out.size())}
    }
    @Test fun writerAndReaderStreamAboveOldTotalWithoutMaterializingResources(){
        var largestRead=0
        val files=object:BackupAssets(){
            override val names=(0..2).map{"assets/$it.bin"}.toSet()
            override fun length(name:String)=BackupArchive.MAX_FILE.toLong()
            override fun get(key:String):ByteArray?=error("whole-resource materialization is forbidden")
            override fun open(name:String)=object:InputStream(){var left=length(name)
                override fun read():Int=if(left-->0)0 else -1
                override fun read(b:ByteArray,off:Int,len:Int):Int{if(len==0)return 0;if(left<=0)return -1;largestRead=maxOf(largestRead,len);val n=minOf(left,len.toLong()).toInt();b.fill(0,off,off+n);left-=n;return n}
            }
            override fun close()=Unit
        }
        val out=ByteArrayOutputStream();BackupArchive.write(root(),files,out)
        BackupArchive.read(out.toByteArray().inputStream()).use{pack->assertTrue(pack.files is ZipBackupAssets);assertEquals(120_000_000L,BackupAssetIO.total(pack.files));assertEquals(3,pack.files.size)}
        assertTrue(largestRead<=BackupAssetIO.BUFFER)
    }
    @Test fun writerRejectsOversizeJsonBeforeWriting(){
        val r=root();val same="x".repeat(2*1024*1024);repeat(4){r["preferences"].asJsonObject.addProperty("synthetic-$it",same)}
        val out=ByteArrayOutputStream();assertTrue(runCatching{BackupArchive.write(r,emptyMap(),out)}.isFailure);assertEquals(0,out.size())
    }
    @Test fun finalPreflightRejectsAvatarThatPushesOtherwiseValidDataOverLimit(){
        val r=root();val payload="x".repeat(1_950_000);repeat(4){r["preferences"].asJsonObject.addProperty("synthetic-$it",payload)}
        BackupArchive.preflight(r,emptyMap())
        r["preferences"].asJsonObject.addProperty(UserAvatarData.KEY,UserAvatarData.encode(ByteArray(UserAvatarData.MAX_BYTES)))
        val original="existing synthetic backup".toByteArray();val destination=ByteArrayOutputStream().apply{write(original)}
        val failure=runCatching{BackupArchive.preflight(r,emptyMap());BackupArchive.write(r,emptyMap(),destination)}.exceptionOrNull()
        assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("8 MiB"));assertArrayEquals(original,destination.toByteArray())
    }
    @Test fun legacyRulesRemainStrictAndValidLegacyStillReads(){
        val r=JsonParser.parseString("""{"scope":"role","character":{"id":"synthetic","name":"林澈","bio":"虚构成年人","prompt":"测试","tone":0},"prompts":{},"records":{}}""").asJsonObject
        val original=ByteArrayOutputStream().also{RoleArchive.write(r,it)}.toByteArray()
        assertEquals("林澈",BackupArchive.read(original.inputStream()).legacy!!.name)
        val entries=linkedMapOf<String,ByteArray>();ZipInputStream(original.inputStream()).use{z->while(true){val e=z.nextEntry?:break;entries[e.name]=z.readBytes()}}
        val manifest=RoleArchive.parse(entries.getValue("manifest.json"));manifest.addProperty("extra","not-supported");entries["manifest.json"]=manifest.toString().toByteArray()
        val bad=ByteArrayOutputStream();ZipOutputStream(bad).use{z->entries.forEach{(name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}}
        assertTrue(runCatching{BackupArchive.read(bad.toByteArray().inputStream())}.isFailure)
    }
    @Test fun remappingChangesOnlyReferencesNeverUserText(){val value=JsonParser.parseString("""{"id":"memory","character":"old","conversation":"old","text":"old memory 7","evidence":[{"character":"old","turn":7,"quote":"old memory 7"}],"source":{"request":"turn","user_id":7,"assistant_id":8},"associationVersions":{"memory":1},"resultVersions":{"memory":2}}""");val r=BackupRemapper("old","new",mapOf("memory" to "m2","turn" to "t2"),mapOf(7L to 70L,8L to 80L)).json(value).asJsonObject
        assertEquals("m2",r["id"].asString);assertEquals("new",r["conversation"].asString);assertEquals("old memory 7",r["text"].asString);assertEquals(70L,r["evidence"].asJsonArray[0].asJsonObject["turn"].asLong);assertEquals("t2",r["source"].asJsonObject["request"].asString);assertTrue(r["associationVersions"].asJsonObject.has("m2"));assertEquals(2,r["resultVersions"].asJsonObject["m2"].asInt)}
}
