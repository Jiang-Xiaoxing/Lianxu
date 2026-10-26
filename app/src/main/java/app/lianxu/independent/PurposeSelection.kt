package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable internal fun PurposeSelection(state:ModelConfigState){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("模型用途",style=MaterialTheme.typography.titleSmall,color=Ink)
        Text("所有角色共用用途选择，不设角色单独覆盖。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)){
            ModelPurpose.entries.forEachIndexed{index,purpose->
                var menu by remember(purpose){mutableStateOf(false)}
                val chosen=state.configs.firstOrNull{it.id==state.purposes[purpose]}
                Box{
                    Row(Modifier.fillMaxWidth().heightIn(min=72.dp).clickable(enabled=!state.busy,role=Role.Button){menu=true}.testTag("purpose-select-${purpose.key}").padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                        ControlIcon(if(purpose==ModelPurpose.MEMORY)"storage" else "model");Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(purpose.title,style=MaterialTheme.typography.titleSmall,color=Ink)
                            Text(chosen?.let{"${it.name} · ${it.modelId}"}?:"尚未选择",style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                        ControlIcon("chevron")
                    }
                    DropdownMenu(menu,{menu=false}){
                        DropdownMenuItem(text={Text("不选择")},onClick={menu=false;state.selectPurpose(purpose,"")},modifier=Modifier.testTag("purpose-clear-${purpose.key}"))
                        state.configs.forEach{config->DropdownMenuItem(text={Column{Text(config.name);Text(config.modelId,style=MaterialTheme.typography.bodySmall)}},onClick={menu=false;state.selectPurpose(purpose,config.id)},modifier=Modifier.testTag("purpose-option-${purpose.key}-${config.id}"))}
                    }
                }
                if(index<ModelPurpose.entries.lastIndex)HorizontalDivider(Modifier.padding(start=64.dp,end=16.dp),color=Line)
            }
        }
        Text("选择用途只保存设置，不发出请求。发送文字、主动整理和已开启的后台评估会使用对应模型；视觉附件尚未接入。",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}
