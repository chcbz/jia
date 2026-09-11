package cn.jia.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.MaximumAllowableTagsMeterFilter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPerformanceMetricsConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ApiPerformanceMetricsConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void appliesThreeSecondSloBucketsToHttpServerTimersOnly() {
        contextRunner.run(context -> {
            MeterFilter filter = context.getBean(MeterFilter.class);
            DistributionStatisticConfig httpConfig = filter.configure(
                    id(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS, Meter.Type.TIMER, "/agent/{agentId}"),
                    DistributionStatisticConfig.DEFAULT);
            DistributionStatisticConfig unrelatedTimerConfig = filter.configure(
                    id("jvm.gc.pause", Meter.Type.TIMER, "/not-a-route"), DistributionStatisticConfig.DEFAULT);

            assertTrue(httpConfig.isPublishingHistogram());
            assertArrayEquals(new double[] {
                    Duration.ofSeconds(1).toNanos(),
                    Duration.ofMillis(2500).toNanos(),
                    Duration.ofSeconds(3).toNanos()
            }, httpConfig.getServiceLevelObjectiveBoundaries());
            assertSame(DistributionStatisticConfig.DEFAULT, unrelatedTimerConfig);
        });
    }

    @Test
    void preservesBoundedRouteTagsAndRecordsOverflowInsteadOfDroppingMeters() {
        contextRunner.withPropertyValues("jia.metrics.http.max-uri-tags=2").run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            registry.config().meterFilter(context.getBean(MeterFilter.class));
            // Mirrors the Boot deny filter after our @Order(HIGHEST_PRECEDENCE) overflow mapper.
            registry.config().meterFilter(new MaximumAllowableTagsMeterFilter(
                    ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS, ApiPerformanceMetricsConfig.URI_TAG, 2));

            Timer admitted = Timer.builder(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS)
                    .tag(ApiPerformanceMetricsConfig.URI_TAG, "/agent/{agentId}")
                    .register(registry);
            Timer overflow = Timer.builder(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS)
                    .tag(ApiPerformanceMetricsConfig.URI_TAG, "/untrusted-looking-route-42")
                    .register(registry);
            admitted.record(Duration.ofSeconds(1));
            overflow.record(Duration.ofSeconds(3));

            assertEquals("/agent/{agentId}", admitted.getId().getTag(ApiPerformanceMetricsConfig.URI_TAG));
            assertEquals(ApiPerformanceMetricsConfig.OVERFLOW_ROUTE,
                    overflow.getId().getTag(ApiPerformanceMetricsConfig.URI_TAG));
            assertEquals(1, overflow.count());
        });
    }

    @Test
    void leavesUnrelatedMetersAndDisabledConfigurationUntouched() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            registry.config().meterFilter(context.getBean(MeterFilter.class));

            Counter counter = Counter.builder(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS)
                    .tag(ApiPerformanceMetricsConfig.URI_TAG, "/raw-value-is-not-remapped-for-counters")
                    .register(registry);
            assertEquals("/raw-value-is-not-remapped-for-counters",
                    counter.getId().getTag(ApiPerformanceMetricsConfig.URI_TAG));
        });

        contextRunner.withPropertyValues("jia.metrics.http.enabled=false").run(context ->
                assertFalse(context.containsBean("apiPerformanceHttpServerRequestsMeterFilter")));
    }
}
