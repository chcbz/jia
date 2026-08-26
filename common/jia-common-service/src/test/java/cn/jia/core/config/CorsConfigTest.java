package cn.jia.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    @Test
    void credentialedCorsUsesConfiguredExactOriginAndSecurityHeaders() {
        CorsConfig config = configured(new String[]{"https://kit.chaoyoufan.cn"});
        CorsConfiguration cors = config.buildConfig();
        assertEquals(List.of("https://kit.chaoyoufan.cn"), cors.getAllowedOriginPatterns());
        assertEquals(List.of("Authorization", "Content-Type", "X-API-Key"), cors.getAllowedHeaders());
        assertTrue(cors.getAllowedMethods().containsAll(List.of("POST", "OPTIONS")));
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        assertEquals(Ordered.HIGHEST_PRECEDENCE,
                config.corsFilterFilterRegistrationBean().getOrder());
    }

    @Test
    void wildcardOriginFailsFastWhenCredentialsAreEnabled() {
        assertThrows(IllegalStateException.class, () -> configured(new String[]{"*"}).buildConfig());
    }

    private static CorsConfig configured(String[] origins) {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", origins);
        ReflectionTestUtils.setField(config, "allowedMethods", new String[]{"GET", "POST", "OPTIONS"});
        ReflectionTestUtils.setField(config, "allowedHeaders", new String[]{"Authorization", "Content-Type", "X-API-Key"});
        return config;
    }
}
