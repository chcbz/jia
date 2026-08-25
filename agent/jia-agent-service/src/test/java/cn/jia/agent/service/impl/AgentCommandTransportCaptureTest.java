package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AgentCommandTransportWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandTransportCaptureTest {
    @Test
    void flagsOffDoesNotResolveWriterOrTouchTransport() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> provider = mock(ObjectProvider.class);
        AgentCommandTransportCapture capture = new AgentCommandTransportCapture(
                gate(AgentRabbitActivationState.OFF), provider);

        assertFalse(capture.captureTaskInvites(null, null, null, 0));

        verify(provider, never()).getIfAvailable();
    }

    @Test
    void enabledWithoutWriterFailsClosed() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        AgentCommandTransportCapture capture = new AgentCommandTransportCapture(
                gate(AgentRabbitActivationState.DB_SHADOW), provider);

        assertThrows(IllegalStateException.class,
                () -> capture.captureTaskInvites(null, null, null, 0));
    }

    @Test
    void captureReportsDurableOwnershipOnlyForExactCanaryScope() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> provider = mock(ObjectProvider.class);
        AgentCommandTransportWriter writer = mock(AgentCommandTransportWriter.class);
        when(provider.getIfAvailable()).thenReturn(writer);
        AgentTaskDTO task = task();
        List<AgentRuntimeEntity> agents = List.of(agent("agent-b"), agent("agent-a"));

        AgentCommandTransportCapture shadow = new AgentCommandTransportCapture(
                gate(AgentRabbitActivationState.DB_SHADOW), provider);
        assertFalse(shadow.captureTaskInvites(task, agents, "evt-assigned", 1_000L));

        AgentCommandTransportCapture canary = new AgentCommandTransportCapture(
                gate(AgentRabbitActivationState.DISPATCH_CANARY), provider);
        assertTrue(canary.captureTaskInvites(task, agents, "evt-assigned", 1_000L));

        ArgumentCaptor<AgentCommandDraft> drafts = ArgumentCaptor.forClass(AgentCommandDraft.class);
        verify(writer, org.mockito.Mockito.times(4)).write(drafts.capture());
        assertTrue(drafts.getAllValues().stream().allMatch(draft ->
                "TASK_INVITE".equals(draft.commandType())));
    }

    private AgentTaskDTO task() {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId("task-1");
        task.setTenantId("tenant-a");
        task.setClientId("client-a");
        task.setTitle("Task One");
        task.setRequiredAbilities(List.of("analysis"));
        return task;
    }

    private AgentRuntimeEntity agent(String agentId) {
        AgentRuntimeEntity agent = new AgentRuntimeEntity();
        agent.setAgentId(agentId);
        return agent;
    }

    private AgentRabbitSafetyGate gate(AgentRabbitActivationState state) {
        boolean outbox = state != AgentRabbitActivationState.OFF;
        boolean topology = state == AgentRabbitActivationState.MQ_SHADOW
                || state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        boolean dispatch = state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(outbox),
                new AgentRabbitSafetyProperties.RabbitTopology(topology),
                new AgentRabbitSafetyProperties.RabbitPublish(dispatch),
                new AgentRabbitSafetyProperties.RabbitConsume(dispatch),
                new AgentRabbitSafetyProperties.RabbitDispatch(dispatch),
                topology ? new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated") : null);
        AgentRabbitDispatchScopeProperties scopes = dispatch
                ? new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                "tenant-a", "client-a")))
                : null;
        return new AgentRabbitSafetyGate(properties, scopes);
    }
}
