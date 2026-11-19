package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 一个语音服务商在列表里的样子。以后接第二家只往列表里加一项，界面不用重写。 */
internal data class VoiceProviderSummary(
    val id: String,
    val title: String,
    val connected: Boolean
)

/** 列表里的一行：名字 + 右侧状态 + 小胶囊按钮。 */
@Composable internal fun VoiceProviderRow(
    provider: VoiceProviderSummary,
    onAction: () -> Unit
) {
    val action = if (provider.connected) "管理" else "连接"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            provider.title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = Ink
        )
        if (provider.connected) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(GreenText))
            Spacer(Modifier.width(5.dp))
            Text("已连接", style = MaterialTheme.typography.bodySmall, color = GreenText)
        } else {
            Text("未连接", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.clip(CircleShape).background(Accent)
                .clickable(onClick = onAction)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("voice-action-${provider.id}")
                .semantics { contentDescription = "${provider.title} $action" },
            contentAlignment = Alignment.Center
        ) {
            Text(action, style = MaterialTheme.typography.labelMedium, color = GreenText)
        }
    }
}

/**
 * 「语音服务」分组：一行一个服务商，未连接只说「未连接」，点开才出去底部面板。
 * 目前底层只有 BreezeBlue 一家，列表里就只有一个元素；不用语音的人只看到这一行和一句说明。
 */
@Composable internal fun BreezeVoiceServiceSection() {
    val account = rememberBreezeAccount()
    val catalog by account.catalogChanges.collectAsState(Triple(account.accountId(), 0L, false))
    val connected = catalog.third
    var sheetOpen by remember { mutableStateOf(false) }

    val providers = remember(connected) {
        listOf(VoiceProviderSummary("breezeblue", "BreezeBlue 语音", connected))
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpace.small)) {
        SectionLabel("语音服务")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Paper)
        ) {
            providers.forEachIndexed { index, provider ->
                VoiceProviderRow(provider) { sheetOpen = true }
                if (index < providers.lastIndex) HorizontalDivider(color = Line, thickness = .5.dp)
            }
        }
        Text("用于角色发语音，不影响文字聊天", style = MaterialTheme.typography.bodySmall, color = Muted)
    }

    if (sheetOpen) {
        BreezeVoiceConnectSheet(account, connected) { sheetOpen = false }
    }
}
