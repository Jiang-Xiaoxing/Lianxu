package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*

class PurposeOptionsTest {
    private val vector=ModelConfig("vector","已核对向量","openai",ProviderPresets.get("openai").base,"text-embedding-3-small")
    private val unknown=ModelConfig("unknown","未知配置","custom","https://example.invalid/v1","synthetic-model")
    private fun options(configs:List<ModelConfig>,current:String="",include:Boolean=false)=purposeOptions(configs,ModelPurpose.EMBEDDING,current,include)
    @Test fun emptyListHasNoOtherOption(){val result=options(emptyList());assertTrue(result.visible.isEmpty());assertFalse(result.hasAdditionalUnknown)}
    @Test fun onlyKnownEmbeddingHasNoOtherOption(){assertEquals(ProviderModelKind.TEXT_EMBEDDING,ProviderCatalog.modelKind(vector));val result=options(listOf(vector));assertEquals(listOf(vector),result.visible);assertFalse(result.hasAdditionalUnknown)}
    @Test fun unlistedUnknownGetsAnExpansionAndBecomesSelectable(){val result=options(listOf(vector,unknown));assertEquals(listOf(vector),result.visible);assertTrue(result.hasAdditionalUnknown);val all=options(listOf(vector,unknown),include=true);assertEquals(listOf(vector,unknown),all.visible);assertFalse(all.hasAdditionalUnknown)}
    @Test fun selectedUnknownIsKeptWithoutOfferingItAgain(){val result=options(listOf(unknown),unknown.id);assertEquals(listOf(unknown),result.visible);assertFalse(result.hasAdditionalUnknown);assertTrue(options(listOf(unknown,unknown.copy(id="second")),unknown.id).hasAdditionalUnknown)}
    @Test fun candidatesAreUniqueBySavedConfigurationId(){val result=options(listOf(vector,vector,unknown,unknown),unknown.id,true);assertEquals(listOf(vector,unknown),result.visible);assertFalse(result.hasAdditionalUnknown)}
    @Test fun knownOtherProtocolIsNotAdvertisedAsUnknown(){val other=ModelConfig("other","其他协议","bailian-cn",ProviderPresets.get("bailian-cn").base,"qwen3-vl-embedding");assertEquals(ProviderModelKind.OTHER_PROTOCOL,ProviderCatalog.modelKind(other));assertFalse(options(listOf(other)).hasAdditionalUnknown);assertEquals(listOf(other),options(listOf(other),other.id).visible)}
}
