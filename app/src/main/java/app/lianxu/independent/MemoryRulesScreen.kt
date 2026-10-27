package app.lianxu.independent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable internal fun MemoryRulesScreen(vm:AppState){
    var recent by rememberSaveable{mutableStateOf("")};var summary by rememberSaveable{mutableStateOf("")};var related by rememberSaveable{mutableStateOf("")}
    var recall by rememberSaveable{mutableStateOf(false)};var write by rememberSaveable{mutableStateOf(false)}
    var rounds by rememberSaveable{mutableStateOf("3")};var tokens by rememberSaveable{mutableStateOf("4000")};var perCall by rememberSaveable{mutableStateOf("1500")}
    var ready by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var status by rememberSaveable{mutableStateOf("")};var failed by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){val r=vm.processing.memoryRules().first;if(recent.isEmpty()){recent=r.recentRounds.toString();summary=r.summaryRounds.toString();related=r.relatedCount.toString();recall=r.proactiveRecall;write=r.immediateWrite;rounds=r.recallRounds.toString();tokens=r.recallTokens.toString();perCall=r.recallPerCall.toString()};ready=true}
    fun save(){if(busy||!ready)return;val r=MemoryRules(recent.toIntOrNull()?:0,summary.toIntOrNull()?:0,related.toIntOrNull()?:0,recall,write,rounds.toIntOrNull()?:0,tokens.toIntOrNull()?:0,perCall.toIntOrNull()?:0)
        if(!r.valid()){failed=true;status="请检查正整数及范围：总结记忆轮数不超过近期轮数；回查1–100轮，总额256–64000，单次128至总额。";return}
        busy=true;failed=false;status="";scope.launch{try{vm.processing.saveMemoryRules(r);vm.refreshProcessing();status="已保存，所有AI使用这套规则。"}catch(_:Exception){failed=true;status="保存未完成，请重试。"}finally{busy=false}}
    }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("memory-rules-page")){
        AppTopBar("记忆规则",{vm.back()},extra={TextButton({save()},enabled=ready&&!busy,modifier=Modifier.heightIn(min=48.dp).testTag("memory-rules-save")){Text(if(busy)"保存中" else "保存")}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("按轮次保留，按条数回想",style=MaterialTheme.typography.titleLarge)
            Text("适用于所有AI，记忆内容仍各自独立。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            AppField(recent,{recent=it.take(10);status=""},"近期原话（轮）","rules-recent",true)
            Text("超过这个完整轮数后，总结最早的一批记忆。一轮是用户连续发言及其首条有效回复；正在输入或未完成的回复不计入。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(summary,{summary=it.take(10);status=""},"每批总结记忆（轮）","rules-summary",true)
            Text("仅在整批保存和来源校验成功后移出近期输入，聊天原文不删除。失败保留进度，可重试。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(related,{related=it.take(10);status=""},"相关记忆最多（条）","rules-related",true)
            Text("直接命中与可用关联补充按记忆去重，共用上限，不凑数。核心记忆单独保留。使用关键词、主题标签及有证据的两跳关联；向量仅在已配置时启用。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Row{Text("主动回查",Modifier.weight(1f));Switch(recall,{recall=it},enabled=!busy,modifier=Modifier.testTag("rules-recall-enabled"))}
            Row{Text("当场写记忆",Modifier.weight(1f));Switch(write,{write=it},enabled=!busy,modifier=Modifier.testTag("rules-write-enabled"))}
            Text("默认关闭；仅向已启用的功能发送工具定义。写入随最终回复提交，人工与锁定内容仍需复核。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(rounds,{rounds=it.take(5)},"回查最多轮数（1–100）","rules-recall-rounds",true)
            AppField(tokens,{tokens=it.take(6)},"回查总额度（256–64000 Token）","rules-recall-tokens",true)
            AppField(perCall,{perCall=it.take(6)},"回查单次额度（128至总额）","rules-recall-per-call",true)
            HorizontalDivider(color=Line)
            Text("容量不足时会明确提示",style=MaterialTheme.typography.titleSmall)
            Text("模型总容量仍有限。装不下完整原话、相关记忆或总结记忆批次时，请选择更大容量模型，或主动调整上述数量；不会暗中缩小批次或截断核心。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("保存本身不联网。修改会使正在运行的旧请求失效；已开始的固定批次保留，手动重试先补齐该批，再按新规则总结记忆。",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(status.isNotEmpty())Text(status,Modifier.testTag("memory-rules-status"),style=MaterialTheme.typography.bodyMedium,color=if(failed)MaterialTheme.colorScheme.error else GreenText)
        }
    }
}
