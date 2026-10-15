package cn.jia.agent.api;

import cn.jia.agent.entity.*;

/** Explicit public DTO projections. Persistent identities and immutable command facts stay unchanged. */
public final class AgentRuntimeWire {
    private AgentRuntimeWire() { }
    public static long version(String value, boolean positive) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid version");
        long parsed = Long.parseLong(value);
        if (positive && parsed == 0) throw new IllegalArgumentException("Invalid version");
        return parsed;
    }
    public record InstallationRequest(String runtimeInstallationId, String canonicalAgentId, String manifestVersion, String manifestSha256, String enrollmentSecretSha256, long enrollmentExpiresAt) {
        public AgentRuntimeV1InstallationRequest internal() { return new AgentRuntimeV1InstallationRequest(runtimeInstallationId, canonicalAgentId, manifestVersion, manifestSha256, enrollmentSecretSha256, enrollmentExpiresAt); }
    }
    public record EnrollmentRequest(String runtimeInstallationId, String tenantId, String clientId, String canonicalAgentId, String manifestVersion, String manifestSha256, String enrollmentSecret) {
        public AgentRuntimeV1EnrollmentRequest internal() { return new AgentRuntimeV1EnrollmentRequest(runtimeInstallationId, tenantId, clientId, canonicalAgentId, manifestVersion, manifestSha256, enrollmentSecret); }
    }
    public record RuntimeRequest(String runtimeInstallationId, String tenantId, String clientId, String canonicalAgentId, String manifestVersion, String manifestSha256, String health) {
        public AgentRuntimeV1RuntimeRequest internal() { return new AgentRuntimeV1RuntimeRequest(runtimeInstallationId, tenantId, clientId, canonicalAgentId, manifestVersion, manifestSha256, health); }
    }
    public record SessionRequest(String runtimeInstallationId, String tenantId, String clientId, String canonicalAgentId, String manifestVersion, String manifestSha256, String hostId, String runtimeInstanceId) {
        public AgentRuntimeV1SessionRequest internal() { return new AgentRuntimeV1SessionRequest(runtimeInstallationId, tenantId, clientId, canonicalAgentId, manifestVersion, manifestSha256, hostId, runtimeInstanceId); }
    }
    public record AckRequest(String messageId, String correlationId, String commandId, String taskId, String workItemId, String tenantId, String clientId, String canonicalAgentId, String payloadReference, Long expiresAt, String status, String runtimeInstallationId, String hostId, String runtimeInstanceId, String sessionGeneration, String deliveryVersion) {
        public AgentRuntimeV1AckRequest internal() { return new AgentRuntimeV1AckRequest(messageId, correlationId, commandId, taskId, workItemId, tenantId, clientId, canonicalAgentId, payloadReference, expiry(expiresAt), status, runtimeInstallationId, hostId, runtimeInstanceId, version(sessionGeneration, true), deliveryVersion == null ? null : version(deliveryVersion, true)); }
    }
    public record InstallationView(String runtimeInstallationId, String tenantId, String clientId, String canonicalAgentId, String manifestVersion, String manifestSha256, long enrollmentExpiresAt, String status, Long lastHeartbeatAt) {
        public static InstallationView from(AgentRuntimeV1InstallationView value) { return new InstallationView(value.installationId(), value.tenantId(), value.clientId(), value.canonicalAgentId(), value.manifestVersion(), value.manifestSha256(), value.enrollmentExpiresAt(), value.status(), value.lastHeartbeatAt()); }
    }
    public record SessionResponse(String runtimeInstallationId, String tenantId, String clientId, String canonicalAgentId, String hostId, String runtimeInstanceId, String sessionGeneration, String scheme, String sessionToken, String websocketPath, String status) {
        public static SessionResponse from(AgentRuntimeV1SessionResponse value) { return new SessionResponse(value.installationId(), value.tenantId(), value.clientId(), value.canonicalAgentId(), value.hostId(), value.runtimeInstanceId(), Long.toString(value.sessionGeneration()), value.scheme(), value.sessionToken(), value.websocketPath(), value.status()); }
        @Override public String toString() { return "SessionResponse[credentials=REDACTED]"; }
    }
    public record EnrollmentResult(InstallationView installation, String runtimeAuthorization) {
        public static EnrollmentResult from(AgentRuntimeV1EnrollmentResult value) { return new EnrollmentResult(InstallationView.from(value.installation()), value.runtimeAuthorization()); }
        @Override public String toString() { return "EnrollmentResult[credentials=REDACTED]"; }
    }
    public record AckResult(AgentCommandAckResult.Kind kind, String status, String deliveryVersion) {
        public static AckResult from(AgentCommandAckResult value) { return new AckResult(value.kind(), value.status(), Long.toString(value.deliveryVersion())); }
    }
    private static String expiry(Long value) {
        if (value == null || value < -9007199254740991L || value > 9007199254740991L) throw new IllegalArgumentException("Invalid expiry");
        return java.time.Instant.ofEpochMilli(value).toString();
    }
}
