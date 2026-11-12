package app.lianxu.independent
import org.junit.Test
import org.junit.Assert.*
class ModelKeyDraftTest {
    private val config=ModelConfig("test","test","deepseek","https://api.deepseek.com","test")
    @Test fun savedKeyIsReadAndUnchangedSaveDoesNotWrite(){
        val key=ModelKeyDraft.resolve(config,config,""){"synthetic"}
        assertEquals("synthetic",key)
        assertFalse(ModelKeyDraft.needsWrite(config,config,key,key))
    }
    @Test fun replacementWinsAndRequiresWrite(){
        val key=ModelKeyDraft.resolve(config,config,"new"){error("must not read")}
        assertEquals("new",key)
        assertTrue(ModelKeyDraft.needsWrite(config,config,"old",key))
    }
    @Test fun changedTargetAndNewConfigCannotReadOldKey(){
        for(draft in listOf(config.copy(baseUrl="https://other.invalid"),config.copy(providerId="custom"))){
            assertEquals("",ModelKeyDraft.resolve(config,draft,""){error("must not read")})
        }
        assertEquals("",ModelKeyDraft.resolve(null,config,""){error("must not read")})
    }
    @Test fun inputOrNavigationInvalidatesEvenWhenInputIsCleared(){
        val draft=ModelKeyDraft();val first=draft.ticket()
        assertTrue(draft.accepts(first));draft.invalidate();assertFalse(draft.accepts(first))
        val next=draft.ticket();draft.invalidate();assertFalse(draft.accepts(next))
    }
}
