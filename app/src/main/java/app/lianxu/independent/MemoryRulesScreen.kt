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
    var ready by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var status by rememberSaveable{mutableStateOf("")};var failed by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){val r=vm.processing.memoryRules().first;if(recent.isEmpty()){recent=r.recentRounds.toString();summary=r.summaryRounds.toString();related=r.relatedCount.toString()};ready=true}
    fun save(){if(busy||!ready)return;val r=MemoryRules(recent.toIntOrNull()?:0,summary.toIntOrNull()?:0,related.toIntOrNull()?:0)
        if(!r.valid()){failed=true;status="请填写正整数；每批整理轮数不能大于近期原话轮数。";return}
        busy=true;failed=false;status="";scope.launch{try{vm.processing.saveMemoryRules(r);vm.refreshProcessing();status="已保存，所有朋友使用这套规则。"}catch(_:Exception){failed=true;status="保存未完成，请重试。"}finally{busy=false}}
    }
    Column(Modifier.fillMaxSize().background(Chrome).testTag("memory-rules-page")){
        AppTopBar("记忆规则",{vm.back()},extra={TextButton({save()},enabled=ready&&!busy,modifier=Modifier.heightIn(min=48.dp).testTag("memory-rules-save")){Text(if(busy)"保存中" else "保存")}})
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(UiSpace.page),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("按轮次保留，按条数回想",style=MaterialTheme.typography.titleLarge)
            Text("适用于所有朋友，记忆内容仍各自独立。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            AppField(recent,{recent=it.take(10);status=""},"近期原话（轮）","rules-recent",true)
            Text("超过这个完整轮数后，整理最早的一批。一轮是用户连续发言及其首条有效回复；正在输入或未完成的回复不计入。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(summary,{summary=it.take(10);status=""},"每批整理（轮）","rules-summary",true)
            Text("仅在整批保存和来源校验成功后移出近期输入，聊天原文不删除。失败保留进度，可重试。",style=MaterialTheme.typography.bodySmall,color=Muted)
            AppField(related,{related=it.take(10);status=""},"相关记忆最多（条）","rules-related",true)
            Text("直接命中与可用关联补充按记忆去重，共用上限，不凑数。核心记忆单独保留。当前使用关键词与主题标签检索，关联图和向量召回尚未接入。",style=MaterialTheme.typography.bodySmall,color=Muted)
            HorizontalDivider(color=Line)
            Text("容量不足时会明确提示",style=MaterialTheme.typography.titleSmall)
            Text("模型总容量仍有限。装不下完整原话、相关记忆或整理批次时，请选择更大容量模型，或主动调整上述数量；不会暗中缩小批次或截断核心。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("保存本身不联网。修改会使正在运行的旧请求失效；已开始的固定批次保留，手动重试先补齐该批，再按新规则整理。",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(status.isNotEmpty())Text(status,Modifier.testTag("memory-rules-status"),style=MaterialTheme.typography.bodyMedium,color=if(failed)MaterialTheme.colorScheme.error else GreenText)
        }
    }
}
