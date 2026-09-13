package cn.jia.config;

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.elasticsearch.autoconfigure.health.ElasticsearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Verifies the Boot health-contributor boundary without opening an Elasticsearch connection.
 * The production liveness endpoint remains an aggregate that excludes Elasticsearch by default;
 * an operator must explicitly opt in before the contributor is registered.
 */
class ElasticsearchProductionHealthIsolationTest {

    @Test
    void productionDefaultDoesNotRegisterAContributorThatCouldMakeAggregateHealthDown() throws Exception {
        String productionHealthEnabled = productionHealthEnabled();
        assertEquals("false", productionHealthEnabled);
        Rest5Client unreachableClient = mock(Rest5Client.class);

        contextWith(unreachableClient)
                .withPropertyValues("management.health.elasticsearch.enabled=" + productionHealthEnabled)
                .run(context -> {
                    assertFalse(context.containsBean("elasticsearchHealthContributor"));
                    assertTrue(context.getBeansOfType(HealthContributor.class).isEmpty());
                    verifyNoInteractions(unreachableClient);
                });
    }

    @Test
    void elasticsearchHealthRequiresExplicitOptInAndRegistrationDoesNotProbeTheClient() {
        Rest5Client unreachableClient = mock(Rest5Client.class);

        contextWith(unreachableClient)
                .withPropertyValues("management.health.elasticsearch.enabled=true")
                .run(context -> {
                    assertTrue(context.containsBean("elasticsearchHealthContributor"));
                    assertFalse(context.getBeansOfType(HealthContributor.class).isEmpty());
                    verifyNoInteractions(unreachableClient);
                });
    }

    private String productionHealthEnabled() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application-prod.properties"));
        return properties.getProperty("management.health.elasticsearch.enabled");
    }

    private ApplicationContextRunner contextWith(Rest5Client client) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ElasticsearchRestHealthContributorAutoConfiguration.class))
                .withBean(Rest5Client.class, () -> client);
    }
}
