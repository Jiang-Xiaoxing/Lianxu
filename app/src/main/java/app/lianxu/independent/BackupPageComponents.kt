package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable internal fun BackupTabs(tabs: PageTabs, enabled: Boolean) {
    SlidingPageTabs(tabs, listOf("备份与恢复", "诊断日志"), listOf("backup-tab-data", "backup-tab-diagnostics"),
        "backup-tab-indicator", enabled)
}

@Composable internal fun SlidingPageTabs(tabs: PageTabs, labels: List<String>, tags: List<String>,
    indicator: String, enabled: Boolean = true, inactive: Color = Muted,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = UiSpace.page, vertical = 12.dp)
        .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp)) {
        val width = maxWidth / labels.size
        val position = (tabs.pager.currentPage + tabs.pager.currentPageOffsetFraction).coerceIn(0f, labels.lastIndex.toFloat())
        Box(Modifier.matchParentSize()) {
            Box(Modifier.offset(x = width * position).width(width).fillMaxHeight()
                .background(Paper, RoundedCornerShape(24.dp)).testTag(indicator))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
            labels.forEachIndexed { index, title ->
                Tab(tabs.selected == index, { tabs.select(index) }, enabled = enabled,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = UiSpace.action).clip(RoundedCornerShape(24.dp)).testTag(tags[index]),
                    selectedContentColor = Ink, unselectedContentColor = inactive,
                    text = { Text(title, color = softColor(if (tabs.selected == index) Ink else inactive),
                        style = labelStyle, maxLines = 1) })
            }
        }
    }
}

@Composable internal fun BackupActionRow(icon:String,title:String,description:String,tag:String,enabled:Boolean,action:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper).clickable(enabled=enabled,role=Role.Button,onClick=action).testTag(tag).padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        if(icon=="person")NavigationIcon(3,false)else ControlIcon(icon,Muted,26.dp)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(title,style=MaterialTheme.typography.titleSmall,color=Ink)
            Text(description,style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
}

@Composable internal fun BackupDiagnosticToggle(value:Boolean,enabled:Boolean,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiSpace.radius)).background(Paper).toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=change).testTag("backup-detailed").padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
        NavigationIcon(2,false)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("导出详细内容",style=MaterialTheme.typography.titleSmall,color=Ink)
            Text(if(value) "导出前会再次确认，文件可能含聊天原文" else "简版仅含排错摘要",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Switch(value,null)
    }
}

@Composable internal fun BackupHelpDialog(diagnostics:Boolean,close:()->Unit){
    AlertDialog(onDismissRequest=close,title={Text(if(diagnostics)"诊断日志说明"else"备份与恢复说明")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()).testTag("backup-help-content"),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(diagnostics){
            Text("日志仅在本机滚动保留最近7天、最多10 MiB，不自动上传。密钥和令牌始终脱敏。")
            Text("应用运行时会在本机记录聊天、记忆、发给模型的提示词、返回内容和连接状态，遇到问题后可直接导出。")
            Text("“导出详细内容”只选择文件内容。详细文件可能含私密原文，确认后才生成；简版只导出排错摘要。")
        }else{
            Text("单角色备份带出该角色全部已保存的人设、提示词、聊天、记忆与来源、候选及关联、关系及快照、语气示例和资源，不含其他角色、本人头像或全局密钥。")
            Text("全部备份包含全部角色、资源和软件设置、API服务及模型配置。不包含密钥也会保留服务和模型设置。包含密钥时需要备份密码，只有密钥使用密码加密；请保管好备份和密码。")
            Text("导入先明确选择新增或覆盖，再预览范围并确认。旧格式缺少的资料无法凭空补齐；导入不会恢复在途任务或自动调用模型。已保存资源可随包保留，不代表附件发送或图片理解已经接入。")
        }
    }},confirmButton={TextButton(close,modifier=Modifier.heightIn(min=UiSpace.action).testTag("backup-help-close")){Text("知道了")}})
}

@Composable internal fun BackupRoleDialog(friends:List<Friend>,selected:String,select:(String)->Unit,close:()->Unit,export:()->Unit){
    AlertDialog(onDismissRequest=close,title={Text("备份单个角色")},text={Column(Modifier.heightIn(max=360.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("选择要备份的角色。导出该角色全部已保存资料和资源，不含其他角色、本人头像或全局密钥。",style=MaterialTheme.typography.bodySmall,color=Muted)
        if(friends.isEmpty())Text("还没有可备份的角色。",style=MaterialTheme.typography.bodyMedium,color=Muted)
        if(friends.isNotEmpty())LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).selectableGroup().testTag("backup-role-list"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        itemsIndexed(friends,key={_,friend->friend.id}){index,friend->val chosen=selected==friend.id
            Row(Modifier.fillMaxWidth().heightIn(min=UiSpace.action).selectable(selected=chosen,role=Role.RadioButton,onClick={select(friend.id)}).testTag("archive-export-${friend.id}").padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                Avatar(friend,44,Modifier.clearAndSetSemantics{})
                Text(friend.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,color=Ink,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                RadioButton(chosen,null)
            }
            if(index<friends.lastIndex)HorizontalDivider(color=Line,thickness=.5.dp)
        }
        }
    }},confirmButton={TextButton(export,enabled=friends.any{it.id==selected},modifier=Modifier.heightIn(min=UiSpace.action).testTag("archive-export")){Text("导出")}},dismissButton={TextButton(close,modifier=Modifier.heightIn(min=UiSpace.action)){Text("取消")}})
}
