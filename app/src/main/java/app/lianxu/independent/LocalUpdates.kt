package app.lianxu.independent

import com.google.gson.JsonObject

internal data class LocalReleaseNote(val code:Long,val version:String,val title:String,val changes:List<String>,val limits:List<String>)

/** Bundled facts about this build and earlier versions. Never polls or pushes. */
internal object LocalReleaseNotes {
    private val entries=listOf(
        LocalReleaseNote(18,"0.18-moon-companion-test","星月相伴与个人资料",listOf(
            "接通用户称呼、自我介绍及角色设定、说话方式、开场白；角色资料实际参与对话，并随备份保存。",
            "统一暖黄星月风格，简化聊天与通讯录文案，记忆与关系分开进入；记忆星空仅展示真实记录及有效关联，完整列表仍可搜索和编辑。",
            "新增本地更新提醒与可回看的记录，关于页显示作者、实际版本、开源许可与致谢。",
            "包含前序记忆可靠性修补：总结期间保护手动修改、五分钟超时取消，以及可选的批量语义查重。"
        ),listOf("测试版本：本轮模拟器未能启动，设备界面、覆盖升级与真实模型效果尚未实测。更新前请先导出备份。")),
        LocalReleaseNote(17,"0.17-model-directory-test","获取厂商模型列表",listOf(
            "在模型配置中获取、搜索并点选厂商返回的模型，同时保留手动输入。",
            "点选只填写草稿，保存后才生效；切换厂商或地址后丢弃旧请求结果。"
        ),listOf("本发布包设备回归及 v0.16 到 v0.17 覆盖升级未实测，安装前请先导出备份。")),
        LocalReleaseNote(16,"0.16-backup-diagnostics-test","完整备份与本机诊断",listOf(
            "单角色备份带出该角色的完整持久化资料；全部备份另包含设置及模型配置。",
            "导入明确选择新增或覆盖；本机滚动诊断可导出简版或详细版，不自动上传。"
        ),listOf("包含 API 密钥的备份没有密码保护，别人取得文件也能使用并消耗额度，请勿分享。"))
    )
    fun forVersion(code:Long)=entries.singleOrNull{it.code==code}
    fun history(installedCode:Long)=entries.filter{it.code<=installedCode}
    fun pending(installedCode:Long,receipt:LocalUpdateReceipt)=forVersion(installedCode)?.takeIf{installedCode>receipt.lastAcknowledged}
}

/** Version codes increase; an older restored build must not resurface an acknowledged notice. */
internal data class LocalUpdateReceipt(val lastAcknowledged:Long=0) {
    init{require(lastAcknowledged>=0)}
    fun acknowledge(code:Long)=copy(lastAcknowledged=maxOf(lastAcknowledged,code.also{require(it>0)}))
    fun encode()=JsonObject().apply{addProperty("version",1);addProperty("lastAcknowledged",lastAcknowledged)}.toString()
    companion object {
        const val KEY="local-update-receipt-v1"
        fun decode(raw:String):LocalUpdateReceipt {
            if(raw.isBlank())return LocalUpdateReceipt()
            val j=RoleArchive.parse(raw.toByteArray());require(j.keySet()==setOf("version","lastAcknowledged")&&RoleArchive.integer(j["version"])==1L)
            return LocalUpdateReceipt(RoleArchive.integer(j["lastAcknowledged"]))
        }
    }
}
