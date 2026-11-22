package app.lianxu.independent

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ChatFailureGuidanceTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun autoPromptTargetsOnlyActionableInterruptionsAndTruncation(){
        assertTrue(ChatFailureGuidance.shouldAutoPrompt(ChatFailure.CONNECTION_INTERRUPTED))
        assertTrue(ChatFailureGuidance.shouldAutoPrompt(ChatFailure.RESPONSE_BROKEN))
        assertTrue(ChatFailureGuidance.shouldAutoPrompt(ChatFailure.FOREGROUND_UNAVAILABLE))
        assertTrue(ChatFailureGuidance.shouldAutoPrompt(ChatFailure.TRUNCATED))
        assertFalse(ChatFailureGuidance.shouldAutoPrompt(ChatFailure.RATE_LIMIT))
        assertFalse(ChatFailureGuidance.shouldAutoPrompt(ChatFailure.UNKNOWN))
        assertFalse(ChatFailureGuidance.shouldAutoPrompt(null))
    }

    @Test fun backgroundRestrictedConnectionAbortGuidesUserToEnableBackgroundPermissions(){
        val restricted=BackgroundAccessState(
            ignoringBatteryOptimizations=false,
            powerSaveMode=false,
            backgroundRestricted=true,
            restrictBackgroundStatus="enabled",
            notificationsEnabled=false,
            chatChannelBlocked=false,
        )
        val guide=ChatFailureGuidance.build(
            ChatFailure.CONNECTION_INTERRUPTED,
            "阶段：辅助看图（模型：deepseek-v4.1-flash，输出上限：3072 token，非流式）\n阶段：请求已发出，等待服务商响应时连接中断（请求期间应用切到过后台）\nSocketException: Software caused connection abort",
            restricted
        )
        assertEquals("辅助看图时连接中断",guide.title)
        assertEquals(FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS,guide.action)
        assertEquals("去开启后台权限",guide.actionLabel)
        assertTrue(guide.steps.any{it.contains("允许后台活动")})
        assertTrue(guide.steps.any{it.contains("不允许优化")})
        assertTrue(guide.steps.any{it.contains("通知管理")})
    }

    @Test fun auxVisionTruncationPointsDirectlyToModelConfig(){
        val guide=ChatFailureGuidance.build(
            ChatFailure.TRUNCATED,
            "阶段：辅助看图（模型：deepseek-v4.1-flash，输出上限：3072 token，流式）\n模型达到了当前配置的输出上限（3072 token），回复未生成完。"
        )
        assertEquals("辅助看图达到输出上限",guide.title)
        assertEquals(FailureGuidanceAction.OPEN_MODEL_CONFIG,guide.action)
        assertEquals("去调大模型配置",guide.actionLabel)
        assertTrue(guide.summary.contains("辅助看图"))
        assertTrue(guide.steps.any{it.contains("输出上限")})
    }

    @Test fun incompleteStreamTruncationDoesNotBlameOutputLimit(){
        val guide=ChatFailureGuidance.build(
            ChatFailure.TRUNCATED,
            "HTTP 200\n回复流没有通过检查：incomplete",
            BackgroundAccessState(ignoringBatteryOptimizations=false)
        )
        assertEquals("回复流未完整结束",guide.title)
        assertEquals(FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS,guide.action)
        assertEquals("去开启后台权限",guide.actionLabel)
    }

    @Test fun basicExportProjectsServiceAndBackgroundFailureFieldsWithoutLeakingPrivateText(){
        val id=java.util.UUID.randomUUID().toString()
        val secret="private-user-chat-should-never-escape"
        DiagnosticJournal(temp.newFolder(),"fixture").use{j->
            j.record("service.foreground.active",id,mapOf(
                "active_requests" to 1,
                "ignoring_battery_optimizations" to false,
                "background_restricted" to true,
                "restrict_background_status" to "enabled",
                "notifications_enabled" to true,
                "chat_channel_blocked" to false,
                "private" to secret
            ))
            j.record("http.network_failed",id,mapOf(
                "request_id" to id,
                "error_type" to "SocketException",
                "failure_stage" to "await_response_headers",
                "saw_background" to true,
                "network_blocked" to true,
                "exception_stack" to secret
            ))
            val basic=j.export(false).decodeToString()
            assertFalse(basic.contains(secret))
            val rows=basic.lineSequence().filter{it.isNotBlank()}.drop(1).map{JsonParser.parseString(it).asJsonObject}.toList()
            val svc=rows[0]["data"].asJsonObject
            assertEquals(1,svc["active_requests"].asInt)
            assertFalse(svc["ignoring_battery_optimizations"].asBoolean)
            assertTrue(svc["background_restricted"].asBoolean)
            assertEquals("enabled",svc["restrict_background_status"].asString)
            val net=rows[1]["data"].asJsonObject
            assertEquals("await_response_headers",net["failure_stage"].asString)
            assertTrue(net["saw_background"].asBoolean)
            assertTrue(net["network_blocked"].asBoolean)
        }
    }
}
