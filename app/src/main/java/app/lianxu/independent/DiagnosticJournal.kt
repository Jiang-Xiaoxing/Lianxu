package app.lianxu.independent

import com.google.gson.*
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Redaction is applied before enqueueing, including exceptions and response bodies. */
internal class DiagnosticRedactor {
    private val secrets=linkedSetOf<String>()
    @Synchronized fun register(secret:String){if(secret.isNotEmpty()){
        val variants=listOf(secret,URLEncoder.encode(secret,"UTF-8"),JsonPrimitive(secret).toString().removeSurrounding("\""),Gson().toJson(secret).removeSurrounding("\"")).distinct()
        variants.forEach{secrets.remove(it);secrets.add(it)}
        while(secrets.size>512)secrets.remove(secrets.first())
    }}
    private val sensitive=Regex("(?i)^(key|authorization|proxy-authorization|cookie|set-cookie|reasoning|reasoning_content|thinking|.*(?:api.?key|token|password|secret|credential).*)$")
    @Synchronized fun text(raw:String):String {
        var value=raw
        secrets.sortedByDescending{it.length}.forEach{value=value.replace(it,"[已脱敏]")}
        value=value.replace(Regex("""(?is)<think(?:ing)?\b[^>]*>.*?(?:</think(?:ing)?\s*>|$)"""),"[模型思考内容已省略]")
        value=value.replace(Regex("""(?im)(authorization|proxy-authorization|cookie|set-cookie|x-api-key)\s*["']?\s*[:=]\s*[^\r\n]*"""),"$1: [已脱敏]")
        value=value.replace(Regex("""(?i)(["']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|token|key|reasoning_content|reasoning|thinking)["']?\s*[:=]\s*)(?:["'][^"']*["']|[^\s,;&}]+)"""),"$1[已脱敏]")
        value=value.replace(Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9+/=._~-]+"""),"$1 [已脱敏]")
        value=value.replace(Regex("""(?i)\b(?:sk|sess|ghp|gho|github_pat)[-_][A-Za-z0-9_-]{8,}"""),"[已脱敏]")
        return value.replace(Regex("""https?://[^\s"<>]+""")){m->runCatching{val u=URI(m.value);require(u.host!=null);URI(u.scheme,null,u.host,u.port,u.path,null,null).toString()+if(u.rawQuery!=null||u.rawFragment!=null)"?[参数已脱敏]" else ""}.getOrDefault("[地址已脱敏]")}
    }
    fun clean(value:JsonElement):JsonElement=when{
        value.isJsonObject->JsonObject().apply{value.asJsonObject.entrySet().forEach{(k,v)->add(k,if(sensitive.matches(k))JsonPrimitive("[已脱敏]") else clean(v))}}
        value.isJsonArray->JsonArray().apply{value.asJsonArray.forEach{add(clean(it))}}
        value.isJsonPrimitive&&value.asJsonPrimitive.isString->JsonPrimitive(text(value.asString))
        else->value.deepCopy()
    }
}

