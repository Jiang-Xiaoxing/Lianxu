package app.lianxu.independent

import com.google.gson.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.zip.*

class BackupArchiveTest {
    private fun root()=JsonParser.parseString("""{"scope":"all","roles":[],"preferences":{},"models":[],"selections":{},"includesKeys":false}""").asJsonObject
    private fun bytes(r:JsonObject=root(),files:Map<String,ByteArray> = emptyMap())=ByteArrayOutputStream().also{BackupArchive.write(r,files,it)}.toByteArray()
    @Test fun manifestHashesAndAssetBytesRoundTrip(){val data=byteArrayOf(0,1,2,3);val p=BackupArchive.read(bytes(files=mapOf("assets/0.bin" to data)).inputStream());assertArrayEquals(data,p.files.getValue("assets/0.bin"));assertTrue(p.all);assertFalse(p.includesKeys)}
    @Test fun pathTraversalAndUnknownEntriesRejected(){for(name in listOf("../backup.json","/backup.json","C:\\backup.json","assets/../../x","unknown.bin")){val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry(name));z.write(byteArrayOf(1));z.closeEntry()};assertTrue(runCatching{BackupArchive.read(out.toByteArray().inputStream())}.isFailure)}}
    @Test fun changedArchiveContentFailsHash(){val input=ZipInputStream(bytes().inputStream());val entries=linkedMapOf<String,ByteArray>();input.use{z->while(true){val e=z.nextEntry?:break;entries[e.name]=z.readBytes()}};entries["backup.json"]=root().apply{addProperty("scope","role")}.toString().toByteArray();val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->entries.forEach{(name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}};assertTrue(runCatching{BackupArchive.read(out.toByteArray().inputStream())}.isFailure)}
    @Test fun truncatedAndDuplicateJsonRejected(){assertTrue(runCatching{BackupArchive.read(byteArrayOf(1,2,3).inputStream())}.isFailure);assertTrue(runCatching{RoleArchive.parse("""{"scope":"all","scope":"role"}""".toByteArray())}.isFailure)}
    @Test fun boundedReaderFailsBeforeUnlimitedGrowth(){assertTrue(runCatching{BackupArchive.bounded(ByteArray(1025).inputStream(),1024)}.isFailure)}
    @Test fun trailingInputCountsTowardCompressedLimitWithoutBufferingIt(){
        val tail=object:InputStream(){var left=BackupArchive.MAX_BYTES.toLong();override fun read():Int=if(left-->0)0 else -1
            override fun read(b:ByteArray,off:Int,len:Int):Int {if(left<=0)return -1;val n=minOf(left,len.toLong()).toInt();java.util.Arrays.fill(b,off,off+n,0.toByte());left-=n;return n}}
        val stream=SequenceInputStream(bytes().inputStream(),tail)
        val failure=runCatching{BackupArchive.read(stream)}.exceptionOrNull()
        assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("64 MiB"))
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
    @Test fun writerRejectsExpandedTotalEvenWhenAssetsAreHighlyCompressible(){
        val data=ByteArray(BackupArchive.MAX_FILE);val out=ByteArrayOutputStream()
        assertTrue(runCatching{BackupArchive.write(root(),mapOf("assets/0.bin" to data,"assets/1.bin" to data,"assets/2.bin" to data),out)}.isFailure)
        assertEquals(0,out.size())
    }
    @Test fun writerRejectsOversizeJsonBeforeWriting(){
        val r=root();val same="x".repeat(2*1024*1024);repeat(4){r["preferences"].asJsonObject.addProperty("synthetic-$it",same)}
        val out=ByteArrayOutputStream();assertTrue(runCatching{BackupArchive.write(r,emptyMap(),out)}.isFailure);assertEquals(0,out.size())
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
