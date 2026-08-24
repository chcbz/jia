package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentCommandRabbitConsumerConfigurationTest {
    private final AgentConfirmedRabbitPublisher publisher =
            mock(AgentConfirmedRabbitPublisher.class);
    private final AgentRabbitTopologyManifest manifest =
            AgentRabbitTopologyManifest.canonical();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentCommandRabbitConsumer.class)
            .withBean(AgentCommandInboxService.class,
                    () -> mock(AgentCommandInboxService.class))
            .withBean(AgentTaskCollaborationAccessService.class,
                    () -> mock(AgentTaskCollaborationAccessService.class))
            .withBean(AgentRawCommandDispatcher.class,
                    () -> mock(AgentRawCommandDispatcher.class))
            .withBean("agentConfirmedRabbitPublisher", AgentConfirmedRabbitPublisher.class,
                    () -> publisher)
            .withBean(AgentRabbitSafetyGate.class,
                    () -> mock(AgentRabbitSafetyGate.class))
            .withBean("agentRabbitTopologyManifest", AgentRabbitTopologyManifest.class,
                    () -> manifest);

    @Test
    void dispatchFalseRegistersNoConsumerEvenWhenDependenciesExist() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("agentCommandRabbitConsumer"));
            assertTrue(context.getBeansOfType(AgentCommandRabbitConsumer.class).isEmpty());
        });
    }

    @Test
    void dispatchTrueWiresOnlyExplicitDedicatedPublisherAndManifest() {
        runner.withPropertyValues("agent.rabbit-dispatch.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AgentCommandRabbitConsumer consumer =
                            context.getBean(AgentCommandRabbitConsumer.class);
                    assertSame(publisher, org.springframework.test.util.ReflectionTestUtils
                            .getField(consumer, "publisher"));
                    Object decoder = org.springframework.test.util.ReflectionTestUtils
                            .getField(consumer, "decoder");
                    assertSame(manifest, org.springframework.test.util.ReflectionTestUtils
                            .getField(decoder, "manifest"));
                });
    }
}
