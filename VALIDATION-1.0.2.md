# 恋叙 1.0.2 验证记录

本记录对应 1.0.2 发布前的实际验证。基线是 GitHub 实际正式版 v1.0.1 的 `35235764135e762e61da7e8f6c07090fe799cd4a`。正式包运行源码为 `a5450334af270de8b4f8b6d1d5b532571da97592`；其后的发布文档提交不改变程序内容，安装包保留该真实编译提交；没有把文档提交说成重新编译过。

**没有“最新整套全绿”的结论。** 完整运行和后续定向补测分开记录，后续重复项不相加成覆盖率；原失败、超时、初始化错误和原始包均保留。设备验证使用专用模拟器与虚构数据，没有操作真实手机或读取真实聊天。

## 构建、单测和完整设备运行

| 阶段/源码 | 实际执行结果 | 范围 |
|---|---|---|
| 最早主机任务选择 / full-initial | 18 秒构建失败，未执行测试 | 任务选择配置错误，保留原日志，不计测试通过 |
| 早期 release JVM / full-2 | 1494 项中 2 项旧时间前缀断言失败 | 同步断言后定向 7 项通过；后续完整 1494/1494 另列，不覆盖本次失败 |
| 早期 release 设备 / device-initial | 54 项完成：29 通过、24 失败、1 条件跳过；主动停止，686 未完成，11 排除 | 部分 UI 失败涉及只在 debug 测试配置声明的 ComponentActivity；16K 容量故障单独查证，不与后续 debug 整轮合并 |
| 早期 debug 设备 / device-debug-full | 38 通过、1 条件跳过，第 40 项发生原生进程中止；701 未完成，11 排除 | 之后做取消收尾对照和完整740；原崩溃及未执行规模保留 |
| 完整主机验证 / `33351c5` | release JVM 1494/1494；debug JVM 1494/1494；两份 lint 各 0 错误、18 警告；两种 APK 及测试驱动构建成功 | 后续三态文案之前；不同构建类型结果不合成 2988 个独立用例 |
| 完整设备 / `33351c5`，device-debug-full-2 | 751 项发现，11 项排除；740 项运行：717 通过、14 失败、9 条件跳过，1356 秒；未见原生进程中止 | 原结果不覆写。下面逐项映射后续验证 |
| 新三态文案第一轮 | 相关 JVM 54/54、设备 47/47 | 包含正确的 ChatPaymentRetryAndroidTest 3 项；尚有后续小收紧 |
| 空白关联/文字色/清理收紧 | 相关 JVM 54/54、设备 17/17 | 主包 c55591b2；覆盖三态、未知、实际文字颜色、真实 Runner 暂存及 Activity 重建 |
| 最终范围收紧 / 等同 `a545033` | 相关 JVM 54/54、设备 4/4 | 主包 4ea56554；只收紧当场写工具的空白关联打开规则、关闭 Flow 初始 Store；受测补丁与提交差异逐字一致 |
| 正式运行源码 `a545033` | lintRelease 0 错误、18 警告；正式 APK/测试驱动构建成功，4 分 20 秒 | 本轮没有重跑完整 JVM；构建目录中复制来的旧报告明确不计本轮执行 |
| 同一正式 APK | 13/13，48.98 秒：8 容量 + 2 按钮颜色 + 3 输入尺寸 | 与后续真实 Go 运行使用完全相同 APK 字节 |

输入区另有真实运行截图：单行输入 56→36.19dp，外层 76.19→57.14dp，发送触控目标 48dp；覆盖普通、大字暗色、窄屏两倍字号、四行中文滚动、IME、发送/打断/停止。绿色背景浅色 D9F1DB/深色 35513F，文字 4E7058/AFC9B5 保持不变，仅停止背景红加深。

16K 合成容量对照中，旧估算漏掉消息封装后完整请求达到 17154，超过配置的 16000；修后首批 20 来源的输入加输出预留估算为 14677，峰值 15319，完成 14 次合成总结和 1 次合成回复，原 71 来源/142 消息保留。无真实 HTTP，不等于供应商 tokenizer 或计费用量。取消收尾对照及连续测试未再中止，但底层原生中止根因未知，不宣称系统问题已根治。

## 原 14 失败逐项映射

以下首轮同步阶段产品仍为 `33351c5`。后续真实状态文案修复另列，不能将同步测试通过说成当时已包含最新产品代码。


