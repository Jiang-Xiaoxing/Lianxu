package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun Header(title:String,back:()->Unit,extra:(@Composable ()->Unit)?=null){
    AppTopBar(title,back,"back",extra=extra)
}
@Composable internal fun AppTopBar(title:String,onBack:()->Unit,backTag:String="back",backDescription:String="返回",enabled:Boolean=true,extra:(@Composable ()->Unit)?=null){
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).background(Paper).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick=onBack,enabled=enabled,modifier=Modifier.size(UiSpace.action).testTag(backTag).semantics{contentDescription=backDescription}){ControlIcon("back",Ink)}
        Text(title,Modifier.weight(1f).padding(horizontal=8.dp,vertical=12.dp),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
        extra?.invoke()
    }
}
@Composable internal fun Avatar(friend:Friend,size:Int=48){
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size/3).dp)).background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center){Text(friend.name.take(1),style=MaterialTheme.typography.titleMedium,color=Muted)}
}
@Composable internal fun PrimaryAction(text:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit){
    Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=UiSpace.action),shape=RoundedCornerShape(16.dp),colors=freshButtonColors(),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){Text(text,style=MaterialTheme.typography.labelLarge)}
}
@Composable internal fun CompactSaveAction(enabled:Boolean,onClick:()->Unit){
    Box(Modifier.widthIn(min=76.dp).heightIn(min=52.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick).testTag("config-save").semantics{contentDescription="保存配置"},contentAlignment=Alignment.Center){
        Text("保存",Modifier.clip(RoundedCornerShape(8.dp)).background(SelectedBackground).padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge,color=GreenText.copy(alpha=if(enabled)1f else .4f))
    }
}
@Composable internal fun AppField(value:String,onChange:(String)->Unit,label:String,tag:String="",singleLine:Boolean=false,minLines:Int=1){
    TextField(value,onChange,label={Text(label)},singleLine=singleLine,minLines=minLines,maxLines=if(singleLine)1 else 6,modifier=Modifier.fillMaxWidth().testTag(tag),shape=RoundedCornerShape(16.dp),textStyle=MaterialTheme.typography.bodyLarge,colors=TextFieldDefaults.colors(focusedContainerColor=Paper,unfocusedContainerColor=Paper,focusedIndicatorColor=GreenText,unfocusedIndicatorColor=Line))
}
