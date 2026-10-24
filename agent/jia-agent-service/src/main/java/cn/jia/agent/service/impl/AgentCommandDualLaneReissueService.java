package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.core.tenant.ProducerScope;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;

/** Explicit protocol router over independent legacy and scoped recovery lanes. */
public final class AgentCommandDualLaneReissueService implements AgentCommandReissueService {
    private final AgentCommandRecoveryDao dao;
    private final AgentCommandReissueService legacyLane;
    private final AgentCommandReissueService scopedLane;
    private final WorkerScope scopedWorkerScope;

    public AgentCommandDualLaneReissueService(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            TenantModuleAdmissionGuard admissionGuard,
            WorkerScope workerScope,
            PlatformTransactionManager transactionManager) {
        this.dao = Objects.requireNonNull(dao, "dao");
        legacyLane = new AgentCommandReissueServiceImpl(dao, gate, dispatcher, manifest,
                sentAckTimeoutMillis, null, null, transactionManager);
        if ((admissionGuard == null) != (workerScope == null)) {
            throw new IllegalStateException("Agent recovery worker authority is incomplete");
        }
        if (workerScope != null && "0".equals(workerScope.tenantId().value())) {
            throw new IllegalStateException(
                    "Protocol-v2 recovery cannot share the legacy tenant-zero selector");
        }
        scopedWorkerScope = workerScope;
        scopedLane = workerScope == null ? null : new AgentCommandReissueServiceImpl(
                dao, gate, dispatcher, manifest, sentAckTimeoutMillis,
                admissionGuard, workerScope, transactionManager);
    }

    AgentCommandDualLaneReissueService(
            AgentCommandRecoveryDao dao,
            AgentCommandReissueService legacyLane,
            AgentCommandReissueService scopedLane,
            WorkerScope scopedWorkerScope) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.legacyLane = Objects.requireNonNull(legacyLane, "legacyLane");
        this.scopedLane = scopedLane;
        this.scopedWorkerScope = scopedWorkerScope;
        if ((scopedLane == null) != (scopedWorkerScope == null)) {
            throw new IllegalArgumentException("scoped lane authority is incomplete");
        }
    }

    public AgentCommandManualReissueResult reissueManually(
            AgentCommandOperationRequest request, long now) {
        if (request == null) throw new IllegalArgumentException("manual reissue request is invalid");
        AgentCommandDeliveryEntity hint = dao.findDeliveryProtocolHint(
                request.tenantId(), request.clientId(), request.deliveryId());
        if (hint == null) throw conflict("MANUAL_REISSUE_NOT_FOUND");
        ProducerScope producerScope;
        try {
            producerScope = AgentCommandProtocolSupport.producerScopeOrLegacy(hint);
        } catch (IllegalArgumentException invalid) {
            throw conflict("MANUAL_REISSUE_PROTOCOL_LANE_REJECTED");
        }
        if (producerScope == null) {
            if (!"0".equals(request.tenantId())) {
                throw conflict("MANUAL_REISSUE_PROTOCOL_LANE_REJECTED");
            }
            return legacyLane.reissueManually(request, now);
        }
        if (scopedLane == null || !matchesWorkerScope(producerScope)) {
            throw conflict("MANUAL_REISSUE_PROTOCOL_LANE_REJECTED");
        }
        return scopedLane.reissueManually(request, now);
    }

    @Override
    public AgentCommandReissueScanResult reissueForReconnect(
            AgentCommandReconnectScope scope, int limit, long now) {
        if (scope == null) throw new IllegalArgumentException("reconnect scope is invalid");
        if ("0".equals(scope.tenantId())) {
            return legacyLane.reissueForReconnect(scope, limit, now);
        }
        if (scopedLane != null
                && scopedWorkerScope.tenantId().value().equals(scope.tenantId())) {
            return scopedLane.reissueForReconnect(scope, limit, now);
        }
        return new AgentCommandReissueScanResult(0, 0, 0);
    }

    @Override
    public AgentCommandReissueScanResult reissueDue(
            int limit, long afterDeliveryId, long now) {
        throw new UnsupportedOperationException(
                "Dual-lane due recovery requires the lane-aware coordinator");
    }

    public AgentCommandReissueService legacyLane() {
        return legacyLane;
    }

    public AgentCommandReissueService scopedLane() {
        return scopedLane;
    }

    private boolean matchesWorkerScope(ProducerScope source) {
        return scopedWorkerScope.tenantId().equals(source.tenantId())
                && scopedWorkerScope.dataDomainId().equals(source.dataDomainId())
                && scopedWorkerScope.executionPoolId().equals(source.executionPoolId())
                && scopedWorkerScope.deploymentId().equals(source.deploymentId())
                && scopedWorkerScope.admissionEpoch() == source.admissionEpoch()
                && scopedWorkerScope.moduleId().equals(source.moduleId())
                && scopedWorkerScope.migrationEpoch() == source.migrationEpoch();
    }

    private IllegalStateException conflict(String reason) {
        return new IllegalStateException(reason);
    }
}
