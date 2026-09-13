package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.dto.OutputContextDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.service.AgentCommandTransportWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandTransportCaptureTest {
    @Test
    void flagsOffDoesNotResolveWriterOrTouchTransport() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutputRunAuthorizationService> outputRunProvider = mock(ObjectProvider.class);
        AgentCommandTransportCapture capture = new AgentCommandTransportCapture(
                gate(AgentRabbitActivationState.OFF), provider, outputRunProvider);

        assertFalse(capture.captureTaskInvites(null, null, null, 0));
        assertTrue(capture.prepareTaskOutputContexts(
                "tenant-a", "client-a", "task-1", List.of("agent-a")).isEmpty());

        verify(provider, never()).getIfAvailable();
        verify(outputRunProvider, never()).getIfAvailable();
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
    void policy1RefusesOffAndShadowTransportBeforeCreatingRun() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> writerProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutputRunAuthorizationService> outputProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentTaskWorkItemDao> workItemProvider = mock(ObjectProvider.class);
        when(writerProvider.getIfAvailable()).thenReturn(mock(AgentCommandTransportWriter.class));
        when(outputProvider.getIfAvailable()).thenReturn(mock(OutputRunAuthorizationService.class));
        when(workItemProvider.getIfAvailable()).thenReturn(mock(AgentTaskWorkItemDao.class));

        for (AgentRabbitActivationState state : List.of(
                AgentRabbitActivationState.OFF, AgentRabbitActivationState.DB_SHADOW,
                AgentRabbitActivationState.MQ_SHADOW)) {
            AgentCommandTransportCapture capture = new AgentCommandTransportCapture(
                    gate(state), writerProvider, outputProvider);
            capture.configureWorkItemDao(workItemProvider);
            assertThrows(IllegalStateException.class,
                    () -> capture.prepareTaskOutputDispatch(
                            "tenant-a", "client-a", "task-1", List.of("agent-a"),
                            1, "evt-policy1"));
        }
        verify(outputProvider, never()).getIfAvailable();
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
        verify(writer, org.mockito.Mockito.times(4)).write(drafts.capture(), isNull());
        assertTrue(drafts.getAllValues().stream().allMatch(draft ->
                "TASK_INVITE".equals(draft.commandType())));
    }

    @Test
    void policy1DispatchBindsRunAndCarriesTrustedWorkItemThroughCanonicalWire() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> writerProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutputRunAuthorizationService> outputRunProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentTaskWorkItemDao> workItemProvider = mock(ObjectProvider.class);
        AgentCommandTransportWriter writer = mock(AgentCommandTransportWriter.class);
        OutputRunAuthorizationService outputRuns = mock(OutputRunAuthorizationService.class);
        AgentTaskWorkItemDao workItems = mock(AgentTaskWorkItemDao.class);
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(outputRunProvider.getIfAvailable()).thenReturn(outputRuns);
        when(workItemProvider.getIfAvailable()).thenReturn(workItems);
        AgentCommandTransportCapture capture = new AgentCommandTransportCapture(
                gate(AgentRabbitActivationState.DISPATCH_CANARY),
                writerProvider, outputRunProvider);
        capture.configureWorkItemDao(workItemProvider);

        AgentTaskWorkItemEntity workItem = new AgentTaskWorkItemEntity()
                .setWorkItemId("work-1").setTaskId("task-1")
                .setAssigneeAgentId("agent-a").setStatus("ready")
                .setRequiredItem(true).setVersion(0L);
        when(workItems.listByTask("tenant-a", "client-a", "task-1", null, 2))
                .thenReturn(List.of(workItem));
        OutputContextDTO context = new OutputContextDTO(
                1, "0123456789abcdef0123456789abcdef",
                new OutputSourceDTO(OutputConstants.SOURCE_TASK, "task-1"),
                Long.toString(OutputConstants.DEFAULT_MAX_FILE_BYTES),
                Long.toString(OutputConstants.DEFAULT_MAX_RUN_BYTES),
                "outputs/0123456789abcdef0123456789abcdef/manifest.json",
                List.of(OutputConstants.CAPABILITY_HTTP_V1,
                        OutputConstants.CAPABILITY_OWNER_SHARE_V1,
                        OutputConstants.CAPABILITY_DELIVERY_HTTP_V1));
        when(outputRuns.createOrRecoverRuns(org.mockito.ArgumentMatchers.anyList(),
                eq(List.of("agent-a")))).thenReturn(Map.of("agent-a", context));
        when(workItems.bindDispatchedRun("tenant-a", "client-a", "task-1",
                "work-1", "agent-a", 0L, context.runId())).thenReturn(1);

        AgentCommandTransportCapture.PreparedTaskOutputContexts prepared =
                capture.prepareTaskOutputDispatch("tenant-a", "client-a", "task-1",
                        List.of("agent-a"), 1, "evt-assigned");
        workItem.setDispatchedRunId(context.runId());
        when(workItems.findByTaskAndWorkItemId(
                "tenant-a", "client-a", "task-1", "work-1")).thenReturn(workItem);
        AgentTaskDTO policyTask = task();
        policyTask.setDeliveryPolicyVersion("1");
        capture.captureTaskInvites(policyTask, List.of(agent("agent-a")),
                "evt-assigned", 1_000L, prepared.contexts(), prepared.workItemIds());

        ArgumentCaptor<AgentCommandDraft> draft = ArgumentCaptor.forClass(AgentCommandDraft.class);
        verify(writer).write(draft.capture(), eq(context));
        assertEquals("work-1", draft.getValue().workItemId());
        assertTrue(((cn.jia.agent.entity.AgentTaskInvitePayload) draft.getValue().payload())
                .acceptance().contains("正式交付接口"));
        assertEquals(context, AgentCommandCanonicalCodec.outputContextFromWire(
                AgentCommandCanonicalCodec.wireBytes(
                        draft.getValue(), "msg-policy1", 1, context)).orElseThrow());
    }

    @Test
    void deliveryCapabilityWithoutTrustedWorkItemIsRejectedByCodec() {
        AgentCommandDraft draft = new AgentCommandDraft(
                1, AgentCommandCanonicalCodec.taskInviteCommandId(
                        "tenant-a", "client-a", "task-1", "agent-a"),
                "task-1", "evt-assigned", "tenant-a", "client-a", "task-1", null,
                "agent-a", "TASK_INVITE", 1_000L, 2_000L,
                new cn.jia.agent.entity.AgentTaskInvitePayload(
                        "task_briefing", "reason", "instruction", "Task One", List.of(),
                        "agent-a", List.of("agent-a"), "coordinator", "acceptance", "juyiting"));
        OutputContextDTO context = new OutputContextDTO(
                1, "0123456789abcdef0123456789abcdef",
                new OutputSourceDTO(OutputConstants.SOURCE_TASK, "task-1"),
                Long.toString(OutputConstants.DEFAULT_MAX_FILE_BYTES),
                Long.toString(OutputConstants.DEFAULT_MAX_RUN_BYTES),
                "outputs/0123456789abcdef0123456789abcdef/manifest.json",
                List.of(OutputConstants.CAPABILITY_HTTP_V1,
                        OutputConstants.CAPABILITY_OWNER_SHARE_V1,
                        OutputConstants.CAPABILITY_DELIVERY_HTTP_V1));

        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandCanonicalCodec.wireBytes(draft, "msg-policy1", 1, context));
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
