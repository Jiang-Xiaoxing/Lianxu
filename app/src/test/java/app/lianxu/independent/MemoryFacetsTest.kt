package app.lianxu.independent
import org.junit.Test
import org.junit.Assert.*
class MemoryFacetsTest {
    @Test fun tagsNormalizeWithoutTaxonomyInference(){assertEquals(listOf("旅行","Night walk"),MemoryFacets.parseTags("#旅行，旅行; Night  walk,night walk"));assertEquals(4,MemoryCategory.entries.count{it.modern});assertEquals(3,MemoryAbout.entries.size)}
    @Test fun subjectStateAndContentRemainIndependent(){MemoryCategory.entries.filter{it.modern}.forEach{c->MemoryAbout.entries.forEach{a->val m=StoredMemory("m","a",c,"Something",core=true,locked=true,manual=true,about=a,tags=listOf("custom"),dueDate="2000-01-01");assertEquals(MemoryLife.ACTIVE,m.life);assertTrue(MemoryFacets.matches(m,c,a,"custom","some"));assertFalse(MemoryFacets.matches(m,about=MemoryAbout.entries.first{it!=a}))}}}
    @Test fun legacyIsExplicitAndSearchDoesNotNeedCategory(){val old=StoredMemory("o","a",MemoryCategory.PROMISE,"Old source");assertTrue(MemoryFacets.matches(old,legacyOnly=true));assertNull(old.about);val m=StoredMemory("n","a",MemoryCategory.EXPERIENCE_MEMORY,"A quiet day",about=MemoryAbout.BOTH,tags=listOf("coffee"));assertTrue(MemoryFacets.matches(m,query="coffee"));assertFalse(MemoryFacets.matches(m,category=MemoryCategory.STATE_FACT))}
    @Test fun invalidTagAndDatesFailWithoutChangingState(){try{MemoryFacets.tags(List(13){"tag$it"});fail()}catch(_:IllegalArgumentException){};try{MemoryFacets.dueDate("tomorrow");fail()}catch(_:Exception){};assertEquals("2000-01-01",MemoryFacets.dueDate("2000-01-01"))}
}