internal class DiagnosticJournal(private val directory:File,private val version:String,private val segmentBytes:Int=2*1024*1024,private val segments:Int=5,private val keepMillis:Long=7L*24*60*60*1000,queueCapacity:Int=128):AutoCloseable {
    private data class Pending(val event:String,val trace:String,val data:JsonObject,val bytes:Long,val elapsed:Long?,val barrier:CountDownLatch?=null)
    val redactor=DiagnosticRedactor()
    private val queue=ArrayBlockingQueue<Pending>(queueCapacity)
    private val pendingBytes=AtomicLong();val dropped=AtomicLong();private var reportedDrops=0L
    private val rotations=AtomicLong();private val expired=AtomicLong();private val started=System.currentTimeMillis()
    private val lock=Any();@Volatile private var closed=false
    private val gson=Gson();private val worker:Thread
    init{require(segmentBytes>=1024&&segments in 2..10&&queueCapacity>0);worker=Thread({consume()},"lianxu-diagnostics").apply{isDaemon=true;start()}}
    fun record(event:String,trace:String=UUID.randomUUID().toString(),data:Map<String,Any?> = emptyMap(),elapsed:Long?=null,secrets:List<String> = emptyList()){
        if(closed)return
        val original=gson.toJsonTree(data).asJsonObject
        if(original.toString().length>1024*1024){dropped.incrementAndGet();return}
        val safe=synchronized(redactor){secrets.forEach{redactor.register(it)};Triple(redactor.clean(original).asJsonObject,redactor.text(event),redactor.text(trace))}
        val json=safe.first;val size=json.toString().length.toLong()*2
        if(size>1024*1024||pendingBytes.addAndGet(size)>2*1024*1024){if(size<=1024*1024)pendingBytes.addAndGet(-size);dropped.incrementAndGet();return}
        if(!queue.offer(Pending(safe.second,safe.third,json,size,elapsed))){pendingBytes.addAndGet(-size);dropped.incrementAndGet()}
    }
    private fun consume(){
        directory.mkdirs()
        synchronized(lock){prune()}
        while(!closed||queue.isNotEmpty()){
            val p=try{queue.poll(200,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){null}?:continue
            if(p.barrier!=null){p.barrier.countDown();continue}
            try {
                val now=System.currentTimeMillis();val lost=dropped.get()
                if(lost>reportedDrops){write(JsonObject().apply{addProperty("at",now);addProperty("event","diagnostic.dropped");addProperty("count",lost-reportedDrops)});reportedDrops=lost}
                val clean=p.data;val raw=clean.toString();val excerptLimit=minOf(32768,segmentBytes/16)
                val row=JsonObject().apply{addProperty("at",now);addProperty("event",p.event);addProperty("trace",p.trace);addProperty("version",version);p.elapsed?.let{addProperty("elapsed_ms",it)}
                    if(raw.length>excerptLimit){addProperty("truncated",true);addProperty("original_chars",raw.length);addProperty("data_excerpt",raw.take(excerptLimit))}else add("data",clean)
                }
                write(row)
            } catch(_:Exception){dropped.incrementAndGet()}finally{pendingBytes.addAndGet(-p.bytes)}
        }
    }
    private fun prune(){
        directory.listFiles().orEmpty().filter{f->val match=Regex("""events-([0-9]+)\.jsonl""").matchEntire(f.name);match!=null&&(match.groupValues[1].toIntOrNull()?.let{it>=segments}==true||System.currentTimeMillis()-f.lastModified()>keepMillis)}.forEach{if(it.delete())expired.incrementAndGet()}
    }
    private fun write(row:JsonObject)=synchronized(lock){
        prune()
        val current=File(directory,"events-0.jsonl");val text=row.toString()+"\n"
        check(text.toByteArray(Charsets.UTF_8).size<=segmentBytes)
        if(current.length()+text.toByteArray().size>segmentBytes){File(directory,"events-${segments-1}.jsonl").delete();for(i in segments-2 downTo 0){val f=File(directory,"events-$i.jsonl");if(f.exists())check(f.renameTo(File(directory,"events-${i+1}.jsonl")))};rotations.incrementAndGet()}
        current.appendText(text,Charsets.UTF_8)
    }
    fun flush():Boolean {if(closed)return queue.isEmpty();val latch=CountDownLatch(1);return queue.offer(Pending("","",JsonObject(),0,null,latch),2,TimeUnit.SECONDS)&&latch.await(3,TimeUnit.SECONDS)}
    fun export(detailed:Boolean):ByteArray {
        val complete=flush()
        return synchronized(lock){prune();buildString{
            append(gson.toJson(mapOf("format" to "lianxu-diagnostics","version" to 1,"detailed" to detailed,"flush_complete" to complete,"dropped_events" to dropped.get(),"session_started" to started,"session_rotations" to rotations.get(),"session_removed_segments" to expired.get(),"history_window_bounded" to true,"retention_days" to keepMillis/(24*60*60*1000),"max_disk_bytes" to segmentBytes.toLong()*segments,"notice" to "仅包含限额内仍保留的本机记录；轮转前历史可能已清理，截断或丢失均有标记。"))).append('\n')
            for(i in segments-1 downTo 0)File(directory,"events-$i.jsonl").takeIf{it.exists()}?.forEachLine(Charsets.UTF_8){line->
                val row=runCatching{JsonParser.parseString(line).asJsonObject}.getOrNull()
                if(row==null)append("{\"event\":\"diagnostic.corrupt_line\"}\n") else {
                    if(!detailed){row.remove("data");row.remove("data_excerpt")}
                    append(row).append('\n')
                }
            }
        }.toByteArray(Charsets.UTF_8)}
    }
    override fun close(){flush();closed=true;worker.join(4000)}
}
