package cn.jia.wx.dailyvote;

import me.chanjar.weixin.mp.bean.message.WxMpXmlMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WxDailyVoteKeysTest {

    @Test
    void messageAndUserKeysAreDeterministicHashesWithoutPlaintextIdentity() {
        WxMpXmlMessage message = new WxMpXmlMessage();
        message.setMsgId(123456789L);
        message.setFromUser("openid-secret-value");
        message.setToUser("gh_original");
        message.setContent("answer-secret-value");
        message.setMsgType("text");
        message.setCreateTime(1710000000L);

        String first = WxDailyVoteKeys.messageKey("wx-app", message);
        String second = WxDailyVoteKeys.messageKey("wx-app", message);
        String userKey = WxDailyVoteKeys.userKey("wx-app", "jiacn-secret-value");
        String fingerprint = WxDailyVoteKeys.requestFingerprint("wx-app", userKey, 337L,
                "answer-secret-value");

        assertEquals(first, second);
        assertAll(
                () -> assertTrue(first.matches("[0-9a-f]{64}")),
                () -> assertTrue(userKey.matches("[0-9a-f]{64}")),
                () -> assertTrue(fingerprint.matches("[0-9a-f]{64}")),
                () -> assertFalse(first.contains("openid-secret-value")),
                () -> assertFalse(userKey.contains("jiacn-secret-value")),
                () -> assertFalse(fingerprint.contains("answer-secret-value"))
        );
        assertNotEquals(first, WxDailyVoteKeys.messageKey("other-app", message));
        assertEquals(first.substring(0, 12), WxDailyVoteKeys.trace(first));
    }

    @Test
    void fallbackKeyChangesWithPayloadWhenMessageIdIsUnavailable() {
        WxMpXmlMessage first = fallback("A");
        WxMpXmlMessage second = fallback("B");

        assertNotEquals(WxDailyVoteKeys.messageKey("wx-app", first),
                WxDailyVoteKeys.messageKey("wx-app", second));
    }

    private WxMpXmlMessage fallback(String content) {
        WxMpXmlMessage message = new WxMpXmlMessage();
        message.setFromUser("openid-secret-value");
        message.setToUser("gh_original");
        message.setContent(content);
        message.setMsgType("text");
        message.setCreateTime(1710000000L);
        return message;
    }
}
