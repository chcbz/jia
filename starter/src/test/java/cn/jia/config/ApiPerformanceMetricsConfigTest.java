package cn.jia.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.MaximumAllowableTagsMeterFilter;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPerformanceMetricsConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ApiPerformanceMetricsConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    private final ApplicationContextRunner bootBindingContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class))
            .withUserConfiguration(ApiPerformanceMetricsConfig.class);

    @Test
    void appliesThreeSecondSloBucketsToHttpServerTimersOnly() {
        contextRunner.run(context -> {
            MeterFilter filter = context.getBean(MeterFilter.class);
            DistributionStatisticConfig httpConfig = filter.configure(
                    id(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS, Meter.Type.TIMER, "/agent/{agentId}"),
                    DistributionStatisticConfig.DEFAULT);
            DistributionStatisticConfig unrelatedTimerConfig = filter.configure(
                    id("jvm.gc.pause", Meter.Type.TIMER, "/not-a-route"), DistributionStatisticConfig.DEFAULT);
            DistributionStatisticConfig sameNameCounterConfig = filter.configure(
                    id(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS, Meter.Type.COUNTER, "/not-a-timer"),
                    DistributionStatisticConfig.DEFAULT);

            assertTrue(httpConfig.isPublishingHistogram());
            assertArrayEquals(new double[] {
                    Duration.ofSeconds(1).toNanos(),
                    Duration.ofMillis(2500).toNanos(),
                    Duration.ofSeconds(3).toNanos()
            }, httpConfig.getServiceLevelObjectiveBoundaries());
            assertSame(DistributionStatisticConfig.DEFAULT, unrelatedTimerConfig);
            assertSame(DistributionStatisticConfig.DEFAULT, sameNameCounterConfig);
        });
    }

    @Test
    void installsEnabledDefaultBeforeBootBindingWithoutOverridingAnExplicitCap() {
        bootBindingContextRunner.run(context -> {
            assertEquals(ApiPerformanceMetricsConfig.DEFAULT_MAX_URI_TAGS,
                    context.getEnvironment().getProperty(ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY,
                            Integer.class));
            assertEquals(ApiPerformanceMetricsConfig.DEFAULT_MAX_URI_TAGS,
                    context.getBean(MetricsProperties.class).getWeb().getServer().getMaxUriTags());
        });

        bootBindingContextRunner.withPropertyValues(ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY + "=37")
                .run(context -> {
                    assertEquals(37, context.getEnvironment().getProperty(
                            ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY, Integer.class));
                    assertEquals(37, context.getBean(MetricsProperties.class).getWeb().getServer().getMaxUriTags());
                });
    }

    @Test
    void usesBootCapAsSingleAuthorityAndCountsCollapsedOverflowMeters() {
        contextRunner.withPropertyValues(
                ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY + "=3",
                "jia.metrics.http.max-uri-tags=2")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    registry.config().meterFilter(context.getBean(MeterFilter.class));
                    registry.config().meterFilter(new MaximumAllowableTagsMeterFilter(
                            ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS,
                            ApiPerformanceMetricsConfig.URI_TAG,
                            context.getEnvironment().getProperty(
                                    ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY, Integer.class)));

                    Timer first = timer(registry, "/agent/{agentId}");
                    Timer second = timer(registry, "/task/{taskId}");
                    Timer overflowOne = timer(registry, "/unexpected-one");
                    Timer overflowTwo = timer(registry, "/unexpected-two");
                    first.record(Duration.ofMillis(10));
                    second.record(Duration.ofMillis(20));
                    overflowOne.record(Duration.ofSeconds(1));
                    overflowTwo.record(Duration.ofSeconds(3));

                    assertEquals("/agent/{agentId}", first.getId().getTag(ApiPerformanceMetricsConfig.URI_TAG));
                    assertEquals("/task/{taskId}", second.getId().getTag(ApiPerformanceMetricsConfig.URI_TAG));
                    assertEquals(ApiPerformanceMetricsConfig.OVERFLOW_ROUTE,
                            overflowOne.getId().getTag(ApiPerformanceMetricsConfig.URI_TAG));
                    assertSame(overflowOne, overflowTwo);
                    assertEquals(2, overflowOne.count());
                    assertEquals(3, registry.find(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS).timers().size());
                });
    }

    @Test
    void rejectsInvalidAuthoritativeCaps() {
        contextRunner.withPropertyValues(ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY + "=1")
                .run(context -> assertNotNull(context.getStartupFailure()));
        contextRunner.withPropertyValues(ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY + "=not-a-number")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void leavesUnrelatedMetersAndDisabledBootBaselineUntouched() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            registry.config().meterFilter(context.getBean(MeterFilter.class));

            Counter counter = Counter.builder(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS)
                    .tag(ApiPerformanceMetricsConfig.URI_TAG, "/raw-value-is-not-remapped-for-counters")
                    .register(registry);
            assertEquals("/raw-value-is-not-remapped-for-counters",
                    counter.getId().getTag(ApiPerformanceMetricsConfig.URI_TAG));
        });

        contextRunner.withPropertyValues("jia.metrics.http.enabled=false").run(context -> {
            assertFalse(context.containsBean("apiPerformanceMetricsDefaults"));
            assertFalse(context.containsBean("apiPerformanceHttpServerRequestsMeterFilter"));
            assertNull(context.getEnvironment().getProperty(ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY));
        });

        contextRunner.withPropertyValues(
                "jia.metrics.http.enabled=false",
                ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY + "=37")
                .run(context -> assertEquals(37, context.getEnvironment().getProperty(
                        ApiPerformanceMetricsConfig.MAX_URI_TAGS_PROPERTY, Integer.class)));
    }

    private static Meter.Id id(String name, Meter.Type type, String route) {
        return new Meter.Id(name, Tags.of(ApiPerformanceMetricsConfig.URI_TAG, route), null, null, type);
    }

    private static Timer timer(MeterRegistry registry, String route) {
        return Timer.builder(ApiPerformanceMetricsConfig.HTTP_SERVER_REQUESTS)
                .tag(ApiPerformanceMetricsConfig.URI_TAG, route)
                .register(registry);
    }
}
