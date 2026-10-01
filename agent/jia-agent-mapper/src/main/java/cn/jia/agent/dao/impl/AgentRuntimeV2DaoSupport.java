package cn.jia.agent.dao.impl;

import cn.jia.agent.entity.AgentRuntimeV2AuthorityScope;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.core.entity.BaseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

final class AgentRuntimeV2DaoSupport {
    private AgentRuntimeV2DaoSupport() {}

    static String id() { return UUID.randomUUID().toString(); }

    static void applyScope(BaseEntity entity, AgentRuntimeV2AuthorityScope scope) {
        entity.setTenantId(scope.tenantId().value());
        entity.setClientId(scope.clientId());
    }

    static void applyScope(BaseEntity entity, RuntimeReceiverScope scope) {
        entity.setTenantId(scope.tenantId().value());
        entity.setClientId(scope.clientId());
    }

    static void applyScope(BaseEntity entity, CommandProducerScope scope) {
        entity.setTenantId(scope.tenantId().value());
        entity.setClientId(scope.commandCallerClientId());
    }

    static void exact(String value, int maximum, String name) {
        if (value == null || value.isEmpty()
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not exact");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException(name + " has an incomplete Unicode character");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " has an incomplete Unicode character");
            }
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException(name + " exceeds its UTF-8 byte limit");
        }
    }

    static void uuid(String value, String name) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException(name + " is not a canonical server UUID");
        }
    }

    static void scopes(CommandProducerScope producer, RuntimeReceiverScope receiver) {
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(receiver, "receiver");
        if (!producer.tenantId().equals(receiver.tenantId())
                || !producer.dataDomainId().equals(receiver.dataDomainId())
                || !producer.executionPoolId().equals(receiver.executionPoolId())
                || !producer.ownerJiacn().equals(receiver.ownerJiacn())
                || !producer.targetAgentId().equals(receiver.agentId())
                || producer.admissionEpoch() != receiver.admissionEpoch()
                || !producer.moduleId().equals(receiver.moduleId())
                || producer.migrationEpoch() != receiver.migrationEpoch()) {
            throw new IllegalArgumentException("producer and receiver snapshots do not describe one command lane");
        }
    }

    static void requireEnforcedProvenance(String provenance) {
        if (!"R2B1_ENFORCED".equals(provenance)) {
            throw new IllegalStateException("legacy runtime-v2 evidence cannot grant current authority");
        }
    }

    static void requireUnsetProvenance(String provenance) {
        if (provenance != null) {
            throw new IllegalArgumentException("evidence provenance is database-owned");
        }
    }
}
