# 源码复用与许可状态

这是现有记录和有界来源补核的整理，不是完整法律审计。来源仓库 [Jiang-Xiaoxing/Nekobot-Android](https://github.com/Jiang-Xiaoxing/Nekobot-Android) 的API许可证元数据为GPL-3.0，父仓库为asukaneko/Nekobot-Android；这不能单独证明每个用户独立新增组件均受第三方GPL权属约束。补核仅限已知用户新增日期文件、工具及相关Git作者/父版本/文件差异，没有重新移植其他源码或素材。维护者已为本项目代码选择GPL-3.0-only；第三方材料保留原许可。

|恋叙内容|现有来源记录|本次核实与范围|
|---|---|---|
|AgentRecallTools.kt|8baacaaf810863d43fd90ee3663794c7d2ca1a23直接复用后适配|提交作者账号Jiang-Xiaoxing；父提交46635eef4f9d897ccff741e32651c68aa7cbcb3b无此文件。原文件导入Gson/JDK，目标含后续适配，不将其等同上游执行器。|
|CompanionRecallBudget.kt及其测试|e253413e107945bf0f141f8c6565e1fe8f0ba7e5|提交作者账号Jiang-Xiaoxing；父提交58ac3ed6466fba9c3ff6b032a98b6026772d49b8无此文件。原文件导入Gson、coroutines及JDK；测试和后续目标适配按现有贡献链保留。|
|time/下5个日期规则文件|既有记录d998cdd4新增，e5f84f44移植，e01d7067修正；仅改包名直接复用|已在本地Git对象核实d998cdd444abe88d6f3c854a34549d07fc394704，作者Jiang-XX（用户既有身份），父提交4ddb183ee4e11d113310a30e56941b36b01d30c9无这5个文件；复制检测均为新增A。当前5文件与同作者修正提交e01d7067fba797e8a55b8d4056037298190646ab除包名/换行完全一致；修正为四语圣诞年份及韩语天数规则。运行依赖仅JDK，相关父目录未匹配其专有实现符号，未发现夹带其他上游日期引擎实现。|
|RelationshipPolicy.clamp|eba14deb21e16b1662781688407274ffa1cad34f最小适配|提交作者账号Jiang-Xiaoxing；使用枚举替代原字符串，保留限幅与高分正增减半。|
|MemoryNumberedReferences、MemoryTools、MemoryReferenceHistory、MemoryDates显示片段及相关总结/候选行为|现有记录16047cbc、e5f84f44、3a05a8a2、c933c63b、15b9563b、e5f84f44等用户贡献链|恋叙适配自己的仓储、证据快照与事务。旧链部分交叉核验不完整，公开授权前须按来源处理，不能将整个旧项目视为自有。|
|MemoryVectorIndex缓存策略|现有记录bb1e9fff中的用户包装策略|目标HTTPS客户端、缓存存储与余弦独立适配；没有复制上游LocalKnowledgeSearch文件。不将其他作者底层文件归为用户新增。|
|RoleExamplesPolicy、旧聊天示例流程|用户确认自行编写；参考 Nekobot-Android 固定 71257279d79018e89c6dbdf9722213597bde504d|核对 ae95627e586e7c78f45e6843b5037460e85cf8ef 相对父 66ef1fedf8135a8fd82248848d604b8708f40f41 新增 CompanionExamplesPolicy/Store/Ui/Entity；后续 05316fa59390e66b4c9eb862986befa4a9393c9b、eb37559e83348b386681fb8009bafc44ed71c3e0 修订。复用筛选正则、场景均衡和只选编号规则，适配本项目真实回合账本、preferences 事务、模型接口与原生 UI；不复制 Room/ServiceContainer 或其他上游会话实现。来源 GPL v3 与本项目 GPL-3.0-only 保留，Git 新增记录和用户声明不被扩大为整个上游文件体系的原创性结论。|
|聊天客户端、账本、配置、现有存储/UI|恋叙独立实现，旧记录中列有行为规格参考|保留现有实现，不为公开准备重新移植旧代码。|
|日期测试中292条上游选题|Microsoft Recognizers-Text，固定da7edcff59f669b2a460ab9d400e36298f0d658e|MIT许可及微软版权在测试resources/licenses内，逐题来源和选择元数据保留；运行引擎不是微软引擎。|

旧文档中“未搬日期引擎”的说法只对应更早阶段，不代表当前源码；当前确有5个规则文件及微软来源测试数据。

维护者已选择GNU GPL version 3 only（GPL-3.0-only），全文见根LICENSE。来源补核不代替完整法律审计，也不因未检出复制而证明全球范围原创性；未逐片段复审的适配不被自动归为已确认第三方GPL复制。原版权/署名继续保留，不为字体、微软测试资料、Gradle或其他第三方内容换授权。

字体与Gradle wrapper独立保留原许可，见THIRD_PARTY_NOTICES.md；字体OFL与数据MIT不替代项目级代码许可证。

## 角色包导入导出（本轮草稿）

用户明确要求优先复用其星伴导入导出实现。核对来源：Jiang-Xiaoxing/Nekobot-Android 的 `71257279d79018e89c6dbdf9722213597bde504d`，其中 `app/src/main/kotlin/com/nekobot/app/data/local/PortableArchiveValidator.kt` 由 `ccf224dc336ce6acfeac33b642564e93e5c8b696` 新增（106行、0删除）；Git作者 Jiang-XX，关联账号 Jiang-Xiaoxing。该文件到指定提交的路径历史只包含此新增提交，并非凭最后修改者推断整个上游项目归属。

`RoleArchive.kt` 的 `entryName` 直接适配该独立贡献的路径规则；版本整数校验、清单/哈希与有界读取沿用同一设计，针对只含两个文件的恋叙格式收紧限制。原 `PortableImportPlan` 的差异预览、保留现有资料与确认后原子提交作为交互/事务参考；没有搬入它依赖的星伴数据库、账号、插件、凭据恢复或迁移代码。仓库现有GPL-3.0-only及第三方声明不变。本次适配不是星伴/爱语旧包兼容承诺，也不执行历史资料迁移。
