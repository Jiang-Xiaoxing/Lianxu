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
            "阶段：请求已发出，等待服务商响应时连接中断（请求期间应用切到过后台）\nSocketException: Software caused connection abort",
            restricted,
            ChatFailureMeta(route="aux_vision",stage="await_response_headers",sawBackground=true,modelConfigId="vision",modelId="deepseek-v4.1-flash",outputTokens=3072,streamReplies=false)
        )
        assertEquals("辅助看图时连接中断",guide.title)
        assertEquals(FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS,guide.action)
        assertEquals("去开启后台权限",guide.actionLabel)
        assertTrue(guide.summary.contains("可能"))
        assertFalse(guide.summary.contains("十几秒"))
        assertTrue(guide.steps.any{it.contains("允许后台活动")})
        assertTrue(guide.steps.any{it.contains("不允许优化")})
        assertTrue(guide.steps.any{it.contains("通知管理")})
    }

    @Test fun foregroundConnectionAbortDoesNotMisleadToBatterySettings(){
        val restricted=BackgroundAccessState(ignoringBatteryOptimizations=false,backgroundRestricted=true)
        val guide=ChatFailureGuidance.build(
            ChatFailure.CONNECTION_INTERRUPTED,
            "阶段：请求已发出，等待服务商响应时连接中断\nSocketException: Connection reset",
            restricted,
            ChatFailureMeta(route="chat",stage="await_response_headers",sawBackground=false)
        )
        assertEquals("等待回复时连接中断",guide.title)
        assertNull(guide.action)
        assertTrue(guide.summary.contains("保持在前台"))
        assertTrue(guide.steps.first().contains("网络或代理"))
        assertTrue("foreground steps must not mention battery/background settings: ${guide.steps}",
            guide.steps.none{it.contains("电池")||it.contains("优化")||it.contains("后台活动")})
    }

    @Test fun sendStageConnectionAbortMatchesTitleToStage(){
        val detail="阶段：发送请求时连接中断\nSocketException: Broken pipe"
        val meta=ChatFailureMeta(route="chat",stage="connect_or_write",sawBackground=false,detailSource="local")
        val guide=ChatFailureGuidance.build(
            ChatFailure.CONNECTION_INTERRUPTED,
            detail,
            BackgroundAccessState(ignoringBatteryOptimizations=true),
            meta
        )
        assertEquals("发送请求时连接中断",guide.title)
        assertTrue(guide.summary.contains("发送请求时"))
        assertFalse(guide.title.contains("等待回复"))
        val full=ChatFailureText.full(ChatFailure.CONNECTION_INTERRUPTED,detail,meta)
        assertTrue(full,full.startsWith("发送请求时连接中断了"))
        assertFalse(full,full.contains("等待回复"))
    }

    @Test fun auxVisionTruncationPointsDirectlyToModelConfig(){
        val guide=ChatFailureGuidance.build(
            ChatFailure.TRUNCATED,
            "模型达到了当前配置的输出上限（3072 token），回复未生成完。",
            meta=ChatFailureMeta(route="aux_vision",stage="read_response_body",modelConfigId="vision",modelId="deepseek-v4.1-flash",outputTokens=3072,streamReplies=true)
        )
        assertEquals("辅助看图达到输出上限",guide.title)
        assertEquals(FailureGuidanceAction.OPEN_MODEL_CONFIG,guide.action)
        assertEquals("去调大模型配置",guide.actionLabel)
        assertTrue(guide.summary.contains("辅助看图"))
        assertTrue(guide.steps.any{it.contains("输出上限")})
    }

    @Test fun incompleteStreamTruncationOnlySuggestsBackgroundSettingsWhenBackgrounded(){
        val foreground=ChatFailureGuidance.build(
            ChatFailure.TRUNCATED,
            "HTTP 200\n回复流没有通过检查：incomplete",
            BackgroundAccessState(ignoringBatteryOptimizations=false),
            ChatFailureMeta(route="chat",stage="read_response_body",sawBackground=false)
        )
        assertEquals("回复流未完整结束",foreground.title)
        assertNull(foreground.action)

        val backgrounded=ChatFailureGuidance.build(
            ChatFailure.TRUNCATED,
            "HTTP 200\n回复流没有通过检查：incomplete",
            BackgroundAccessState(ignoringBatteryOptimizations=false),
            ChatFailureMeta(route="chat",stage="read_response_body",sawBackground=true)
        )
        assertEquals("回复流未完整结束",backgrounded.title)
        assertEquals(FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS,backgrounded.action)
        assertEquals("去开启后台权限",backgrounded.actionLabel)
    }

    @Test fun httpProviderBodyStaysVerbatimAndSeparateFromStageMeta(){
        val secret="sk-pasted-into-model-id-987654321"
        val redactedModel=Diagnostics.redact("bad-$secret",listOf(secret))
        assertFalse(redactedModel.contains(secret))
        val rawHttp="HTTP 415 Unsupported Media Type\nunsupported image   "
        val meta=ChatFailureMeta(route="aux_vision",modelConfigId="vision",modelId=redactedModel,outputTokens=3072,streamReplies=false,detailSource="provider_http")
        val full=ChatFailureText.full(ChatFailure.REJECTED,rawHttp,meta)
        assertFalse(full.contains(secret))
        assertTrue(full.contains("失败阶段：\n辅助看图（"))
        assertTrue(full.endsWith("服务返回的原文：\n$rawHttp"))

        val localHttp200=ChatFailureText.full(
            ChatFailure.FORMAT,
            "HTTP 200\n服务返回的内容不是有效的 JSON。",
            ChatFailureMeta(route="chat",stage="read_response_body",detailSource="local")
        )
        assertTrue(localHttp200,localHttp200.contains("详细原因：\nHTTP 200\n服务返回的内容不是有效的 JSON。"))
        assertFalse(localHttp200,localHttp200.contains("服务返回的原文："))
    }

    @Test fun notificationGuidanceAndChannelResolutionDistinguishAppVsChannelLevel(){
        val appOff=BackgroundAccessState(notificationsEnabled=false,chatChannelBlocked=true)
        assertNull(notificationSettingsChannelId(appOff))
        val appGuide=ChatFailureGuidance.build(ChatFailure.FOREGROUND_UNAVAILABLE,"",appOff)
        assertEquals(FailureGuidanceAction.OPEN_NOTIFICATION_SETTINGS,appGuide.action)
        assertEquals("去开启应用通知",appGuide.actionLabel)

        val channelOnlyOff=BackgroundAccessState(notificationsEnabled=true,chatChannelBlocked=true)
        assertEquals(ChatRequestService.CHANNEL_ID,notificationSettingsChannelId(channelOnlyOff))
        val channelGuide=ChatFailureGuidance.build(ChatFailure.FOREGROUND_UNAVAILABLE,"",channelOnlyOff)
        assertEquals(FailureGuidanceAction.OPEN_NOTIFICATION_SETTINGS,channelGuide.action)
        assertEquals("去开启聊天通知",channelGuide.actionLabel)
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
