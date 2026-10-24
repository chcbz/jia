package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.ProducerScope;
import cn.jia.core.tenant.TenantId;

import java.util.Objects;

/** Protocol selection and persisted producer-provenance validation. */
public final class AgentCommandProtocolSupport {
    public static final String MODULE_ID = "agent-command";

    private AgentCommandProtocolSupport() {
    }

    public static boolean isV2(AgentCommandDeliveryEntity delivery) {
        return delivery != null && Integer.valueOf(AgentCommandAmqpContract.WIRE_VERSION)
                .equals(delivery.getProtocolVersion());
    }

    public static ProducerScope producerScope(AgentCommandDeliveryEntity delivery) {
        if (!isV2(delivery)) throw invalid("delivery is not protocol v2");
        return scope(delivery.getTenantId(), delivery.getDataDomainId(),
                delivery.getExecutionPoolId(), delivery.getProducerDeploymentId(),
                delivery.getAdmissionEpoch(), delivery.getModuleId(), delivery.getMigrationEpoch());
    }

    /** Returns persisted v2 provenance, or {@code null} for a valid legacy row. */
    public static ProducerScope producerScopeOrLegacy(AgentCommandDeliveryEntity delivery) {
        if (isV2(delivery)) return producerScope(delivery);
        requireLegacy(delivery, null);
        return null;
    }

    public static ProducerScope producerScope(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        if (delivery == null || outbox == null
                || !Objects.equals(delivery.getProtocolVersion(), outbox.getProtocolVersion())) {
            throw invalid("delivery/outbox protocol is mixed");
        }
        if (isV2(delivery)) {
            if (!Objects.equals(delivery.getTenantId(), outbox.getTenantId())
                    || !Objects.equals(delivery.getClientId(), outbox.getClientId())) {
                throw invalid("delivery/outbox business scope differs");
            }
            ProducerScope source = producerScope(delivery);
            ProducerScope outboxScope = scope(outbox.getTenantId(), outbox.getDataDomainId(),
                    outbox.getExecutionPoolId(), outbox.getProducerDeploymentId(),
                    outbox.getAdmissionEpoch(), outbox.getModuleId(), outbox.getMigrationEpoch());
            if (!source.equals(outboxScope)) throw invalid("delivery/outbox provenance differs");
            return source;
        }
        requireLegacy(delivery, outbox);
        return null;
    }

    public static byte[] wireBytes(AgentCommandDraft draft, String messageId, int attempt,
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        ProducerScope source = producerScope(delivery, outbox);
        return source == null
                ? AgentCommandCanonicalCodec.wireBytes(draft, messageId, attempt)
                : AgentCommandCanonicalCodec.wireBytesV2(
                        draft, messageId, delivery.getId(), attempt, source);
    }

    public static byte[] wireBytes(AgentCommandDraft draft, String messageId, int attempt,
            AgentCommandDeliveryEntity delivery) {
        if (isV2(delivery)) {
            return AgentCommandCanonicalCodec.wireBytesV2(
                    draft, messageId, delivery.getId(), attempt, producerScope(delivery));
        }
        requireLegacy(delivery, null);
        return AgentCommandCanonicalCodec.wireBytes(draft, messageId, attempt);
    }

    public static void apply(AgentCommandDeliveryEntity delivery, ProducerScope source) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(source, "source");
        delivery.setProtocolVersion(AgentCommandAmqpContract.WIRE_VERSION)
                .setDataDomainId(source.dataDomainId().value())
                .setExecutionPoolId(source.executionPoolId())
                .setAdmissionEpoch(source.admissionEpoch())
                .setProducerDeploymentId(source.deploymentId())
                .setModuleId(source.moduleId())
                .setMigrationEpoch(source.migrationEpoch());
    }

    public static void apply(AgentOutboxEventEntity outbox, ProducerScope source) {
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(source, "source");
        outbox.setProtocolVersion(AgentCommandAmqpContract.WIRE_VERSION)
                .setDataDomainId(source.dataDomainId().value())
                .setExecutionPoolId(source.executionPoolId())
                .setAdmissionEpoch(source.admissionEpoch())
                .setProducerDeploymentId(source.deploymentId())
                .setModuleId(source.moduleId())
                .setMigrationEpoch(source.migrationEpoch());
    }

    private static ProducerScope scope(String tenantId, String dataDomainId,
            String executionPoolId, String deploymentId, Long admissionEpoch,
            String moduleId, Long migrationEpoch) {
        if (admissionEpoch == null || migrationEpoch == null || !MODULE_ID.equals(moduleId)) {
            throw invalid("protocol v2 provenance is incomplete");
        }
        try {
            return new ProducerScope(TenantId.of(tenantId), DataDomainId.of(dataDomainId),
                    executionPoolId, deploymentId, admissionEpoch, moduleId, migrationEpoch);
        } catch (RuntimeException malformed) {
            throw invalid("protocol v2 provenance is malformed");
        }
    }

    private static void requireLegacy(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        if (delivery == null || !(delivery.getProtocolVersion() == null
                || Integer.valueOf(1).equals(delivery.getProtocolVersion()))
                || !"0".equals(delivery.getTenantId())
                || hasProvenance(delivery.getDataDomainId(), delivery.getExecutionPoolId(),
                    delivery.getAdmissionEpoch(), delivery.getProducerDeploymentId(),
                    delivery.getModuleId(), delivery.getMigrationEpoch())) {
            throw invalid("legacy delivery provenance is invalid");
        }
        if (outbox != null && (!Objects.equals(delivery.getProtocolVersion(), outbox.getProtocolVersion())
                || !Objects.equals(delivery.getTenantId(), outbox.getTenantId())
                || !Objects.equals(delivery.getClientId(), outbox.getClientId())
                || hasProvenance(outbox.getDataDomainId(), outbox.getExecutionPoolId(),
                    outbox.getAdmissionEpoch(), outbox.getProducerDeploymentId(),
                    outbox.getModuleId(), outbox.getMigrationEpoch()))) {
            throw invalid("legacy delivery/outbox provenance is mixed");
        }
    }

    private static boolean hasProvenance(String domain, String pool, Long admission,
            String deployment, String module, Long migration) {
        return domain != null || pool != null || admission != null || deployment != null
                || module != null || migration != null;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid Agent command protocol: " + reason);
    }
}
