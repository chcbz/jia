package cn.jia.base.config;

import cn.jia.base.filter.UriAccessLogFilter;
import cn.jia.base.service.LogService;
import cn.jia.core.audit.BoundedAuditDispatcher;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class UriAccessLogConfigTest {
    @Test
    void registersBoundedRequestOnlyAuditAfterContextAndRequestIdFilters() {
        UriAccessLogConfig config = new UriAccessLogConfig();
        LogService logService = mock(LogService.class);
        try (BoundedAuditDispatcher dispatcher = config.uriAccessAuditDispatcher(8, 5, 20, 1000)) {
            var registration = config.uriAccessLogFilter(logService, dispatcher);

            assertInstanceOf(UriAccessLogFilter.class, registration.getFilter());
            assertEquals(Ordered.HIGHEST_PRECEDENCE + 3, registration.getOrder());
            assertEquals(EnumSet.of(DispatcherType.REQUEST), registration.determineDispatcherTypes());
        }
    }
}
