package cn.jia.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the existing Spring Boot HTTP-server observation while making its route histogram useful for the
 * three-second SLO. This configuration deliberately does not create a servlet filter or change actuator exposure.
 *
 * <p>Boot supplies the normalized {@code uri} tag (including its neutral {@code UNKNOWN} value) for
 * {@code http.server.requests}; this filter never derives a tag from a raw request URI. The single route-cardinality
 * authority is {@code management.metrics.web.server.max-uri-tags}. One slot is reserved for {@code OVERFLOW}, so an
 * unexpected route-tag value is collapsed before Boot's deny filter rather than causing a new time series or being
 * silently denied. {@code uri=OVERFLOW} is explicit evidence that the route inventory/cardinality budget was
 * exceeded and must not be used to claim per-route SLO compliance.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jia.metrics.http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApiPerformanceMetricsConfig {

    static final String HTTP_SERVER_REQUESTS = "http.server.requests";
    static final String URI_TAG = "uri";
    static final String OVERFLOW_ROUTE = "OVERFLOW";
    static final String MAX_URI_TAGS_PROPERTY = "management.metrics.web.server.max-uri-tags";
    static final int DEFAULT_MAX_URI_TAGS = 1024;

    private static final String DEFAULTS_PROPERTY_SOURCE = "jiaApiPerformanceMetricsDefaults";

    /**
     * Supplies headroom only while this feature is enabled and only when the operator did not configure Boot's cap.
     * Adding the low-precedence source during bean-factory post-processing lets Boot and this filter bind the same
     * value without changing Boot's disabled-feature baseline.
     */
    @Bean
    static BeanFactoryPostProcessor apiPerformanceMetricsDefaults(ConfigurableEnvironment environment) {
        return beanFactory -> {
            if (!environment.containsProperty(MAX_URI_TAGS_PROPERTY)) {
                environment.getPropertySources().addLast(new MapPropertySource(
                        DEFAULTS_PROPERTY_SOURCE, Map.of(MAX_URI_TAGS_PROPERTY, DEFAULT_MAX_URI_TAGS)));
            }
        };
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    MeterFilter apiPerformanceHttpServerRequestsMeterFilter(
            @Value("${" + MAX_URI_TAGS_PROPERTY + "}") int maxUriTags) {
        if (maxUriTags < 2) {
            throw new IllegalArgumentException(MAX_URI_TAGS_PROPERTY + " must be at least 2");
        }
        return new BoundedHttpServerRequestsMeterFilter(maxUriTags);
    }

    private static final class BoundedHttpServerRequestsMeterFilter implements MeterFilter {
        private static final double[] SLO_BOUNDARIES = {
                Duration.ofSeconds(1).toNanos(),
                Duration.ofMillis(2500).toNanos(),
                Duration.ofSeconds(3).toNanos()
        };

        private final int normalRouteLimit;
        private final Set<String> admittedRoutes = new HashSet<>();

        private BoundedHttpServerRequestsMeterFilter(int maxUriTags) {
            this.normalRouteLimit = maxUriTags - 1;
        }

        @Override
        public Meter.Id map(Meter.Id id) {
            if (!isHttpServerRequestTimer(id)) {
                return id;
            }
            String route = id.getTag(URI_TAG);
            if (route == null || OVERFLOW_ROUTE.equals(route)) {
                return id;
            }
            return id.withTag(Tag.of(URI_TAG, boundedRoute(route)));
        }

        @Override
        public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
            if (!isHttpServerRequestTimer(id)) {
                return config;
            }
            return DistributionStatisticConfig.builder()
                    .percentilesHistogram(true)
                    .serviceLevelObjectives(SLO_BOUNDARIES)
                    .build()
                    .merge(config);
        }

        private static boolean isHttpServerRequestTimer(Meter.Id id) {
            return HTTP_SERVER_REQUESTS.equals(id.getName()) && Meter.Type.TIMER.equals(id.getType());
        }

        private synchronized String boundedRoute(String route) {
            if (admittedRoutes.contains(route)) {
                return route;
            }
            if (admittedRoutes.size() >= normalRouteLimit) {
                return OVERFLOW_ROUTE;
            }
            admittedRoutes.add(route);
            return route;
        }
    }
}
