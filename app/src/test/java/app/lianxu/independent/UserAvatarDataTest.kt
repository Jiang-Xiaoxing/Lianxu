package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class UserAvatarDataTest {
    private fun bytes(vararg values:Int)=values.map{it.toByte()}.toByteArray()
    @Test fun jpegMetadataAndTrailingBytesAreRemovedWithoutChangingScanData(){
        val body=bytes(255,219,0,3,9,255,218,0,2,1,255,0,2,255,208,3,255,217)
        val dirty=bytes(255,216,255,225,0,5,71,80,83,255,238,0,3,7,255,254,0,3,8)+body+bytes(9,8,7)
        assertArrayEquals(bytes(255,216)+body,UserAvatarData.withoutMetadata(dirty))
    }
    @Test fun progressiveMetadataBetweenScansIsAlsoRemoved(){
        val first=bytes(255,218,0,2,5,6);val second=bytes(255,218,0,2,7,8,255,217)
        assertArrayEquals(bytes(255,216)+first+second,UserAvatarData.withoutMetadata(bytes(255,216)+first+bytes(255,225,0,3,1)+second))
    }
    @Test fun iccColourProfileIsPreservedWithTheCompressedPhoto(){
        val profile=bytes(255,226,0,16)+"ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)+bytes(1,1)
        val image=bytes(255,216)+profile+bytes(255,218,0,2,1,2,255,217)
        assertArrayEquals(image,UserAvatarData.withoutMetadata(image))
    }
    @Test fun jfifAndJfxxEmbeddedOldThumbnailsAreRemoved(){
        fun segment(payload:ByteArray)=bytes(255,224,(payload.size+2) shr 8,(payload.size+2) and 255)+payload
        val prefix="JFIF\u0000".toByteArray(Charsets.US_ASCII)
        val jfif=segment(prefix+bytes(1,2,0,0,1,0,1,1,1,10,20,30))
        val cleaned=segment(prefix+bytes(1,2,0,0,1,0,1,0,0))
        val jfxx=segment("JFXX\u0000".toByteArray(Charsets.US_ASCII)+bytes(16,9,8,7))
        val scan=bytes(255,218,0,2,1,2,255,217)
        assertArrayEquals(bytes(255,216)+cleaned+scan,UserAvatarData.withoutMetadata(bytes(255,216)+jfif+jfxx+scan))
    }
    @Test fun malformedOrTruncatedJpegCannotPassTheLosslessPath(){
        for(data in listOf(bytes(1,2,3),bytes(255,216,255,225,0,1),bytes(255,216,255,218,0,2,1,2),bytes(255,216,255,217))){assertTrue(runCatching{UserAvatarData.withoutMetadata(data)}.isFailure)}
    }
    @Test fun boundedEncodingRoundTripsWithoutExternalUri(){
        val bytes=ByteArray(UserAvatarData.MAX_BYTES){(it%251).toByte()}
        val encoded=UserAvatarData.encode(bytes)
        assertEquals(UserAvatarData.MAX_ENCODED,encoded.length)
        assertArrayEquals(bytes,UserAvatarData.decode(encoded))
    }
    @Test fun missingMalformedUnsupportedAndOversizedValuesFallBack(){
        listOf("","content://private/photo","v2:YWJj","v1:","v1:%%%","v1:"+"A".repeat(UserAvatarData.MAX_ENCODED)).forEach{assertNull(it.take(30),UserAvatarData.decode(it))}
        for(bytes in listOf(byteArrayOf(),ByteArray(UserAvatarData.MAX_BYTES+1))){
            try{UserAvatarData.encode(bytes);fail("应拒绝空图或过大的图")}catch(_:IllegalArgumentException){}
        }
    }
}
