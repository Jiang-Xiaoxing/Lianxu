package app.lianxu.independent

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class NaturalReceiptTextTest {
    @Test fun controlResultsDescribeOnlyTheActionAndKeepDataAndCapsule(){
        for(action in listOf("forget","correct","stop_reminding")){
            val p=ExperienceProposal(control=action,targets=listOf(12,23),controlChecked=true)
            val before=Gson().toJson(p);val receipt=JointControl.receipt(p);val note=receipt["note"] as String
            assertEquals(mapOf("success" to true,"action" to action,"targets" to listOf(12L,23L),"status" to "applied"),receipt-"note")
            assertEquals(before,Gson().toJson(p));assertTrue(note.contains("原聊天仍保留"));assertFalse(note.contains("程序已执行"))
            when(action){
                "forget"->{assertTrue(note.contains("停止使用"));assertTrue(note.contains("没有永久删除"))}
                "correct"->{assertTrue(note.contains("没有把更正后的新内容写成记忆"));assertTrue(note.contains("实际写入结果"))}
                else->{assertTrue(note.contains("尚未发送"));assertTrue(note.contains("没有忘掉"))}
            }
            val item=ChatToolJournal.start(ChatToolCall("c",JointControl.NAME,"""{"action":"$action"}"""),1,"")
            assertEquals(ChatToolJournal.result(item,receipt+("note" to "程序已执行")),ChatToolJournal.result(item,receipt))
        }
    }
    @Test fun compactRecordKeepsStagingAndPartialOutcomeWithoutChangingToolData(){
        for(note in listOf(CompanionRecord.stagedNote,CompanionRecord.partialStagedNote))for(partial in listOf(false,true)){
            val output=mapOf<String,Any>("success" to true,"recorded" to true,"status" to "staged_until_reply_commit","note" to note)+(if(partial)mapOf("relation" to RelationReceipt.GIVEN_UP)else emptyMap())
            val before=Gson().toJson(output);val compact=CompanionRecord.receiptNote(output)
            assertTrue(compact.contains("已暂存，尚未正式保存"));assertTrue(compact.contains("回复失败或取消"))
            assertEquals(partial,compact.contains(RelationReceipt.GIVEN_UP));assertTrue(compact.contains(note))
            assertEquals(before,Gson().toJson(output));assertFalse(compact.contains("继续按人设自然回话"))
        }
    }
    @Test fun rejectedAndUndiagnosedCallsCannotBecomeSuccess(){
        val base=mapOf<String,Any>("success" to false,"error" to "invalid_arguments","hint" to "原修复提示")
        val missing=listOf(ToolArgumentDiagnosis.Problem("targets[0]","为可用来源编号","数字 999"))
        for(problems in listOf(emptyList(),missing)){
            val result=ToolArgumentDiagnosis.failure(base,IllegalArgumentException()){problems}
            base.forEach{(key,value)->assertEquals(value,result[key])}
            assertEquals(problems.map{mapOf("field" to it.field,"expected" to it.expected,"actual" to it.actual)},result["problems"])
            val detail=result["detail"] as String
            assertTrue(detail.contains(if(problems.isEmpty())"还不能确认已经完成" else "没有通过检查"))
        }
        assertNull(ControlPresentation.reference(null))
        assertEquals(ControlPresentation.NOT_COMPLETED,ControlPresentation.reference(ExperienceProposal(controlChecked=true))!!.text)
    }
}
