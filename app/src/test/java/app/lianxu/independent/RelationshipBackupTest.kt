package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RelationshipBackupTest {
    private fun archive():JsonObject {
        val root=JsonParser.parseString("""{"roles":[{"character":{"id":"r"},"tables":{"messages":[{"id":1,"body":"原话甲"},{"id":2,"body":"原话乙"}],"chat_turns":[{"user_message_id":1,"source_revision":1},{"user_message_id":2,"source_revision":1}],"message_state":[],"companion_sources":[]},"preferences":{}}]}""").asJsonObject
        val rows=root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject["companion_sources"].asJsonArray
        for(id in 1L..2){val input=RelationInput(SourceRef("r",id,1),id,0,"关于关系",p=.3,sourceHash=RelationshipBackup.hash(if(id==1L)"原话甲"else"原话乙"))
            rows.add(JsonObject().apply{addProperty("source_id",id);addProperty("revision",1);addProperty("proposal",Gson().toJson(ExperienceProposal(memory="独立事实",relationInput=input)))})
        }
        return root
    }
    @Test fun malformedVersionMissingSourceTruncationAndChangedTextOnlyDropTheirRelation(){
        val mutations=listOf<(JsonObject)->Unit>({it.addProperty("category","错")},{it.addProperty("formatVersion",99)},
            {it.getAsJsonObject("source").addProperty("turnId",999)}, {it.remove("at")}, {it.addProperty("sourceHash","已变更")},
            {it.add("previous",Gson().toJsonTree(SourceRef("r",999,1)));it.addProperty("correction","误会")})
        for(mutate in mutations){
            val root=archive();val rows=root["roles"].asJsonArray[0].asJsonObject["tables"].asJsonObject["companion_sources"].asJsonArray
            val first=rows[0].asJsonObject;val p=JsonParser.parseString(first["proposal"].asString).asJsonObject
            mutate(p["relationInput"].asJsonObject);first.addProperty("proposal",p.toString())
            val report=RelationshipBackup.sanitize(root);assertEquals(1,report.skipped);assertEquals("有 1 条关系记录对不上，已跳过",report.message)
            val restored=Gson().fromJson(first["proposal"].asString,ExperienceProposal::class.java)
            assertEquals("独立事实",restored.memory);assertNull(restored.relationInput)
            assertNotNull(Gson().fromJson(rows[1].asJsonObject["proposal"].asString,ExperienceProposal::class.java).relationInput)
        }
    }
    @Test fun goodArchiveHasNoSkipNoticeAndIsUnchanged(){
        val root=archive();val original=root.toString();val report=RelationshipBackup.sanitize(root)
        assertEquals(0,report.skipped);assertNull(report.message);assertEquals(original,root.toString())
    }
    @Test fun disposableRelationshipCacheIsRemovedWithoutCountingAsLostInput(){
        val root=archive();val prefs=root["roles"].asJsonArray[0].asJsonObject["preferences"].asJsonObject
        prefs.addProperty("relationship-cache:r","broken disposable cache")
        assertEquals(0,RelationshipBackup.sanitize(root).skipped)
        assertFalse(prefs.has("relationship-cache:r"))
    }
    @Test fun unknownHistoryVersionIsSkippedWithoutDiscardingMessages(){
        val root=archive();val role=root["roles"].asJsonArray[0].asJsonObject
        role["preferences"].asJsonObject.addProperty(RelationStorage.key("r"),"""{"version":99}""")
        assertEquals(1,RelationshipBackup.sanitize(root).skipped)
        assertEquals(2,role["tables"].asJsonObject["messages"].asJsonArray.size())
    }
    @Test fun anOldBackupStillCarryingTensionDaysIsReadAndTheFieldDropped(){
        val root=archive()
        val old=Gson().toJsonTree(RelationTemplate.DIRECT.parameters).asJsonObject.apply{addProperty("tensionDays",2.0)}
        val change=JsonObject().apply{addProperty("at",1);add("old",old);add("new",old.deepCopy());addProperty("label","旧备份");addProperty("afterMessage",0)}
        val history=JsonObject().apply{addProperty("version",1);add("changes",com.google.gson.JsonArray().apply{add(change)})}
        val prefs=root["roles"].asJsonArray[0].asJsonObject["preferences"].asJsonObject
        prefs.addProperty(RelationStorage.key("r"),history.toString())
        assertEquals("the extra old field is ignored, nothing is skipped",0,RelationshipBackup.sanitize(root).skipped)
        val saved=prefs[RelationStorage.key("r")].asString
        assertFalse(saved,"tensionDays" in saved)
        assertEquals(RelationTemplate.DIRECT.parameters,Gson().fromJson(saved,RelationHistory::class.java).changes.single().old)
        assertFalse("tensionDays" in RelationParameterRules.ranges)
    }
}
