package app.lianxu.independent

/** Local engineering limits, not a provider capability table. MB means decimal bytes. */
internal object ImagePolicy {
    const val ORIGINAL_LIMIT=20_000_000
    const val INPUT_LIMIT=40_000_000
    const val REQUEST_LIMIT=32*1024*1024
    const val TILE_SIDE=2048
    const val OVERLAP=204
    const val MAX_PARTS=64
    const val MAX_PIXELS=160_000_000L
    const val MAX_SIDE=65535
    const val MAX_PREPARED=96L*1024*1024
    val mimes=setOf("image/jpeg","image/png","image/webp")
    data class Region(val left:Int,val top:Int,val right:Int,val bottom:Int){
        val width get()=right-left
        val height get()=bottom-top
    }
    fun original(size:Long)=size in 1..ORIGINAL_LIMIT.toLong()
    fun regions(width:Int,height:Int):List<Region>{
        require(width in 1..MAX_SIDE&&height in 1..MAX_SIDE&&width.toLong()*height<=MAX_PIXELS){"图片尺寸超过本机处理上限"}
        fun starts(length:Int):List<Int>{
            val result=mutableListOf(0)
            while(result.last()+TILE_SIDE<length)result.add(result.last()+TILE_SIDE-OVERLAP)
            return result
        }
        val xs=starts(width);val ys=starts(height)
        require(xs.size.toLong()*ys.size+1<=MAX_PARTS){"图片需要超过64张局部/总览，未发送或遗漏任何部分"}
        return ys.flatMap{y->xs.map{x->Region(x,y,minOf(x+TILE_SIDE,width),minOf(y+TILE_SIDE,height))}}
    }
    fun encodedLength(size:Long)=4*((size+2)/3)
}
