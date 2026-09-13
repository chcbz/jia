package cn.jia.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.PropertySource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNull;

class RedisCacheConfigTest {

    @Test
    void doesNotInstallPerformanceGoalTimeoutOverrides() throws IOException {
        assertNull(RedisCacheConfig.class.getAnnotation(PropertySource.class));
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("META-INF/jia-redis-budget-defaults.properties")) {
            assertNull(input);
        }
    }
}
