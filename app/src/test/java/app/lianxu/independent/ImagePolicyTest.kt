package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ImagePolicyTest {
    @Test fun decimalTwentyMbBoundaryIsInclusive(){
        assertTrue(ImagePolicy.original(19_999_999));assertTrue(ImagePolicy.original(20_000_000));assertFalse(ImagePolicy.original(20_000_001));assertFalse(ImagePolicy.original(0))
    }
    @Test fun insufficientStorageFailsExplicitlyBeforeWriting(){
        val failure=runCatching{ImageDisk.space(java.io.File("."),1,ImageDisk.RESERVE)}.exceptionOrNull()
        assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("存储空间不足"))
    }
    @Test fun base64PaddingCountsEveryBoundary(){
        assertEquals(4L,ImagePolicy.encodedLength(1));assertEquals(4L,ImagePolicy.encodedLength(3));assertEquals(8L,ImagePolicy.encodedLength(4));assertEquals(26_666_668L,ImagePolicy.encodedLength(20_000_000))
    }
    @Test fun oldSourceSnapshotDefaultsToLegacyWithoutInventingAnOriginal(){
        val image=ChatImageRef("r",1,"chat-images/a.jpg","image/jpeg","a".repeat(64),120,100)
        val source=SourceDialogue("t",SourceRef("r",1,1),Message(1,"r","旧图",true,1,image=image),null,"Asia/Shanghai")
        val json=source.json();json.getJSONObject("image_origin").remove("storageKind")
        assertEquals("legacy-normalized",SourceDialogue.from(json).user.image!!.storageKind)
    }
}
