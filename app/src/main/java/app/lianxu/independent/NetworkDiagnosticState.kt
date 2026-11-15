package app.lianxu.independent

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Shared metadata for one existing network.request ID, never a second logger or retry policy. */
internal data class NetworkTimeoutDiagnostic(val source:String,val primary:Boolean)
internal class NetworkDiagnosticState(val id:String) {
    private val started=System.nanoTime()
    private val stopped=AtomicReference("none")
    private val terminal=AtomicReference<String?>(null)
    private val bytes=AtomicLong()
    private val first=AtomicLong()
    private val last=AtomicLong()
    fun finish(status:String)=terminal.compareAndSet(null,status)
    fun cancel(cause:Throwable?){
        stop(if(cause is TimeoutCancellationException)"coroutine_timeout"else if(cause is CancellationException)"caller_cancellation"else"cancellation_unknown")
    }
    fun stop(source:String){
        if(source=="total_timeout")stopped.set(source)
        else {
            stopped.compareAndSet("none",source)
            if(source in setOf("caller_cancellation","coroutine_timeout"))stopped.compareAndSet("cancellation_unknown",source)
            if(source=="coroutine_timeout")stopped.compareAndSet("caller_cancellation",source)
        }
    }
    fun timeout(totalBudgetElapsed:Boolean):NetworkTimeoutDiagnostic {
        while(true){
            val before=stopped.get()
            if(before!="none")return NetworkTimeoutDiagnostic(before,false)
            val source=if(totalBudgetElapsed)"total_timeout"else"socket_timeout"
            if(stopped.compareAndSet(before,source))return NetworkTimeoutDiagnostic(source,true)
        }
    }
    fun fields():Map<String,Any?> = mapOf("request_id" to id,"cancel_source" to stopped.get(),"terminal_status" to terminal.get(),"response_bytes" to bytes.get(),
        "first_byte_ms" to first.get().takeIf{it!=0L}?.let{(it-started)/1_000_000},"last_byte_ms" to last.get().takeIf{it!=0L}?.let{(it-started)/1_000_000})
    fun stage(name:String,data:Map<String,Any?> = emptyMap())=Diagnostics.event(name,fields()+data,elapsed=(System.nanoTime()-started)/1_000_000)
    fun failure(name:String,error:Throwable,timeout:NetworkTimeoutDiagnostic?=null){
        val source=timeout?.source?:stopped.get();val decided=terminal.get()
        val cause=if(timeout?.primary==true)"primary_local_timeout"else when(source){"total_timeout","coroutine_timeout","socket_timeout"->"after_local_timeout";"caller_cancellation"->"after_local_cancellation";"cancellation_unknown"->"cancellation_origin_unknown";else->"unattributed"}
        stage(name,mapOf("error_type" to error.javaClass.simpleName,"cancel_source" to source,"terminal_status" to decided,"cause_relation" to cause,"after_terminal_decision" to (decided!=null)))
    }
    fun transferEnd(status:Int?){
        if(last.get()!=0L)stage("http.last_byte")
        stage("http.transfer_end",mapOf("http_status" to status))
    }
    fun count(input:InputStream):InputStream=object:FilterInputStream(input){
        private fun received(n:Int){if(n<=0)return;val now=System.nanoTime();bytes.addAndGet(n.toLong());last.set(now);if(first.compareAndSet(0,now))stage("http.first_byte")}
        override fun read():Int=`in`.read().also{if(it>=0)received(1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int=`in`.read(b,off,len).also{received(it)}
    }
}
