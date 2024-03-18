package cn.jia.core.common;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

@Slf4j
class EsHandlerTest {
    @Test
    void testURLEncoder() {
        log.info(java.net.URLEncoder.encode("你的验证码是1234, 请在2分钟内输入！", StandardCharsets.UTF_8));
    }
}