package cn.jia.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the existing Spring Boot HTTP-server observation while making its route histogram useful for the
 * three-second SLO. This configuration deliberately does not create a servlet filter or change actuator exposure.
 *
 * <p>Boot supplies the normalized {@code uri} tag (including its neutral {@code UNKNOWN} value) for
 * {@code http.server.requests}; this filter never derives a tag from a raw request URI. At most
 * {@code max-uri-tags} values are retained. The high-precedence filter runs before Spring Boot's built-in
 * {@code management.metrics.web.server.max-uri-tags} deny filter; both caps are configured to the same value.
 * One slot is reserved for {@code OVERFLOW}, so an unexpected route-tag value is collapsed rather than causing a
 * new time series or being denied. Consequently, no
 * {@code http.server.requests} meter is silently dropped: {@code uri=OVERFLOW} is explicit evidence that the
 * route inventory/cardinality budget was exceeded and must not be used to claim per-route SLO compliance.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "jia.metrics.http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApiPerformanceMetricsConfig {

    static final String HTTP_SERVER_REQUESTS = "http.server.requests";
    static final String URI_TAG = "uri";
    static final String OVERFLOW_ROUTE = "OVERFLOW";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    MeterFilter apiPerformanceHttpServerRequestsMeterFilter(
            @Value("${jia.metrics.http.max-uri-tags:1024}") int maxUriTags) {
        if (maxUriTags < 2) {
            throw new IllegalArgumentException("jia.metrics.http.max-uri-tags must be at least 2");
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
