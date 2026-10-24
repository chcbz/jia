package cn.jia.agent.service.impl;

import cn.jia.core.tenant.WorkerScope;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Compact lease identity cryptographically bound to one deployment generation. */
final class AgentOutboxLeaseOwner {
    private static final String PREFIX = "d03/";

    private AgentOutboxLeaseOwner() {
    }

    static String create(WorkerScope scope, UUID instanceId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(instanceId, "instanceId");
        return expectedPrefix(scope) + instanceId;
    }

    static boolean matches(WorkerScope scope, String leaseOwner) {
        if (scope == null || leaseOwner == null || leaseOwner.length() > 100
                || !leaseOwner.equals(leaseOwner.strip())
                || leaseOwner.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        String prefix = expectedPrefix(scope);
        if (!leaseOwner.startsWith(prefix)) return false;
        try {
            String uuid = leaseOwner.substring(prefix.length());
            return UUID.fromString(uuid).toString().equals(uuid);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String expectedPrefix(WorkerScope scope) {
        byte[] deployment = AgentCommandCanonicalCodec.sha256(
                scope.deploymentId().getBytes(StandardCharsets.UTF_8));
        String deploymentDigest = HexFormat.of().formatHex(deployment, 0, 16);
        return PREFIX + deploymentDigest + "/" + scope.workerGeneration() + "/";
    }
}
