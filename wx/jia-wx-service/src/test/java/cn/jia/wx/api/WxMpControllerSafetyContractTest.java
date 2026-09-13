package cn.jia.wx.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WxMpControllerSafetyContractTest {

    @Test
    void callbackLogsAndCacheCleanupStayPayloadSafe() throws Exception {
        String controller = source("src/main/java/cn/jia/wx/api/WxMpController.java");
        String timing = source("src/main/java/cn/jia/wx/dailyvote/WxCallbackTiming.java");

        assertFalse(controller.contains("\\n\\r\" + msg"));
        assertFalse(controller.contains("jiacn={}"));
        assertFalse(controller.contains("openid={}"));
        assertFalse(controller.contains("signature={}"));
        assertTrue(controller.contains("checkSignature(timestamp, nonce, signature)"));
        assertTrue(controller.contains("findByAppIdAndOpenId(appid, message.getFromUser())"));
        assertTrue(controller.contains("deleteIfValueEquals"));
        assertTrue(controller.contains("result.firstProcessing() && result.correct()"));
        assertTrue(controller.contains("new WxCallbackTiming()"));
        assertTrue(timing.contains("Wx callback timing"));
        assertFalse(timing.contains("openid"));
        assertFalse(timing.contains("content"));
        assertFalse(timing.contains("signature={}"));
    }

    private String source(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
