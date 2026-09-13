package cn.jia.wx.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class WxExternalHttpClientBuilderTest {
    @Test
    void buildsAClientWithOnlyExplicitPositivePhaseOverrides() {
        WxExternalHttpClientBuilder builder = new WxExternalHttpClientBuilder(0, 7001, 0);

        assertDoesNotThrow(() -> builder.build().close());
    }
}
