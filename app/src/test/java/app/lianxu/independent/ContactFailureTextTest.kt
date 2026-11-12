package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ContactFailureTextTest {
    private val raw="HTTP 429 Too Many Requests\n{\"error\":{\"api_key\":\"sk-****abcd\",\"type\":\"rate_limit_exceeded\"}}"
    @Test fun onlyARealUnreachableServiceSaysTheNetworkIsDown(){
        assertTrue(ContactFailureText.summary(ContactFailure(ContactFailureKind.NETWORK,0)).contains("网络连不上"))
        for(kind in ContactFailureKind.entries.filter{it!=ContactFailureKind.NETWORK})
            assertFalse(kind.name,ContactFailureText.summary(ContactFailure(kind,0,raw)).contains("网络"))
    }
    @Test fun providerStatusIsNamedAndTheFullTextKeepsTheRawError(){
        val f=ContactFailure(ContactFailureKind.MODEL_RESPONSE,0,raw)
        assertEquals("她想联系你，但没发出去。服务商返回 HTTP 429。",ContactFailureText.summary(f))
        assertTrue(ContactFailureText.full(f).endsWith("服务返回的原文：\n"+raw))
        assertFalse(ContactFailureText.preview(raw).contains("\n"))
        assertEquals("她想联系你，但没发出去。服务商返回了错误。",ContactFailureText.summary(ContactFailure(ContactFailureKind.MODEL_RESPONSE,0,"FORMAT: 回复格式无效")))
        assertTrue(ContactFailureText.full(ContactFailure(ContactFailureKind.OTHER,0,"IllegalStateException: x")).contains("详细原因：\nIllegalStateException: x"))
    }
    @Test fun timeReadsLikeTheChatLabels(){
        val zone=ZoneId.of("Asia/Shanghai");val now=Instant.parse("2026-11-12T02:00:00Z")
        assertEquals("昨天 23:14",ContactFailureText.time(Instant.parse("2026-11-11T15:14:00Z").toEpochMilli(),now,zone))
    }
}
