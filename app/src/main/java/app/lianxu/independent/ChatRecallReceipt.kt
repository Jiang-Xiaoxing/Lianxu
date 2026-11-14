package app.lianxu.independent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Same capsule geometry as other receipts; only the recall status palette and grouping differ. */
@Composable internal fun ChatRecallReceipt(group:RecallReceiptGroup,modifier:Modifier=Modifier){
    var expanded by remember(group.id){mutableStateOf(false)}
    val dark=Paper.luminance()<.5f
    val failureText=if(dark)Color(0xFFE9B6B1) else Color(0xFF915753)
    val successText=if(dark)Color(0xFFAFC9B5) else Color(0xFF4E7058)
    val foreground=when{group.hasFailure->failureText;group.succeeded->successText;else->Muted}
    val background=when{
        group.hasFailure->if(dark)Color(0xFF493230)else Color(0xFFF8EDEC)
        group.succeeded->if(dark)Color(0xFF29382E)else Color(0xFFEAF1E9)
        else->Paper
    }
    Column(modifier.testTag("recall-group-${group.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Surface(shape=RoundedCornerShape(50),color=background,border=BorderStroke(.5.dp,Line),modifier=Modifier.widthIn(max=280.dp).testTag("tool-result-${group.id}")){
            Row(Modifier.clickable(role=Role.Button){expanded=!expanded}.testTag("recall-toggle-${group.id}")
                .semantics{contentDescription=if(expanded)"收起回想结果"else"查看回想结果";stateDescription=if(expanded)"已展开"else"已收起"}
                .padding(start=12.dp,end=8.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                Text(group.label(),Modifier.weight(1f,fill=false),style=MaterialTheme.typography.labelMedium,color=foreground)
                Box(Modifier.rotate(if(expanded)0f else 180f)){ControlIcon("up",foreground,14.dp)}
            }
        }
        if(expanded)Surface(Modifier.widthIn(max=280.dp).testTag("recall-results-${group.id}"),shape=RoundedCornerShape(14.dp),color=Paper,border=BorderStroke(.5.dp,Line)){
            Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                group.items.forEachIndexed{index,item->
                    val color=when(item.recallOutcome()){RecallDisplayOutcome.FOUND->successText;RecallDisplayOutcome.NOT_FOUND,RecallDisplayOutcome.ERROR->failureText;else->Muted}
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text("${index+1}. ${item.recallOutcome().label}",Modifier.testTag("recall-outcome-${item.id}"),style=MaterialTheme.typography.bodyMedium,color=color)
                        if(item.detail.isNotBlank())Text(item.detail,style=MaterialTheme.typography.bodySmall,color=Muted)
                    }
                }
            }
        }
    }
}
