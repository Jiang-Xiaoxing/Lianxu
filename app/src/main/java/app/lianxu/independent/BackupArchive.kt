package app.lianxu.independent

import com.google.gson.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

/** Portable archive. Compressed input is streamed; entries are never extracted or executed. */
internal object BackupArchive {
    const val MAX_BYTES=64*1024*1024
    const val MAX_EXPANDED=48*1024*1024
    const val MAX_JSON=8*1024*1024
    const val MAX_FILE=16*1024*1024
    private const val MAX_MANIFEST=1024*1024
    private val assetPath=Regex("assets/[0-9]+\\.bin")
    data class Package(val root:JsonObject,val files:Map<String,ByteArray>,val digest:String,val legacy:RoleArchive.Package?=null){
        val all get()=legacy==null&&root["scope"].asString=="all"
        val roles get()=root["roles"].asJsonArray
        val includesKeys get()=legacy==null&&root["includesKeys"].asBoolean
        override fun toString()="BackupPackage(redacted)"
    }
    fun bounded(input:InputStream,max:Int):ByteArray {
        require(max>=0){"备份大小限制无效"}
        val out=ByteArrayOutputStream(minOf(max,8192));val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer,0,minOf(buffer.size,max-out.size()+1));if(n<0)break
            require(out.size().toLong()+n<=max){"备份超过大小限制"};out.write(buffer,0,n)}
        return out.toByteArray()
    }
    /** Counts ZIP headers, compressed data, central directory and trailing input alike. */
    private class CountedInput(input:InputStream):FilterInputStream(input){
        var count=0L;private set
        override fun read():Int=`in`.read().also{if(it>=0)account(1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int =
            `in`.read(b,off,minOf(len.toLong(),MAX_BYTES-count+1).toInt()).also{if(it>0)account(it)}
        private fun account(n:Int){count+=n;require(count<=MAX_BYTES){"备份文件超过64 MiB"}}
    }
    fun read(input:InputStream):Package {
        val entries=linkedMapOf<String,ByteArray>();var expanded=0L;val source=CountedInput(input)
        ZipInputStream(source).use{z->
            while(true){val e=z.nextEntry?:break;val name=RoleArchive.entryName(e.name)
                require(!e.isDirectory&&entries.size<4096&&name !in entries&&
                    (name in setOf("manifest.json","backup.json","role.json")||assetPath.matches(name))){"备份含未知、重复或无效路径"}
                val limit=when(name){"manifest.json"->MAX_MANIFEST;"backup.json"->MAX_JSON;"role.json"->RoleArchive.MAX_BYTES;else->MAX_FILE}
                val data=bounded(z,minOf(limit,(MAX_EXPANDED-expanded).toInt()))
                expanded+=data.size;entries[name]=data;z.closeEntry()
            }
            // ZipInputStream stops at the central directory. Still bound any unread tail.
            val tail=ByteArray(8192);while(source.read(tail)>=0)Unit
        }
        val manifest=RoleArchive.parse(entries["manifest.json"]?:error("备份缺少清单"))
        if(manifest["format"]?.asString==RoleArchive.FORMAT)return legacy(entries,manifest,source.count,expanded)
        require(manifest.keySet()==setOf("format","version","files")&&manifest["format"].asString=="lianxu-backup"&&RoleArchive.integer(manifest["version"])==2L){"不支持的备份格式或版本"}
        val files=manifest["files"].asJsonArray
        require(files.size()==entries.size-1&&files.size() in 1..4095){"备份清单数量不符"}
        val seen=hashSetOf<String>()
        files.forEach{v->val f=v.asJsonObject;require(f.keySet()==setOf("path","size","sha256")){"备份清单字段不符"}
            val name=f["path"].asString
            require(seen.add(name)&&(name=="backup.json"||assetPath.matches(name))){"备份清单路径无效或重复"}
            val bytes=entries[name]?:error("备份缺少文件")
            require(RoleArchive.integer(f["size"])==bytes.size.toLong()&&f["sha256"].asString==RoleArchive.hash(bytes)){"备份校验失败"}}
        val root=RoleArchive.parse(entries["backup.json"]?:error("缺少备份资料"));validateRoot(root)
        return Package(root,entries.filterKeys{it.startsWith("assets/")},digest(entries))
    }
    /** Keep every v1 read rule, including its smaller compressed/expanded and manifest limits. */
    private fun legacy(entries:Map<String,ByteArray>,manifest:JsonObject,compressed:Long,expanded:Long):Package {
        require(compressed<=RoleArchive.MAX_BYTES&&expanded<=RoleArchive.MAX_BYTES){"旧角色包超过32 MiB"}
        require(entries.keys==setOf("manifest.json","role.json")&&entries.getValue("manifest.json").size<=8192){"旧角色包缺少清单、含额外文件或清单过大"}
        require(manifest.keySet()==setOf("format","version","scope","size","sha256")){"清单字段不支持"}
        require(RoleArchive.integer(manifest["version"])==1L){"不支持的角色包版本，请核对文件"}
        val raw=entries.getValue("role.json")
        require(RoleArchive.integer(manifest["size"])==raw.size.toLong()&&manifest["sha256"].asString==RoleArchive.hash(raw)){"角色资料校验失败"}
        val old=RoleArchive.validate(raw)
        require(old.scope==manifest["scope"].asString){"导出范围不一致"}
        return Package(JsonObject().apply{add("roles",JsonArray());addProperty("scope","role")},emptyMap(),digest(entries),old)
    }
    private fun validateRoot(root:JsonObject){
        require(root.keySet()==setOf("scope","roles","preferences","models","selections","includesKeys")){"备份资料字段不支持"}
        require(root["scope"].isJsonPrimitive&&root["scope"].asJsonPrimitive.isString&&root["scope"].asString in setOf("role","all")&&
            root["includesKeys"].isJsonPrimitive&&root["includesKeys"].asJsonPrimitive.isBoolean){"备份范围或密钥标识无效"}
        require(root["roles"].isJsonArray&&root["roles"].asJsonArray.size() in 0..100&&
            root["models"].isJsonArray&&root["models"].asJsonArray.size()<=100&&
            root["preferences"].isJsonObject&&root["selections"].isJsonObject){"备份资料类别或数量无效"}
        if(root["scope"].asString=="role")require(root["roles"].asJsonArray.size()==1&&root["models"].asJsonArray.isEmpty&&
            root["preferences"].asJsonObject.size()==0&&root["selections"].asJsonObject.size()==0&&!root["includesKeys"].asBoolean){"单角色包不能包含全局设置或密钥"}
    }
    private fun digest(entries:Map<String,ByteArray>):String {
        val hash=MessageDigest.getInstance("SHA-256")
        entries.toSortedMap().forEach{(name,data)->hash.update(name.toByteArray(Charsets.UTF_8));hash.update(0.toByte());hash.update(RoleArchive.hash(data).toByteArray(Charsets.US_ASCII))}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    fun write(root:JsonObject,assets:Map<String,ByteArray>,output:OutputStream){
        validateRoot(root)
        require(assets.size<=4094&&assets.keys.all{assetPath.matches(it)}&&assets.values.all{it.size<=MAX_FILE}){"备份资源路径、数量或大小无效"}
        val bytes=root.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=MAX_JSON){"备份文字资料超过8 MiB"}
        RoleArchive.parse(bytes) // Apply the same nesting/string/UTF-8 limits as the reader.
        val all=linkedMapOf("backup.json" to bytes).apply{putAll(assets)}
        val manifest=JsonObject().apply{addProperty("format","lianxu-backup");addProperty("version",2);add("files",JsonArray().apply{all.forEach{(name,data)->add(JsonObject().apply{addProperty("path",name);addProperty("size",data.size);addProperty("sha256",RoleArchive.hash(data))})}})}.toString().toByteArray(Charsets.UTF_8)
        require(manifest.size<=MAX_MANIFEST&&all.values.sumOf{it.size.toLong()}+manifest.size<=MAX_EXPANDED){"备份解压总量超过48 MiB"}
        val limited=object:FilterOutputStream(output){var count=0L
            override fun write(b:Int){require(++count<=MAX_BYTES){"备份文件超过64 MiB"};out.write(b)}
            override fun write(b:ByteArray,off:Int,len:Int){require(count+len<=MAX_BYTES){"备份文件超过64 MiB"};out.write(b,off,len);count+=len}}
        ZipOutputStream(limited).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(manifest);z.closeEntry()
            all.forEach{(name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}}
    }
}
