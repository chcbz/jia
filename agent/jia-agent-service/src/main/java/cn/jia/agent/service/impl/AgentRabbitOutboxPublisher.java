package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentOutboxPublisher;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** D03 outbox-token adapter with a send-time control-plane admission lock. */
public final class AgentRabbitOutboxPublisher implements AgentOutboxPublisher {
    private final AgentConfirmedRabbitPublisher publisher;
    private final AgentRabbitTopologyManifest manifest;
    private final TenantModuleAdmissionGuard admissionGuard;
    private final TransactionTemplate publishTransaction;

    public AgentRabbitOutboxPublisher(
            AgentConfirmedRabbitPublisher publisher,
            AgentRabbitTopologyManifest manifest,
            TenantModuleAdmissionGuard admissionGuard,
            PlatformTransactionManager transactionManager,
            AgentOutboxRelaySettings settings) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.admissionGuard = Objects.requireNonNull(admissionGuard, "admissionGuard");
        Objects.requireNonNull(transactionManager, "transactionManager");
        Objects.requireNonNull(settings, "settings");
        publishTransaction = new TransactionTemplate(transactionManager);
        publishTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long timeoutSeconds = Math.addExact(
                Math.floorDiv(settings.confirmTimeoutMillis() - 1L, 1000L), 2L);
        publishTransaction.setTimeout(Math.toIntExact(timeoutSeconds));
    }

    /** Legacy construction is fail-closed and retained only for source compatibility. */
    @Deprecated
    public AgentRabbitOutboxPublisher(
            AgentConfirmedRabbitPublisher publisher,
            AgentRabbitTopologyManifest manifest) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.admissionGuard = null;
        this.publishTransaction = null;
    }

    @Override
    public AgentRabbitPublishResult publish(
            WorkerScope scope, AgentOutboxClaimToken token, long confirmTimeoutMillis) {
        if (scope == null || token == null || admissionGuard == null
                || publishTransaction == null || !tokenMatchesScope(scope, token)) {
            return rejected("WORKER_AUTHORITY_REQUIRED");
        }
        AgentRabbitV2TopologyManifest scopedManifest =
                AgentRabbitV2TopologyManifest.forScope(scope);
        if (!scopedManifest.allowsCommandPublish(
                token.destination(), token.routingKey())) {
            return rejected("SCOPED_ROUTE_MISMATCH");
        }
        AtomicBoolean admitted = new AtomicBoolean();
        try {
            AgentRabbitPublishResult result = publishTransaction.execute(ignored ->
                    admissionGuard.withWorkerAdmission(scope,
                            TenantModuleAdmissionGuard.WorkerOperation.PUBLISH,
                            mode -> {
                                admitted.set(true);
                                return publisher.publish(request(token, scopedManifest), confirmTimeoutMillis);
                            }));
            return result == null ? rejected("PUBLISH_AUTHORITY_TRANSACTION_FAILED") : result;
        } catch (RuntimeException denied) {
            return rejected(admitted.get()
                    ? "PUBLISH_AUTHORITY_TRANSACTION_FAILED"
                    : "WORKER_ADMISSION_REJECTED");
        }
    }

    private AgentConfirmedPublishRequest request(
            AgentOutboxClaimToken token, AgentRabbitV2TopologyManifest scopedManifest) {
        return new AgentConfirmedPublishRequest(
                token.destination(), token.routingKey(), token.wirePayload(), token.wirePayloadHash(),
                token.messageId(), token.eventId(), token.deliveryId(), token.commandId(),
                token.tenantId(), token.dataDomainId(), token.executionPoolId(),
                token.admissionEpoch(), token.producerDeploymentId(), token.moduleId(),
                token.migrationEpoch(), token.protocolVersion(), token.clientId(),
                token.taskId(), token.targetAgentId(), token.commandType(),
                token.deliveryActiveAttempt(), token.expiresAt(), scopedManifest.sha256(),
                AgentCommandAmqpContract.INITIAL_SOURCE_SETTLEMENT_RETRY);
    }

    private static boolean tokenMatchesScope(WorkerScope scope, AgentOutboxClaimToken token) {
        return scope.tenantId().value().equals(token.tenantId())
                && scope.dataDomainId().value().equals(token.dataDomainId())
                && scope.executionPoolId().equals(token.executionPoolId())
                && scope.admissionEpoch() == token.admissionEpoch()
                && scope.moduleId().equals(token.moduleId())
                && scope.migrationEpoch() == token.migrationEpoch()
                && scope.deploymentId().equals(token.claimantDeploymentId())
                && scope.workerGeneration() == token.claimantWorkerGeneration()
                && token.protocolVersion() == AgentCommandAmqpContract.WIRE_VERSION;
    }

    private static AgentRabbitPublishResult rejected(String reason) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION,
                "NONE", "NOT_RETURNED", null, null, reason);
    }
}