| 原失败 | 调整和定向结果 | 边界 |
|---|---|---|
| ChatReplyStreamingUiTest.failedToolCapsuleSeparatesLivePreambleFromModelCorrection | 合成客户端先结束纠正段落再hold；本轮live owner等结果，明确PENDING；放行后live消失、本轮保存回执。stream-notification整类4/4过 | 不单凭本例验收1500/350ms精确时长 |
| 同类toolCallTruncatesThinkingThenContinuesTheSameStream | 保留两段思考/断点/原文/单工具/保存思考，限定本轮owner等结果；同组通过 | 同上 |
| ChatSenderNameUiTest.roleNameStaysInHeaderWithoutRepeatedGroupNames | 原30秒超时未定位；只增独立最小状态、账本、截图与唯一目录；probe1过、test-sync-ui1过，原期限/断言不变 | 原根因未知，不写成已根治 |
| ChatToolCapsuleUiTest.actualCapsulesShowOrderedResultsInLightAndLargeDarkTheme | error应“回想出错”；对照第二颗是真success，非把staged认作成功。test-sync-ui通过，浅色/大字暗色 | 合成控件 |
| CompanionPipelineAndroidTest.modelCanContinuePastDailyAndUnansweredLimitsThroughWorker | 旧single与system+程序user协议冲突；精确角色顺序、正向system及完整请求禁用词，8次联系/限额原断言保留；component整类通过 | 合成Worker |
| 同类completeFlowIdempotentSourceJobMessageNotificationAndRead | 同协议修正，来源/幂等/消息/通知事实原断言保留；同组通过 | 不代替真实手机通知 |
| LiveChatRaceUiTest.doubleSendThenExplicitCancelRetryFailureAndLateCallback[pressure-2] | 原10秒超时calls=0或2未知；保留40ms双击/全部断言，只增强独立取证和After取消本测试全部Deferred；probe8/8、ui8/8 | 原根因未知，清理不当原故障因果结论 |
| MemoryAudit012Test.pendingReviewMakesNoRequestThenReceiptAndHumanCoreChoiceRestoreChat | FAILED早于sending释放，UI实际enabled=!chatSending；原10秒wait也等空闲再retry；保护原文/调用数不变，取消join成功才释放资源；component-2整类6/6过 | 原期限未加长 |
| MemoryPortTest.sequentialClarificationThenCompletionKeepsLatestTextAndResolvesOnce | request(clarify)与实际中文原话不符；共用input，唯一行/候选清空/正文/COMPLETED/版本3保留；component整类通过 | 不改来源校验 |
| MemoryWiringAndroidTest.toolWrittenMemorySurvivesTheWholeChain | 原工具DATE_EVIDENCE；输入补原预期加班事实，time_text明天/date_type day；线程安全捕获同轮首个正文请求，按id/version定位实际引用块；首轮查保存assistant；component-2通过 | component首次新定位误命中编号块失败也保留 |
| RelationshipRestartAndroidTest.persistedProjectionAcrossProcessDeath | 旧pointer对应已执行撤回校验的种子；先tar备份pointer/原UUID库/expected，再新seed、force-stop、另进程check；safe-conditionals两阶段通过，原断言未改 | 未删/覆盖旧库 |
| ToolReceiptCardBreakUiTest.twoCallsSitBetweenThreeIndependentThinkingCards | 等两颗实际结果结束中性加载，再保留三卡/两胶囊/正文全部几何及不嵌框断言；test-sync-ui通过 | DB提交不当UI出现 |
| ToolReceiptFlowUiTest.thinkingReceiptAndReplyKeepTheirOrderThroughReentryAndRecreation | live staged/PENDING按当前回执id限定中性字并测位置，整页无提前成功；After解等待；最终成功/重进/重建/唯一记忆及回执全保留，ui通过 | 不单靠重进等待成功字证明历史不重播 |
| ToolReceiptStateAndroidTest.anUpdateLinksToTheSameRowAndKeepsItsNewText | 旧手注私有stage缺新来源戳被SOURCE_CHANGED拒绝；真实runner M1 update，保留同ID/正文/版本2/可打开/未存不可打开，重复结算后再断言版本2；component-2整类7/7过 | 不冒称旧私有stage兼容 |

阶段数据不能简单相加当全量：component87/86过1失败；component-2共14/14；ui16/15过1失败（新live夹具未结束段落）；修后stream-notification9/9（stream4+通知流程5）。源补丁、APK/driver/hash/raw逐次独立归档。Name/Race定向通过仍保留原整套根因未知风险。

## 原9条件跳过逐项

