package cn.jia.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.PropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RedisCacheConfigTest {

    @Test
    void loadsBoundedRedisClientDefaults() throws IOException {
        PropertySource propertySource = RedisCacheConfig.class.getAnnotation(PropertySource.class);
        assertNotNull(propertySource);
        assertEquals("classpath:META-INF/jia-redis-budget-defaults.properties", propertySource.value()[0]);

        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("META-INF/jia-redis-budget-defaults.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals("500ms", properties.getProperty("spring.data.redis.connect-timeout"));
        assertEquals("1500ms", properties.getProperty("spring.data.redis.timeout"));
        assertEquals("500ms", properties.getProperty("spring.data.redis.lettuce.pool.max-wait"));
        assertEquals("100ms", properties.getProperty("spring.data.redis.lettuce.shutdown-timeout"));
    }
}
