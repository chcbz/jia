package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentCommandRedriveRabbitSettings;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.entity.*;
import cn.jia.core.tenant.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Broker/settlement fixture only. Real authority locking and transaction races are
 * covered by AgentCommandDlqRedriverV2AdmissionTest, not by these stubs. */
final class AgentCommandRedriveTestFixture {
    static final WorkerScope SCOPE = new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"),
            "pool-a", "deployment-a", 7, 11, 13, "agent-command", 17);
    static final AgentRabbitV2TopologyManifest TOPOLOGY = AgentRabbitV2TopologyManifest.forScope(SCOPE);
    static final AgentCommandRedriveRabbitSettings SETTINGS = new AgentCommandRedriveRabbitSettings(
            2000, 2000, 2000, 1000, 15000, 10000, 500, 12);

    static AgentConfirmedPublishRequest request(String messageId, String eventId) {
        byte[] wire = ("{\"schemaVersion\":2,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"" + messageId + "\",\"commandId\":\"cmd-1\"," +
                "\"tenantId\":\"1\",\"dataDomainId\":\"cyf\"," +
                "\"executionPoolId\":\"pool-a\",\"producerDeploymentId\":\"producer-a\"," +
                "\"admissionEpoch\":13,\"moduleId\":\"agent-command\",\"migrationEpoch\":17," +
                "\"deliveryId\":\"1\",\"clientId\":\"client-a\",\"taskId\":\"task-1\"," +
                "\"targetAgentId\":\"agent-a\",\"commandType\":\"TASK_INVITE\"," +
                "\"attempt\":1,\"expiresAt\":4000000000000,\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        return new AgentConfirmedPublishRequest(TOPOLOGY.mainExchange(), TOPOLOGY.generalRoutingKey(),
                wire, AgentCommandAmqpContract.sha256(wire), messageId, eventId, 1L, "cmd-1", "1", "cyf",
                "pool-a", 13, "producer-a", "agent-command", 17, 2, "client-a", "task-1", "agent-a",
                "TASK_INVITE", 1, 4000000000000L, TOPOLOGY.sha256(), 0);
    }

    static AgentCommandDlqRedriverImpl redriver(AgentCommandRedriveRawProvider provider,
            AgentConfirmedPublishRequest expected) {
        var dao=mock(AgentCommandOperationsDao.class);
        var d=new AgentCommandDeliveryEntity();
        d.setId(expected.deliveryId()); d.setProtocolVersion(2); d.setTenantId(expected.tenantId());
        d.setClientId(expected.clientId()); d.setDataDomainId(expected.dataDomainId());
        d.setExecutionPoolId(expected.executionPoolId()); d.setAdmissionEpoch(expected.admissionEpoch());
        d.setProducerDeploymentId(expected.producerDeploymentId()); d.setModuleId(expected.moduleId());
        d.setMigrationEpoch(expected.migrationEpoch()); d.setCommandId(expected.commandId());
        d.setTaskId(expected.taskId()); d.setTargetAgentId(expected.targetAgentId());
        d.setCommandType(expected.commandType()); d.setStatus("PUBLISHED");
        d.setActiveMessageId(expected.messageId()); d.setActiveAttempt(expected.activeAttempt());
        d.setExpiresAt(expected.expiresAt());
        var o=new AgentOutboxEventEntity(); o.setId(2L); o.setProtocolVersion(2);
        o.setTenantId(expected.tenantId()); o.setClientId(expected.clientId());
        o.setDataDomainId(expected.dataDomainId()); o.setExecutionPoolId(expected.executionPoolId());
        o.setAdmissionEpoch(expected.admissionEpoch()); o.setProducerDeploymentId(expected.producerDeploymentId());
        o.setModuleId(expected.moduleId()); o.setMigrationEpoch(expected.migrationEpoch());
        o.setEventId(expected.eventId()); o.setMessageId(expected.messageId()); o.setCommandId(expected.commandId());
        o.setDeliveryId(expected.deliveryId()); o.setAggregateType("task"); o.setAggregateId(expected.taskId());
        o.setDestination(expected.destination()); o.setRoutingKey(expected.routingKey());
        o.setWirePayload(expected.wirePayload()); o.setWirePayloadHash(expected.wirePayloadHash());
        o.setStatus("PUBLISHED"); o.setAttemptCount(1); o.setActiveAttempt(expected.activeAttempt());
        o.setExpiresAt(expected.expiresAt()); o.setPublisherConfirmStatus("ACK"); o.setConfirmedAt(1L);
        o.setMandatoryReturnStatus("NOT_RETURNED"); o.setPublishedAt(1L);
        when(dao.lockDelivery(expected.tenantId(),expected.clientId(),expected.deliveryId())).thenReturn(d);
        when(dao.lockActiveOutboxes(expected.tenantId(),expected.clientId(),expected.deliveryId(),expected.messageId()))
                .thenReturn(List.of(o));
        var guard=mock(TenantModuleAdmissionGuard.class);
        when(guard.withWorkerAdmission(eq(SCOPE),eq(TenantModuleAdmissionGuard.WorkerOperation.PUBLISH),any()))
                .thenAnswer(call -> call.<Function<TenantModuleAdmissionGuard.Mode,Object>>getArgument(2)
                        .apply(TenantModuleAdmissionGuard.Mode.DOMAIN));
        var transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return new AgentCommandDlqRedriverImpl(provider,dao,SCOPE,guard,transactions,SETTINGS);
    }
}