| 用例 | 具体条件与本次处理 |
|---|---|
| BubbleSplitScreenshotTest.savedReplyHasTwoBubbles | bubbleShot=true纯合成；safe-conditionals显式启用通过 |
| ChatTimeReferenceRealTest.selectedModelMetadataOrSixSyntheticTurnsOnly | 默认model-configs已有选中CHAT真实凭据，metadata后需精确selectionStamp；不新建/改普通vault或启用他服。RSA独立Go六请求另列，不能计本原例通过 |
| ControlledGoTimeReferenceTest.controlledSixRequests | controlledGo=true、唯一runId、RSA3072密文、最多6HTTP无自动重试；当前正式包6/6组件请求完成；未充当实际聊天页验证 |
| GoMemoryRealUiTest.sixChatTurnsAndManualSummary | 同安全import；当前正式包六轮完成，3条当场记忆及原话来源核对；总结COMMITTED但0新增/更新，最终完成画面未验；12连接中1缺终态/用量，不能报全部网络通过 |
| GoTimeReferenceRealUiTest.threeRealChatScreenTurns | 同安全import；当前正式包实际聊天页三轮、3HTTP通过，当前时间匹配/指定标签保留/原历史未改 |
| RealCompanionValidationTest.selectedServicesAndFiniteProductionScenarios | 默认vault，run只接受mimo官方mimo-v2.6-flash或deepseek官方非deepseek-chat，else拒绝；Go不符合。不启用其他凭据，不改服务断言，未执行 |
| RealThinkingLanguageCompareTest.compare | cnThinking=true fake=true mode=run不打开普通vault/明文cn-key；safe-conditionals已通过10脚本×before/after×两种persona×3=120合成场景，不当真实语言效果 |
| ReleaseUpgradeTest.syntheticDataAndEncryptedConfigSurviveCoverInstall | 仅seed16/17/18/19/21、verify17/18/19/22/59/60，需前版预埋+install-r；现有类无61阶段，不卸载现有资料或冒用verify60，不声称61升级保留通过 |
| UserAvatarRestartTest.profilePhotoSurvivesActualProcessRestart | 同一唯一numeric namespace、avatarPhase1/2、中间force-stop；safe-conditionals两阶段通过，第二阶段只清本测试namespace；阶段2及气泡实际截图已看 |

## 原11未执行逐项

| 用例 | 本次动作或缺口 |
|---|---|
| CompanionFlowUiTest.malformedRecordsKeepTheInputAndStillReplyWithoutAnExperience | 删除自动pm grant，独立业务不再被权限gate跳过；stream-notification通过 |
| 同类aFailedContactShowsTheCardAndTheRetryRow | 要求已有通知权限，本机已granted，补测通过 |
| 同类changingRolesWhileUpdatingPermissionKeepsTheOriginalTarget | 同上，通过 |
| 同类offscreenHistoryAndAnotherRoleStayUnreadUntilActuallyVisible | 无权限gate，通过 |
| 同类realChatWorkerNotificationTargetAndVisibleReceipt | 现有权限下合成UI/Worker/通知目标及回执通过；前后granted=true及flags逐字相同，没有授权/撤权 |
| CompanionBirdPreviewTest.nativePreviewAndMotionBoundaries | 原例改global animator_duration_scale再恢复，无系统开关授权，未执行 |
| CompanionBrushPreviewTest.nativePreviewAndMotionBoundaries | 同上，未执行 |
| WaitingMotionIntegrationTest.pausesInBackgroundReducedMotionAndRemoval | 原例改动画倍率1/0/恢复，未执行 |
| RealProviderChatReceiptRecordingTest.realModelThinkingReceiptAndReplyStayOrderedThroughReentryAndRecreation | 旧fixture要求sf-key.txt明文（默认硅基流动，可选服务地址仍同明文通道）；不创建明文文件或启用其他服务，未执行；安全RSA Go UI另列 |
| RealProviderRecallDedupRecordingTest.aRecallThatWasAlreadyInTheReferenceSaysRemembered | 共用上述旧明文fixture，未执行 |
| RealProviderMemoryPillTest.aRealProviderSummaryCommitsExactlyOnePillMatchingTheRealWrites | 要求私有files/sf-key.txt明文，默认硅基流动可选地址；未执行，RSA Go总结另列 |

原11项已安全补5项，仍有6项本身未执行。不能用替代证据算这些原例通过。当前三组Go原始请求/状态/保存/截图/返回用量与清理已核对；详细缺口见下文，未用历史次数充当本次。



## 最新当场写记忆状态

仅 `agent_memory_update` 使用“正在记住 / 我记住了 / 没记住”：running/staged 中性；确认提交且关联 ID 非空白才显示成功及可打开；缺可靠结果显示“结果未确认”。旧标题不回写，其他工具保持原语义、打开规则和全局动画机制。

