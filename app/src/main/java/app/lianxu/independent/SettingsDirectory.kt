package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val SettingsPaper:androidx.compose.ui.graphics.Color @Composable get()=Chrome
private val SettingsInk:androidx.compose.ui.graphics.Color @Composable get()=Ink
private val SettingsMuted:androidx.compose.ui.graphics.Color @Composable get()=Muted
private val SettingsLine:androidx.compose.ui.graphics.Color @Composable get()=Line

@Composable internal fun SettingsDirectory(vm:AppState,models:ModelConfigState=viewModel()) {
    val current=models.configs.firstOrNull {it.id==models.defaultId}
    val summary=when {
        current!=null->"当前默认：${current.name} · ${current.modelId}"
        models.statusError&&models.status.isNotBlank()&&models.configs.isEmpty()->"请进入查看配置提示"
        models.configs.isNotEmpty()->"尚未选择默认模型"
        else->"添加服务商并选择默认模型"
    }
    Column(Modifier.fillMaxSize().background(SettingsPaper).verticalScroll(rememberScrollState()).padding(UiSpace.page).testTag("settings-home"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("按用途找到需要的设置",style=MaterialTheme.typography.bodyMedium,color=SettingsMuted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)) {
            DirectoryRow("model","大模型配置",summary,"settings-models","settings-model-summary"){vm.open("models")}
            HorizontalDivider(Modifier.padding(start=68.dp,end=18.dp),color=SettingsLine)
            DirectoryRow("storage","记忆与关系","分角色查看原话、记忆与六维关系","settings-processing"){vm.open("processing")}
            HorizontalDivider(Modifier.padding(start=68.dp,end=18.dp),color=SettingsLine)
            DirectoryRow("storage","备份与诊断","完整角色、全部备份与诊断日志","settings-role-archive"){vm.open("role-archive")}
            HorizontalDivider(Modifier.padding(start=68.dp,end=18.dp),color=SettingsLine)
            DirectoryRow("list","显示与列表","调整聊天和通讯录的行间距","settings-display"){vm.open("settings-display")}
            HorizontalDivider(Modifier.padding(start=68.dp,end=18.dp),color=SettingsLine)
            DirectoryRow("info","数据与版本","本地数据、演示能力与版本信息","settings-info"){vm.open("settings-info")}
        }
    }
}

@Composable private fun DirectoryRow(icon:String,title:String,description:String,tag:String,descriptionTag:String="",onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onClick).testTag(tag).padding(horizontal=18.dp,vertical=20.dp),verticalAlignment=Alignment.CenterVertically) {
        ControlIcon(icon);Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text(title,color=SettingsInk,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium)
            Text(description,Modifier.then(if(descriptionTag.isEmpty())Modifier else Modifier.testTag(descriptionTag)),style=MaterialTheme.typography.bodySmall,color=SettingsMuted,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp));ControlIcon("chevron")
    }
}

@Composable private fun SettingsPage(title:String,onBack:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().background(SettingsPaper)) {
        AppTopBar(title,onBack,"settings-back","返回设置")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
    }
}

@Composable internal fun DisplaySettings(vm:AppState) {
    SettingsPage("显示与列表",{vm.back()}) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper).testTag("settings-display-page")) {
            Row(Modifier.fillMaxWidth().toggleable(value=vm.compact,enabled=!vm.busy,role=Role.Switch,onValueChange={vm.updateCompact(it)}).testTag("compact-switch").padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                ControlIcon("list");Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("紧凑列表",style=MaterialTheme.typography.titleSmall,color=SettingsInk)
                    Text("缩小聊天和通讯录的行间距",style=MaterialTheme.typography.bodySmall,color=SettingsMuted)
                }
                Switch(vm.compact,null,enabled=!vm.busy,colors=SwitchDefaults.colors(checkedTrackColor=Accent,checkedThumbColor=MaterialTheme.colorScheme.onSecondaryContainer,uncheckedTrackColor=Chrome,uncheckedThumbColor=SettingsMuted,uncheckedBorderColor=Outline))
            }
        }
        Text("更改会自动保存在本机。",style=MaterialTheme.typography.bodySmall,color=SettingsMuted)
    }
}

@Composable internal fun DataAndVersion(onBack:()->Unit) {
    val context=LocalContext.current
    val version=remember(context){runCatching{context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()}.getOrDefault("暂时无法读取")}
    SettingsPage("数据与版本",onBack) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper).testTag("settings-info-page")) {
            InformationRow("storage","本地数据","角色、消息与配置保存在本机应用空间。API Key 加密保存且不参与备份；卸载应用会清除本地数据。")
            HorizontalDivider(Modifier.padding(start=68.dp,end=18.dp),color=SettingsLine)
            InformationRow("model","当前能力","文字聊天使用统一聊天模型；记忆按完整轮次总结记忆，关系评估默认关闭。发送与已启用的总结记忆/评估会请求你配置的服务。搜索AI仍为本地演示，视觉附件未开放；主动回查与当场写记忆可在记忆规则中分别启用，默认关闭。")
            HorizontalDivider(Modifier.padding(start=68.dp,end=18.dp),color=SettingsLine)
            InformationRow("info","应用版本",version,"settings-version")
        }
    }
}

@Composable private fun InformationRow(icon:String,title:String,description:String,tag:String="") {
    Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.Top) {
        ControlIcon(icon);Spacer(Modifier.width(14.dp))
        Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,color=SettingsInk)
            Text(description,Modifier.then(if(tag.isEmpty())Modifier else Modifier.testTag(tag)),style=MaterialTheme.typography.bodySmall,color=SettingsMuted)
        }
    }
}
