package cn.jia.core.config;

import cn.jia.core.filter.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RequestIdFilterConfigTest {
    @Test
    void registersRequestIdFilterForRequestAsyncAndErrorDispatches() {
        var registration = new RequestIdFilterConfig().requestIdFilter();

        assertInstanceOf(RequestIdFilter.class, registration.getFilter());
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 2, registration.getOrder());
        assertEquals(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR),
                registration.determineDispatcherTypes());
    }
}