旧包在无动画事件的合成 staged 用例中确实提前显示成功标题，下方同时写着暂存未保存；这证明标题时序错误，不证明发生数据丢失。新用例保留旧包红灯；新方案验证无事件、暂存转失败无成功字、真实 Runner 等待 2 秒仍 PENDING、Activity 重建仍“正在记住”、实际提交后重进/重建显示“我记住了”。浅色和大字暗色文字色断言通过。中间一次输入不存在的 ChatPaymentRetryUiTest 导致 initializationError，已保留，不当产品崩溃；随后正确类的 3 项通过。

最终两次 Astra 标准 medium 只读复审已关闭代码意见；它们签认局部代码与相应记录，不能代替最终独立发布审查。

## 当前正式 APK 的有限真实 Go 验证

使用既有授权的单一 Go 凭据、安全 RSA 临时传输和独立虚构库。21 次连接尝试中，20 次取得完整模型及用量记录，记录的模型均为 deepseek-v4.1-flash；记忆场景第 10 次尝试的终态和用量未知。没有启用其他服务、改系统权限/时钟/代理开关，也没有修改普通用户配置。

| 场景 | 已证实 | 缺口 |
|---|---|---|
| 控制组件 6 请求 | 6/6 完成；生产提示和 HTTPS 链路；无意外时间标签 | 不是聊天页 UI |
| 真实聊天页时间 3 轮 | 3 请求完成，按请求时间回答，指定 `[17:29]` 保留，原历史正文未改；实际截图已看 | 有限样本，不推及所有时间表达 |
| 真实聊天页记忆 6 轮 | 6 轮正文完成并对应保存；首轮明确不记时 0 记忆/0 工具；后续共 3 条有效当场记忆，核对原句、消息 ID/版本；生日仅 05-20 不猜年份，原话“明天”展览对应 2026-11-16；2 次历史搜索、1 次记忆读取成功 | 12 连接尝试中第 10 条 network 缺最终状态、耗时和响应元数据，原因未定 |
| 手动总结 | 第一次 COMMITTED，0 新增/更新，原 3 条记忆不变 | 截图时测试时钟仍暂停、画面仍“处理中”；最终 UI 完成未观察，不证明永久卡住，也不证明真实 API 总结新增保存通过 |

总计 **21 次连接尝试，20 次有完整模型及用量记录，1 次终态/用量未知**。已知部分用量为输入 77853、输出 2557、合计 80410 token；20 条服务响应 cost 字段合计 0，allowance 字段合计 0.00486777 美元。这是响应字段，非独立账单；由于缺 1 条，不报告完整总费用或完整用量。三组临时凭据、库、别名、传输密文及进程清理核对全部通过。

## 仍未验证或未解决

- Name 原 30 秒超时、Race 原 10 秒压力项超时：定向通过，原根因未知，未延长个别等待或删减原断言。
- 原 9 条件项中，普通选中模型、仅官方 MiMo/DeepSeek 验证、code61 覆盖升级这 3 个原用例未启用；GoMemory 有上述观测缺口。原 11 排除项安全补 5，仍 6 原用例未执行。替代证据不充当这些原例通过。
- 专用模拟器现有通知权限下 5 项合成联系流程通过，前后授权和 flags 完全不变；真实手机锁屏送达、省电后台、长期运行与进程死亡后的到达未验。
- 未操作真实手机，未证明 code61 覆盖旧版后真实资料完整保留；签名相同与版本递增不能替代升级数据验收。
- 遗忘首次选择目标仍不稳；模型口头答应联系却未调用安排工具的旧问题未修；没有每轮聊天后自动补排期的兜底。
- 当前 Go 使用隔离受控配置；不是正常用户配置、超长上下文或所有模型的验收。历史真实模型报告另存，未合并冒充本候选样本。
- 测试取消资源清理按项尽力执行且记录异常；未做操作系统故障注入，不保证 OS 错误下绝无资源遗留。

## 正式包身份与发布记录

正式包名 `app.lianxu.independent`，1.0.2 / versionCode 61；APK SHA-256 `4a2c7fb691b5b2faebacdf3c60b5da943ae4b1d6a41b793b2a4a1f5da1b09e2f`，14954154 字节。沿用既有发布签名，证书 SHA-256 `78e2ff6edc4468a700ce51e68681f03c38b11778918ec251fc3e367561e0c31b`。证书 subject 含 Android Debug，是既有证书的名字，不包装成新证书。

已完成独立发布审查。最终资产与公开状态见 [1.0.2 Release](https://github.com/Jiang-Xiaoxing/Lianxu/releases/tag/v1.0.2)，本记录不将未验证项补写为通过。安装前先备份；不要卸载存有资料的旧应用。
