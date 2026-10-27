package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class MemoryPresentationTest {
    @Test fun allOriginalCategoriesStayVisibleAndLabeledWithoutReclassification(){
        val rows=MemoryCategory.entries.filterNot{it.modern}.map{c->StoredMemory(c.name,"a",c,"保留的合成原文",locked=true,manual=true)}
        val before=rows.map{it.copy()}
        assertEquals(6,rows.filter{MemoryFacets.matches(it)}.size)
        rows.forEach {m->
            assertTrue(MemoryFacets.categoryLabel(m).startsWith("未归类 · 原分类："))
            assertFalse(MemoryFacets.categoryLabel(m).contains("旧版兼容"))
            assertNull(m.about);assertTrue(m.locked&&m.manual)
            assertTrue(MemoryFacets.matches(m,query=m.category.label.removePrefix("旧版：")))
        }
        assertEquals(before,rows)
    }
    @Test fun explicitFiltersDoNotInventSubjectsForUnclassifiedRows(){
        val old=StoredMemory("old","a",MemoryCategory.USER_PROFILE,"合成资料")
        assertTrue(MemoryFacets.matches(old));assertFalse(MemoryFacets.matches(old,about=MemoryAbout.USER))
        assertFalse(MemoryFacets.matches(old,category=MemoryCategory.STATE_FACT));assertEquals(MemoryCategory.USER_PROFILE,old.category)
    }
}
