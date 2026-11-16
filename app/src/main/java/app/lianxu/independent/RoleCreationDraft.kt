package app.lianxu.independent

import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

internal enum class RoleCreationStep { NAME, DEFINITION, RELATIONSHIP, MORE, CONFIRM }

/** Presentation-only draft. No role is persisted before the final confirmation. */
internal data class RoleCreationDraft(
    val step:RoleCreationStep=RoleCreationStep.NAME,
    val name:String="",val definition:String="",val relationship:String="",
    val expression:String="",val opening:String="",val extra:String="",
    val creationId:String=UUID.randomUUID().toString()
){
    val hasContent get()=listOf(name,definition,relationship,expression,opening,extra).any{it.isNotEmpty()}
    fun problem():String?=when(step){
        RoleCreationStep.NAME->if(name.isBlank())"请先填写名字。"else null
        RoleCreationStep.DEFINITION->if(definition.isBlank())"请先写一点角色设定。"else null
        RoleCreationStep.CONFIRM->when{ name.isBlank()->"请先填写名字。";definition.isBlank()->"请先写一点角色设定。";else->null }
        else->null
    }
    fun next()=RoleCreationStep.entries.getOrNull(step.ordinal+1)
    fun previous()=RoleCreationStep.entries.getOrNull(step.ordinal-1)
}

internal val RoleCreationDraftSaver=listSaver<RoleCreationDraft,String>(
    save={listOf(it.step.name,it.name,it.definition,it.relationship,it.expression,it.opening,it.extra,it.creationId)},
    restore={v->runCatching{RoleCreationDraft(RoleCreationStep.valueOf(v[0]),v[1],v[2],v[3],v[4],v[5],v[6],v[7])}.getOrNull()}
)

/** Preserve complete supplementary characters when enforcing the existing UTF-16 limits. */
internal fun creationText(value:String,limit:Int)=value.take(limit).let{if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1)else it}
