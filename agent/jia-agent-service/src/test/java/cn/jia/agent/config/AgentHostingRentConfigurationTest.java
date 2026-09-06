package cn.jia.agent.config;

import cn.jia.agent.service.HostingRentAdmissionException;
import cn.jia.agent.service.HostingRentAdmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHostingRentConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(AgentHostingRentConfiguration.class);

    @Test
    void absentConfigurationIsDisabledAndNotConfigured() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            AgentHostingRentProperties properties = context.getBean(AgentHostingRentProperties.class);
            assertFalse(properties.enabled());
            assertTrue(properties.descriptorConfigured());
            assertEquals("1", properties.planVersion());
            assertEquals("1000000000", properties.amountMicro());
            assertEquals("2592000", properties.periodSeconds());
            assertFalse(properties.configured());
            assertReason(context.getBean(HostingRentAdmissionService.class),
                    HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED);
        });
    }

    @Test
    void disabledOrIncompleteConfigurationCannotActivateAdmission() {
        RUNNER.withPropertyValues(
                        "agent.hosting-rent.enabled=false",
                        "agent.hosting-rent.plan-version=1",
                        "agent.hosting-rent.amount-micro=1000000000",
                        "agent.hosting-rent.period-seconds=2592000")
                .run(context -> assertReason(context.getBean(HostingRentAdmissionService.class),
                        HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED));

        RUNNER.withPropertyValues(
                        "agent.hosting-rent.enabled=true",
                        "agent.hosting-rent.plan-version=1",
                        "agent.hosting-rent.amount-micro=01",
                        "agent.hosting-rent.period-seconds=2592000")
                .run(context -> assertReason(context.getBean(HostingRentAdmissionService.class),
                        HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED));
    }

    @Test
    void completeVersionedConfigurationStillCannotOpenLegacyFreeHosting() {
        RUNNER.withPropertyValues(
                        "agent.hosting-rent.enabled=true",
                        "agent.hosting-rent.plan-version=1",
                        "agent.hosting-rent.amount-micro=1000000000",
                        "agent.hosting-rent.period-seconds=2592000")
                .run(context -> {
                    AgentHostingRentProperties properties = context.getBean(AgentHostingRentProperties.class);
                    assertTrue(properties.descriptorConfigured());
                    assertTrue(properties.configured());
                    assertEquals("1", properties.planVersion());
                    assertEquals("1000000000", properties.amountMicro());
                    assertEquals("2592000", properties.periodSeconds());
                    assertReason(context.getBean(HostingRentAdmissionService.class),
                            HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_READY);
                });
    }

    private static void assertReason(HostingRentAdmissionService service,
            HostingRentAdmissionException.Reason expected) {
        HostingRentAdmissionException failure = assertThrows(
                HostingRentAdmissionException.class, service::requireServerBindAvailable);
        assertEquals(expected, failure.reason());
    }
}
