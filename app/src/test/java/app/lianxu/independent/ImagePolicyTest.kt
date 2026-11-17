package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class ImagePolicyTest {
    @Test fun decimalTwentyMbBoundaryIsInclusive(){
        assertTrue(ImagePolicy.original(19_999_999));assertTrue(ImagePolicy.original(20_000_000));assertFalse(ImagePolicy.original(20_000_001));assertFalse(ImagePolicy.original(0))
    }
    @Test fun tilesCoverEveryEdgeInReadingOrderWithoutScaling(){
        for((w,h) in listOf(1 to 9000,2048 to 2048,2049 to 2049,4097 to 9001,12000 to 400)){
            val parts=ImagePolicy.regions(w,h)
            assertEquals(0,parts.first().left);assertEquals(0,parts.first().top);assertEquals(w,parts.maxOf{it.right});assertEquals(h,parts.maxOf{it.bottom})
            assertTrue(parts.all{it.width in 1..2048&&it.height in 1..2048})
            assertEquals(parts.sortedWith(compareBy<ImagePolicy.Region>{it.top}.thenBy{it.left}),parts)
            val rows=parts.groupBy{it.top}.values.toList()
            for(row in rows)row.zipWithNext().forEach{(a,b)->assertEquals(204,a.right-b.left)}
            rows.zipWithNext().forEach{(a,b)->assertEquals(204,a.first().bottom-b.first().top)}
            for(y in 0 until h step 31)for(x in 0 until w step 31)assertTrue(parts.any{x in it.left until it.right&&y in it.top until it.bottom})
        }
    }
    @Test fun pixelAndPartCapsFailBeforeReturningIncompleteCoverage(){
        assertTrue(runCatching{ImagePolicy.regions(65535,65535)}.isFailure)
        assertTrue(runCatching{ImagePolicy.regions(65535,2400)}.isFailure)
        assertTrue(runCatching{ImagePolicy.regions(0,200)}.isFailure)
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
