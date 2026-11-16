package app.lianxu.independent

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class RoleCreationDraftTest {
    @Test fun requiredFieldsAreCheckedBeforeContinuing(){
        assertNotNull(RoleCreationDraft(name="  ").problem())
        assertNull(RoleCreationDraft(name="中性名字").problem())
        assertNotNull(RoleCreationDraft(step=RoleCreationStep.DEFINITION,definition="\n ").problem())
        assertNull(RoleCreationDraft(step=RoleCreationStep.RELATIONSHIP).problem())
        assertNull(RoleCreationDraft(step=RoleCreationStep.MORE).problem())
        assertNotNull(RoleCreationDraft(step=RoleCreationStep.CONFIRM,name="名字").problem())
        assertNull(RoleCreationDraft(step=RoleCreationStep.CONFIRM,name="名字",definition="设定").problem())
    }
    @Test fun savedDraftRestoresEveryFieldAndTheCurrentStep(){
        val draft=RoleCreationDraft(RoleCreationStep.MORE,"名字","长设定".repeat(2000),"旧识","表达","开场","自定义")
        val scope=SaverScope{true}
        val saved=with(RoleCreationDraftSaver){with(scope){save(draft)}}
        assertEquals(draft,RoleCreationDraftSaver.restore(requireNotNull(saved)))
    }
    @Test fun invalidSavedStepDoesNotCrashRestoration(){
        assertNull(RoleCreationDraftSaver.restore(listOf("obsolete","","","","","","")))
        assertNull(RoleCreationDraftSaver.restore(emptyList<String>()))
    }
    @Test fun blankInitialDraftCanExitWithoutADataLossPrompt(){
        assertFalse(RoleCreationDraft().hasContent)
        assertTrue(RoleCreationDraft(extra="保留附加内容").hasContent)
    }
    @Test fun aLengthBoundaryNeverKeepsHalfAnEmoji(){
        for(limit in listOf(30,4000,8000)){
            val prefix="字".repeat(limit-1)
            assertEquals(prefix,creationText(prefix+"😀",limit))
            assertEquals(prefix.dropLast(1)+"😀",creationText(prefix.dropLast(1)+"😀",limit))
        }
    }
}
