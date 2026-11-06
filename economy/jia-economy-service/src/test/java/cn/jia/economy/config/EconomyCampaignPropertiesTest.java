package cn.jia.economy.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static cn.jia.economy.config.EconomyCampaignProperties.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomyCampaignPropertiesTest {
    @Test void effectiveWindowIsStartInclusiveAndEndExclusive() {
        var rule = new Campaign("beta", Event.HALL_ENTERED, true, 3500, 100L, 200L);
        assertFalse(rule.eligible(Event.HALL_ENTERED, 99));
        assertTrue(rule.eligible(Event.HALL_ENTERED, 100));
        assertTrue(rule.eligible(Event.HALL_ENTERED, 199));
        assertFalse(rule.eligible(Event.HALL_ENTERED, 200));
    }
    @Test void duplicateCampaignIdsAndInvalidAmountsCannotBeEnabled() {
        var rule = new Campaign("beta", Event.HALL_ENTERED, true, 3500, null, null);
        assertThrows(IllegalArgumentException.class, () -> new EconomyCampaignProperties(List.of(rule, rule)));
        assertThrows(IllegalArgumentException.class, () -> new Campaign("beta", Event.HALL_ENTERED, true, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Campaign("beta", Event.HALL_ENTERED, true, 3500, 2L, 1L));
        assertTrue(EconomyCampaignProperties.disabled().grants().isEmpty());
    }
}
