package cn.jia.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level production-profile contract only. It never creates a client, contacts Elasticsearch,
 * creates an index, or reads credentials.
 */
class ElasticsearchProductionProfileContractTest {
    @Test
    void productionProfileExposesTheConfiguredElasticsearchDependencyToActuatorHealth() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application-prod.properties"));

        assertEquals("true", properties.getProperty("management.health.elasticsearch.enabled"));
        assertNotNull(properties.getProperty("spring.elasticsearch.uris"));
        assertFalse(properties.getProperty("spring.elasticsearch.uris").isBlank());
        assertEquals("500ms", properties.getProperty("spring.elasticsearch.connection-timeout"));
        assertEquals("1750ms", properties.getProperty("spring.elasticsearch.socket-timeout"));
        assertEquals("2500ms", properties.getProperty("jia.elasticsearch.request-timeout"));
        assertEquals("100ms", properties.getProperty("jia.elasticsearch.safety-margin"));
        assertTrue(properties.getProperty("spring.ai.vectorstore.elasticsearch.initialize-schema") == null,
                "production must not enable vector-store schema initialization through this profile");
    }
}
