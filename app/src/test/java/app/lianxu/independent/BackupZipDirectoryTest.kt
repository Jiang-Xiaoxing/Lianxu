package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupZipDirectoryTest {
    private fun archive(count:Int):File=File.createTempFile("directory-test-",".zip").apply{outputStream().use{out->ZipOutputStream(out).use{z->repeat(count){z.putNextEntry(ZipEntry("assets/$it.bin"));z.closeEntry()}}}}
    private fun end(bytes:ByteArray)=(bytes.size-22 downTo 0).first{bytes[it]==0x50.toByte()&&bytes[it+1]==0x4b.toByte()&&bytes[it+2]==5.toByte()&&bytes[it+3]==6.toByte()}
    private fun short(bytes:ByteArray,at:Int,value:Int){bytes[at]=value.toByte();bytes[at+1]=(value ushr 8).toByte()}
    @Test fun normalDirectoryIsCountedBeforeZipFile(){val file=archive(4);try{assertEquals(4,BackupZipDirectory.check(file).records)}finally{file.delete()}}
    @Test fun forgedLowerOrHigherEocdCountsDoNotHideActualRecords(){val file=archive(100);try{val original=file.readBytes();for(n in listOf(2,99,101)){val b=original.copyOf();val e=end(b);short(b,e+8,n);short(b,e+10,n);file.writeBytes(b);assertTrue(runCatching{BackupZipDirectory.check(file)}.exceptionOrNull()?.message.orEmpty().contains("实际数量"))}}finally{file.delete()}}
    @Test fun oversizedRealZip64EmptyDirectoryIsRejectedWithoutZipFile(){val file=archive(BackupArchive.MAX_ENTRIES+1);try{assertTrue(runCatching{BackupZipDirectory.check(file)}.exceptionOrNull()?.message.orEmpty().contains("数量"))}finally{file.delete()}}
    @Test fun smallDeclaredCountWithHugeRealDirectoryStillStopsAtActualLimit(){val file=archive(BackupArchive.MAX_ENTRIES+1);try{
        val b=file.readBytes();val e=end(b);val locator=e-20
        var zip64=0L;for(i in 0..7)zip64=zip64 or ((b[locator+8+i].toLong() and 255) shl (8*i))
        for(at in listOf(zip64.toInt()+24,zip64.toInt()+32)){for(i in 0..7)b[at+i]=(if(i==0)2 else 0).toByte()}
        file.writeBytes(b);assertTrue(runCatching{BackupZipDirectory.check(file)}.exceptionOrNull()?.message.orEmpty().contains("实际文件数量"))
    }finally{file.delete()}}
    @Test fun trailingPayloadAndTruncatedDirectoryAreRejected(){val file=archive(2);try{val b=file.readBytes();file.appendBytes(byteArrayOf(0,1));assertTrue(runCatching{BackupZipDirectory.check(file)}.isFailure);file.writeBytes(b.copyOf(b.size-8));assertTrue(runCatching{BackupZipDirectory.check(file)}.isFailure)}finally{file.delete()}}
    @Test fun excessiveDirectoryMetadataIsRejectedBeforeIndexConstruction(){
        val file=File.createTempFile("large-directory-",".zip")
        try{file.outputStream().use{out->ZipOutputStream(out).use{z->val comment="x".repeat(65000);repeat(280){i->z.putNextEntry(ZipEntry("assets/$i.bin").apply{this.comment=comment});z.closeEntry()}}}
            assertTrue(file.length()>BackupZipDirectory.MAX_DIRECTORY_BYTES);assertTrue(runCatching{BackupZipDirectory.check(file)}.exceptionOrNull()?.message.orEmpty().contains("目录体积"))
        }finally{file.delete()}
    }
}
