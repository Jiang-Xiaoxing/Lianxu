package app.lianxu.independent

import com.google.gson.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.Assert.*

class RoleArchiveTest {
    private fun root()=JsonParser.parseString("""{"scope":"role","character":{"id":"synthetic","name":"林澈","bio":"25岁虚构成年人","prompt":"不编造共同经历","tone":0},"prompts":{},"records":{}}""").asJsonObject
    private fun archive(root:JsonObject)=ByteArrayOutputStream().also{RoleArchive.write(root,it)}.toByteArray()
    private fun fails(block:()->Unit){assertTrue(runCatching(block).isFailure)}
    @Test fun roundTripKeepsUnicodeWithoutExecutingPrompt(){val r=root();r["character"].asJsonObject.addProperty("prompt","只是文本：../../file；不执行脚本")
        val p=RoleArchive.read(archive(r).inputStream());assertEquals("林澈",p.name);assertEquals(r,p.root());assertEquals("role",p.scope)}
    @Test fun traversalAndAbsolutePathsRejected(){listOf("../role.json","/role.json","C:/role.json","a\\..\\role.json","a//role.json").forEach{fails{RoleArchive.entryName(it)}}}
    @Test fun duplicateJsonNamesAndTrailingDataRejected(){listOf("{\"a\":1,\"a\":2}","{} {}","{\"a\":{\"b\":1,\"b\":2}}").forEach{fails{RoleArchive.parse(it.toByteArray())}}}
    @Test fun excessiveNestingRejected(){fails{RoleArchive.parse(("{\"a\":".repeat(35)+"0"+"}".repeat(35)).toByteArray())}}
    @Test fun unknownCredentialFieldsRejected(){val r=root();r.addProperty("api_key","synthetic-no-secret");fails{archive(r)}}
    @Test fun stringsCannotPretendToBeVersionNumbers(){fails{RoleArchive.integer(JsonPrimitive("1"))};fails{RoleArchive.integer(JsonPrimitive(1.5))}}
    @Test fun fieldLimitsAndMissingNameRejected(){val r=root();r["character"].asJsonObject.addProperty("name","x".repeat(31));fails{archive(r)};r["character"].asJsonObject.remove("name");fails{archive(r)}}
    @Test fun unknownZipEntryRejectedBeforeContentIsUsed(){val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("../role.json"));z.write("{}".toByteArray());z.closeEntry()}}.toByteArray();fails{RoleArchive.read(bytes.inputStream())}}
    @Test fun archiveHashMismatchAndBadVersionRejected(){
        for(version in listOf(1,2)){val raw=root().toString().toByteArray();val m="""{"format":"lianxu-role","version":$version,"scope":"role","size":${raw.size},"sha256":"wrong"}"""
            val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->listOf("manifest.json" to m.toByteArray(),"role.json" to raw).forEach{(n,b)->z.putNextEntry(ZipEntry(n));z.write(b);z.closeEntry()}}}.toByteArray();fails{RoleArchive.read(bytes.inputStream())}}
    }
    @Test fun oversizedStreamRejected(){fails{RoleArchive.read(ByteArray(RoleArchive.MAX_BYTES+1).inputStream())}}
    @Test fun foreignRoleOwnershipRejected(){val r=root();r.addProperty("scope","records");val data=r["records"].asJsonObject;RoleArchive.tables.keys.forEach{data.add(it,JsonArray())}
        data["messages"].asJsonArray.add(JsonParser.parseString("""{"id":1,"friend_id":"another-role","body":"test","mine":1,"created":0}"""));fails{archive(r)}}
}
