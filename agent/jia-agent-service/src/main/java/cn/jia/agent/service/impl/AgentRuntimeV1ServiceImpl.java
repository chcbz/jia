package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentRuntimeV1AckRequest;
import cn.jia.agent.entity.AgentRuntimeV1EnrollmentRequest;
import cn.jia.agent.entity.AgentRuntimeV1EnrollmentResult;
import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.agent.entity.AgentRuntimeV1InstallationRequest;
import cn.jia.agent.entity.AgentRuntimeV1InstallationView;
import cn.jia.agent.entity.AgentRuntimeV1RuntimeRequest;
import cn.jia.agent.entity.AgentRuntimeV1SessionRequest;
import cn.jia.agent.entity.AgentRuntimeV1SessionResponse;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;

/** Runtime v1 boundary. It stores enrollment and runtime credentials as SHA-256 digests only. */
@Service
@RequiredArgsConstructor
public class AgentRuntimeV1ServiceImpl implements AgentRuntimeV1Service {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    // The controlled installer generates this opaque id before hashing its manifest.
    // Server-side generation would make the installationId/manifestSha256 binding circular.
    private static final Pattern INSTALLATION_ID = Pattern.compile("rti_[0-9a-f]{32}");
    private static final Set<String> ACK_STATUSES = Set.of(
            "RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentRuntimeV1InstallationDao installations;
    private final AgentIdentityService identityService;
    private final AgentIdentityRegistryDao identityRegistry;
    private final ObjectProvider<AgentCommandAckService> commandAcks;
    private final AgentRuntimeAuthenticationService sessions;
    private final ObjectProvider<cn.jia.agent.skill.SkillInstallAckService> skillAcks;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeV1InstallationView create(String tenantId, String clientId, String ownerJiacn,
            AgentRuntimeV1InstallationRequest request, long now) {
        requireManagementScope(tenantId, clientId, ownerJiacn);
        if (request == null || !installationId(request.installationId()) || !exact(request.canonicalAgentId(), 100)
                || !exact(request.manifestVersion(), 100) || !sha256Text(request.manifestSha256())
                || !sha256Text(request.enrollmentSecretSha256()) || request.enrollmentExpiresAt() <= now) {
            throw forbidden("Runtime v1 installation request is invalid");
        }
        String canonicalAgentId = identityService.requireCanonicalAgentIdInScope(
                tenantId, clientId, ownerJiacn, request.canonicalAgentId());
        AgentRuntimeV1InstallationEntity installation = new AgentRuntimeV1InstallationEntity()
                .setInstallationId(request.installationId())
                .setCanonicalAgentId(canonicalAgentId).setManifestVersion(request.manifestVersion())
                .setManifestSha256(request.manifestSha256()).setEnrollmentSecretHash(hexDigest(request.enrollmentSecretSha256()))
                .setEnrollmentExpiresAt(request.enrollmentExpiresAt()).setStatus("PENDING").setVersion(0L);
        // BaseEntity setters return BaseEntity; assign scope separately so the
        // Runtime-v1-specific fluent chain remains type-safe at compile time.
        installation.setTenantId(tenantId);
        installation.setClientId(clientId);
        if (installations.insert(installation) != 1) throw forbidden("Runtime v1 installation could not be created");
        return view(installation, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeV1InstallationView ensureInstallation(String tenantId, String clientId, String ownerJiacn,
            AgentRuntimeV1InstallationRequest request, long now) {
        requireManagementScope(tenantId, clientId, ownerJiacn);
        if (request == null || !installationId(request.installationId()) || !exact(request.canonicalAgentId(), 100)
                || !"1".equals(request.manifestVersion()) || !sha256Text(request.manifestSha256())
                || !sha256Text(request.enrollmentSecretSha256()) || request.enrollmentExpiresAt() <= 0) {
            throw forbidden("Runtime v1 installation candidate is invalid");
        }
        var candidate = new AgentRuntimeV1InstallationEntity().setInstallationId(request.installationId())
                .setCanonicalAgentId(request.canonicalAgentId()).setManifestVersion(request.manifestVersion())
                .setManifestSha256(request.manifestSha256()).setEnrollmentSecretHash(hexDigest(request.enrollmentSecretSha256()))
                .setEnrollmentExpiresAt(request.enrollmentExpiresAt()).setStatus("PENDING").setVersion(0L);
        candidate.setTenantId(tenantId); candidate.setClientId(clientId);
        requireInstallationOwner(candidate, tenantId, clientId, ownerJiacn);
        // Installation first, then hosting intent -> lease -> binding -> free request. This follows
        // session/registration's installation-before-binding fence and performs no external I/O.
        // Do not FOR UPDATE a missing unique key: concurrent gap-lock holders would
        // both need to upgrade to an insert lock. The unique insert arbitrates first;
        // the subsequent locking read sees the winner even under REPEATABLE READ.
        var hint = installations.findInScope(tenantId, clientId, request.installationId());
        if (hint == null) {
            if (request.enrollmentExpiresAt() <= now) throw forbidden("Runtime v1 installation candidate expired");
            installations.insertCandidateIfAbsent(candidate);
        }
        var existing = installations.lock(request.installationId());
        if (existing == null || !sameScope(existing, tenantId, clientId)
                || !request.installationId().equals(existing.getInstallationId())
                || !request.canonicalAgentId().equals(existing.getCanonicalAgentId())
                || !request.manifestVersion().equals(existing.getManifestVersion())
                || !request.manifestSha256().equals(existing.getManifestSha256())
                || !Objects.equals(request.enrollmentExpiresAt(), existing.getEnrollmentExpiresAt())
                || existing.getEnrollmentSecretHash() == null
                || !MessageDigest.isEqual(candidate.getEnrollmentSecretHash(), existing.getEnrollmentSecretHash())
                || !("PENDING".equals(existing.getStatus()) || "ACTIVE".equals(existing.getStatus()))
                || existing.getId() == null || existing.getVersion() == null) {
            throw forbidden("Runtime v1 installation candidate conflicts");
        }
        // Exact ACTIVE/PENDING replay remains valid after expiry; never extend, reenroll or rotate it.
        return view(existing, null);
    }

    @Override
    public AgentRuntimeV1InstallationView status(String tenantId, String clientId, String ownerJiacn, String installationId) {
        requireManagementScope(tenantId, clientId, ownerJiacn);
        AgentRuntimeV1InstallationEntity installation = installations.findInScope(tenantId, clientId, installationId);
        requireInstallationOwner(installation, tenantId, clientId, ownerJiacn);
        return view(installation, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String tenantId, String clientId, String ownerJiacn, String installationId, long now) {
        requireManagementScope(tenantId, clientId, ownerJiacn);
        AgentRuntimeV1InstallationEntity installation = installations.lock(installationId);
        if (installation == null || !sameScope(installation, tenantId, clientId)
                || installation.getVersion() == null || installation.getId() == null) {
            throw forbidden("Runtime v1 installation is unavailable");
        }
        requireInstallationOwner(installation, tenantId, clientId, ownerJiacn);
        if ("REVOKED".equals(installation.getStatus())) return;
        if (installations.revoke(installation, now) != 1) throw forbidden("Runtime v1 installation changed");
    }

    @Override
    public AgentRuntimeV1EnrollmentResult enroll(AgentRuntimeV1EnrollmentRequest request, long now) {
        if (request == null || !exact(request.installationId(), 100) || !exact(request.tenantId(), 50)
                || !exact(request.clientId(), 50) || !exact(request.canonicalAgentId(), 100)
                || !exact(request.manifestVersion(), 100) || !sha256Text(request.manifestSha256())
                || !exact(request.enrollmentSecret(), 4096)) throw forbidden("Runtime v1 enrollment rejected");
        AgentRuntimeV1InstallationEntity bootstrap = installations.findBootstrap(request.installationId());
        if (!validEnrollment(bootstrap, request, now)) {
            throw forbidden("Runtime v1 enrollment rejected");
        }
        return withPersistentScope(bootstrap, scope -> {
            AgentRuntimeV1InstallationEntity installation = installations.lock(request.installationId());
            if (!validEnrollment(installation, request, now)) throw forbidden("Runtime v1 enrollment rejected");
            requirePersistentOwner(installation, scope);
            String authorization = randomAuthorization();
            if (installations.activate(installation, digest(authorization), now) != 1) {
                throw forbidden("Runtime v1 enrollment rejected");
            }
            installation.setStatus("ACTIVE").setEnrollmentConsumedAt(now).setRuntimeAuthorizationIssuedAt(now)
                    .setVersion(installation.getVersion() + 1);
            return new AgentRuntimeV1EnrollmentResult(view(installation, null), authorization);
        });
    }

    @Override
    public AgentRuntimeV1SessionResponse session(String authorization, AgentRuntimeV1SessionRequest request, long now) {
        AgentRuntimeV1InstallationEntity bootstrap = authenticateBootstrap(authorization);
        return withPersistentScope(bootstrap, scope -> {
            AgentRuntimeV1InstallationEntity installation = installations.lock(bootstrap.getInstallationId());
            if (!validRuntimeCredential(installation, authorization)
                    || request == null || !sameRuntimeIdentity(installation, request.tenantId(), request.clientId(), request.canonicalAgentId())
                    || !Objects.equals(installation.getInstallationId(), request.installationId())
                    || !Objects.equals(installation.getManifestVersion(), request.manifestVersion())
                    || !Objects.equals(installation.getManifestSha256(), request.manifestSha256())) {
                throw forbidden("Runtime session rejected");
            }
            requirePersistentOwner(installation, scope);
            return sessions.issue(installation, request, now);
        });
    }

    @Override
    public AgentRuntimeV1InstallationView heartbeat(String authorization, AgentRuntimeV1RuntimeRequest request, long now) {
        AgentRuntimeV1InstallationEntity bootstrap = authenticateBootstrap(authorization);
        return withPersistentScope(bootstrap, scope -> {
            AgentRuntimeV1InstallationEntity installation = installations.lock(bootstrap.getInstallationId());
            if (!validRuntimeCredential(installation, authorization)) {
                throw forbidden("Runtime v1 authorization rejected");
            }
            requirePersistentOwner(installation, scope);
            AgentRuntimeV1InstallationView state = runtimeView(installation, request);
            if ("REBINDS_REQUIRED".equals(state.status())) return state;
            if (installation.getId() == null || installation.getVersion() == null
                    || installations.heartbeat(installation, now) != 1) throw forbidden("Runtime v1 authorization rejected");
            installation.setLastHeartbeatAt(now).setVersion(installation.getVersion() + 1);
            return view(installation, null);
        });
    }

    @Override
    public AgentCommandAckResult acknowledge(String authorization, String pathMessageId,
            AgentRuntimeV1AckRequest request, long now) {
        if (request == null || !exact(pathMessageId, 100) || !pathMessageId.equals(request.messageId())
                || !exact(request.correlationId(), 100) || !exact(request.commandId(), 100)
                || !exact(request.taskId(), 100)
                || (request.workItemId() != null && !exact(request.workItemId(), 100))
                || !ACK_STATUSES.contains(request.status())
                || !exact(request.installationId(), 100) || !exact(request.hostId(), 100)
                || !exact(request.runtimeInstanceId(), 100) || request.sessionGeneration() <= 0
                || request.deliveryVersion() != null && request.deliveryVersion() <= 0) {
            throw forbidden("Runtime v1 ACK rejected");
        }
        var proof = sessions.verify(new AgentRuntimeAuthenticationFilter.SessionHeaders(request.canonicalAgentId(),
                request.installationId(), request.hostId(), request.runtimeInstanceId(), request.sessionGeneration(), authorization));
        if (!Objects.equals(proof.scope().tenantId(), request.tenantId())
                || !Objects.equals(proof.scope().clientId(), request.clientId())) throw forbidden("Runtime ACK rejected");
        return sessions.withFence(proof, false, () -> {
            String senderMessageId = "rta_" + HexFormat.of().formatHex(digest(
                    proof.installationId() + "|" + pathMessageId + "|" + request.correlationId()
                            + "|" + request.status())).substring(0, 32);
            AgentCommandAck ack = new AgentCommandAck(
                    proof.scope().tenantId(), proof.scope().clientId(), proof.scope().agentId(),
                    senderMessageId, pathMessageId, request.commandId(), request.taskId(), request.workItemId(),
                    request.status(), now);
            var skillAckService = skillAcks.getIfAvailable();
            AgentCommandAckResult result = skillAckService == null ? null
                    : skillAckService.acknowledgeIfSkill(proof, ack, now);
            if (result == null) {
                AgentCommandAckService ackService = commandAcks.getIfAvailable();
                if (ackService == null) throw forbidden("Runtime v1 command ACK is unavailable");
                result = ackService.acknowledge(ack, now);
            }
            if (result == null || result.kind() == null || !request.status().equals(result.status())
                    || result.deliveryVersion() <= 0
                    || request.deliveryVersion() != null && (result.deliveryVersion() < request.deliveryVersion()
                        || result.kind() == AgentCommandAckResult.Kind.ADVANCED && result.deliveryVersion() <= request.deliveryVersion())) {
                throw forbidden("Runtime command commit receipt rejected");
            }
            return result;
        });
    }

    private AgentRuntimeV1InstallationEntity authenticateBootstrap(String authorization) {
        if (!exact(authorization, 4096)) throw forbidden("Runtime v1 authorization rejected");
        AgentRuntimeV1InstallationEntity installation =
                installations.findBootstrapActiveByAuthorizationHash(digest(authorization));
        if (!validRuntimeCredential(installation, authorization)) {
            throw forbidden("Runtime v1 authorization rejected");
        }
        return installation;
    }

    private <T> T withPersistentScope(AgentRuntimeV1InstallationEntity installation,
            java.util.function.Function<BusinessScope, T> operation) {
        try {
            return sessions.withPersistentInstallationScope(installation, operation);
        } catch (AgentBizException rejected) {
            throw rejected;
        } catch (IllegalArgumentException rejected) {
            throw forbidden("Runtime v1 authorization rejected");
        }
    }

    private void requirePersistentOwner(AgentRuntimeV1InstallationEntity installation,
            BusinessScope scope) {
        if (scope == null || installation == null
                || !Objects.equals(installation.getTenantId(), scope.tenantId().value())
                || !Objects.equals(installation.getClientId(), scope.callerClientId())) {
            throw forbidden("Runtime v1 authorization rejected");
        }
        var identity = identityRegistry.findExactByCanonicalInScope(scope.tenantId().value(),
                scope.callerClientId(), scope.ownerJiacn(), installation.getCanonicalAgentId());
        if (identity == null || !Objects.equals(identity.getTenantId(), scope.tenantId().value())
                || !Objects.equals(identity.getClientId(), scope.callerClientId())
                || !Objects.equals(identity.getOwnerJiacn(), scope.ownerJiacn())
                || !Objects.equals(identity.getCanonicalAgentId(), installation.getCanonicalAgentId())) {
            throw forbidden("Runtime v1 authorization rejected");
        }
    }

    private static boolean validEnrollment(AgentRuntimeV1InstallationEntity installation,
            AgentRuntimeV1EnrollmentRequest request, long now) {
        return installation != null && request != null
                && sameScope(installation, request.tenantId(), request.clientId())
                && Objects.equals(request.installationId(), installation.getInstallationId())
                && Objects.equals(request.canonicalAgentId(), installation.getCanonicalAgentId())
                && Objects.equals(request.manifestVersion(), installation.getManifestVersion())
                && Objects.equals(request.manifestSha256(), installation.getManifestSha256())
                && "PENDING".equals(installation.getStatus())
                && installation.getEnrollmentConsumedAt() == null
                && installation.getEnrollmentExpiresAt() != null
                && installation.getEnrollmentExpiresAt() > now
                && installation.getEnrollmentSecretHash() != null
                && MessageDigest.isEqual(digest(request.enrollmentSecret()), installation.getEnrollmentSecretHash())
                && installation.getId() != null && installation.getVersion() != null;
    }

    private static boolean validRuntimeCredential(AgentRuntimeV1InstallationEntity installation,
            String authorization) {
        return installation != null && "ACTIVE".equals(installation.getStatus())
                && installation.getRuntimeAuthorizationHash() != null
                && MessageDigest.isEqual(digest(authorization), installation.getRuntimeAuthorizationHash());
    }

    private AgentRuntimeV1InstallationView runtimeView(
            AgentRuntimeV1InstallationEntity installation, AgentRuntimeV1RuntimeRequest request) {
        if (request == null || !sameRuntimeIdentity(installation, request.tenantId(), request.clientId(),
                request.canonicalAgentId()) || !Objects.equals(request.installationId(), installation.getInstallationId())
                || !Objects.equals(request.manifestVersion(), installation.getManifestVersion())
                || !Objects.equals(request.manifestSha256(), installation.getManifestSha256())) {
            return view(installation, "REBINDS_REQUIRED");
        }
        return view(installation, null);
    }

    private static boolean sameRuntimeIdentity(AgentRuntimeV1InstallationEntity installation,
            String tenantId, String clientId, String canonicalAgentId) {
        return installation != null && Objects.equals(installation.getTenantId(), tenantId)
                && Objects.equals(installation.getClientId(), clientId)
                && Objects.equals(installation.getCanonicalAgentId(), canonicalAgentId);
    }
    /** Management remains owner-bound even though every user now shares tenant 0.
     * Lifecycle is deliberately not an execution gate: an owner can inspect/revoke
     * a pending, suspended or retired installation without activating that Agent.
     */
    private void requireInstallationOwner(AgentRuntimeV1InstallationEntity installation,
            String tenantId, String clientId, String ownerJiacn) {
        try {
            new BusinessScope(TenantId.of(tenantId), DataDomainId.of("cyf"), ownerJiacn, clientId);
        } catch (RuntimeException invalidScope) {
            throw forbidden("Runtime v1 installation is unavailable");
        }
        if (!sameScope(installation, tenantId, clientId)
                || !exact(installation.getCanonicalAgentId(), 100)) {
            throw forbidden("Runtime v1 installation is unavailable");
        }
        var identity = identityRegistry.findExactByCanonicalInScope(
                tenantId, clientId, ownerJiacn, installation.getCanonicalAgentId());
        if (identity == null || !tenantId.equals(identity.getTenantId())
                || !clientId.equals(identity.getClientId()) || !ownerJiacn.equals(identity.getOwnerJiacn())
                || !installation.getCanonicalAgentId().equals(identity.getCanonicalAgentId())) {
            throw forbidden("Runtime v1 installation is unavailable");
        }
    }

    private static boolean sameScope(AgentRuntimeV1InstallationEntity installation, String tenantId, String clientId) {
        return installation != null && Objects.equals(installation.getTenantId(), tenantId)
                && Objects.equals(installation.getClientId(), clientId);
    }
    private static AgentRuntimeV1InstallationView view(AgentRuntimeV1InstallationEntity e, String status) {
        return new AgentRuntimeV1InstallationView(e.getInstallationId(), e.getTenantId(), e.getClientId(),
                e.getCanonicalAgentId(), e.getManifestVersion(), e.getManifestSha256(),
                e.getEnrollmentExpiresAt() == null ? 0 : e.getEnrollmentExpiresAt(),
                status == null ? e.getStatus() : status, e.getLastHeartbeatAt());
    }
    private static boolean installationId(String input) {
        return input != null && INSTALLATION_ID.matcher(input).matches();
    }
    private static boolean sha256Text(String input) { return input != null && SHA_256.matcher(input).matches(); }
    private static byte[] hexDigest(String input) { return HexFormat.of().parseHex(input); }
    private static boolean exact(String input, int maximum) {
        return input != null && !input.isBlank() && input.length() <= maximum
                && input.codePoints().noneMatch(Character::isISOControl);
    }
    private static void requireScope(String tenantId, String clientId) {
        if (!exact(tenantId, 50) || !exact(clientId, 50)) throw forbidden("Runtime v1 scope is invalid");
    }
    private static void requireManagementScope(String tenantId, String clientId,
            String ownerJiacn) {
        requireScope(tenantId, clientId);
        final BusinessScope trusted;
        try {
            trusted = TrustedTenantContext.requireScope();
        } catch (RuntimeException unavailable) {
            throw forbidden("Runtime v1 scope is invalid");
        }
        if (!tenantId.equals(trusted.tenantId().value())
                || !clientId.equals(trusted.callerClientId())
                || !Objects.equals(ownerJiacn, trusted.ownerJiacn())
                || !"cyf".equals(trusted.dataDomainId().value())) {
            throw forbidden("Runtime v1 scope is invalid");
        }
    }
    private static AgentBizException forbidden(String message) {
        return new AgentBizException("AGENT_FORBIDDEN", message);
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static String randomAuthorization() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return "rta1_" + HexFormat.of().formatHex(bytes);
    }
}
