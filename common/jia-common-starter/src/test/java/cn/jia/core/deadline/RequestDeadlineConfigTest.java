package cn.jia.core.deadline;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestDeadlineConfigTest {
    @Test
    void registersShadowFilterAfterRequestIdAcrossAllRequestDispatches() {
        var registration = new RequestDeadlineConfig().requestDeadlineFilter("shadow", 3000);

        assertInstanceOf(RequestDeadlineFilter.class, registration.getFilter());
        assertEquals(RequestDeadlineConfig.FILTER_ORDER, registration.getOrder());
        assertEquals(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR),
                registration.determineDispatcherTypes());
    }

    @Test
    void rejectsAccidentalEnforcementOrUnsafeBudgetConfiguration() {
        RequestDeadlineConfig config = new RequestDeadlineConfig();

        assertThrows(IllegalArgumentException.class, () -> config.requestDeadlineFilter("enforce", 3000));
        assertThrows(IllegalArgumentException.class, () -> config.requestDeadlineFilter("", 3000));
        assertThrows(IllegalArgumentException.class, () -> config.requestDeadlineFilter("shadow", 0));
        assertThrows(IllegalArgumentException.class,
                () -> config.requestDeadlineFilter("shadow", RequestDeadlineFilter.MAX_SERVER_BUDGET_MILLIS + 1));
    }
}
