package cn.jia.agent.entity;

import cn.jia.core.tenant.TenantId;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Domain-separated LP fingerprints and keyed digests for runtime-v2 registration. */
public final class AgentRuntimeV2RegistrationFingerprints {
    private static final HexFormat HEX = HexFormat.of();
    private static final String UUID4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private AgentRuntimeV2RegistrationFingerprints() {}

    public record Subject(String credentialKind, String credentialReference, String tenantId,
                          String runtimeCredentialClientId, String ownerJiacn, String agentId) {
        public Subject {
            if (!"API_KEY".equals(credentialKind)) throw new IllegalArgumentException("network registration subject must be API_KEY");
            exact(credentialReference, 200, "credentialReference"); tenant(tenantId);
            exact(runtimeCredentialClientId, 50, "runtimeCredentialClientId");
            exact(ownerJiacn, 100, "ownerJiacn"); exact(agentId, 100, "agentId");
        }
    }

    public record HmacKeyRef(String keyId, SecretKey key) {
        public HmacKeyRef { exact(keyId, 100, "keyId"); Objects.requireNonNull(key, "key"); }
    }

    public static byte[] subject(Subject value) {
        Objects.requireNonNull(value, "value");
        return sha256(lp(List.of("CYF-RUNTIME-V2-REGISTRATION-SUBJECT-V1", value.credentialKind(),
                value.credentialReference(), value.tenantId(), value.runtimeCredentialClientId(),
                value.ownerJiacn(), value.agentId())));
    }

    public static byte[] authority(AgentRuntimeV2RegistrationAuthority value) {
        Objects.requireNonNull(value, "value");
        List<String> fields = new ArrayList<>(51);
        fields.add("CYF-RUNTIME-V2-REGISTRATION-AUTHORITY-V1"); fields.addAll(value.fields());
        requireHash(fields.get(1), "registrationSubjectHash");
        return sha256(lp(fields));
    }

    public static byte[] createSemantic(byte[] subject, byte[] authority, String runtimeInstanceId,
                                        byte[] processPublicKeySha256) {
        uuid(runtimeInstanceId, "runtimeInstanceId");
        return sha256(lp(List.of("CYF-RUNTIME-V2-CREATE-SEMANTIC-V1", hex(copy32(subject,"subject")),
                hex(copy32(authority,"authority")), runtimeInstanceId,
                hex(copy32(processPublicKeySha256,"processPublicKeySha256")))));
    }

    public static byte[] challengeSemantic(byte[] subject, byte[] authority, String bindingId,
            String priorReceiptId, byte[] priorReceiptSha256, String instanceId,
            String provisionalSessionFence, String socketOwnerInstanceId, long socketOwnerEpoch) {
        uuid(bindingId,"runtimeBindingId"); uuid(priorReceiptId,"priorRegistrationReceiptId");
        uuid(instanceId,"runtimeInstanceId"); uuid(provisionalSessionFence,"provisionalSessionFence");
        uuid(socketOwnerInstanceId,"socketOwnerInstanceId"); nonnegative(socketOwnerEpoch,"socketOwnerEpoch");
        return sha256(lp(List.of("CYF-RUNTIME-V2-CHALLENGE-SEMANTIC-V1", hex(copy32(subject,"subject")),
                hex(copy32(authority,"authority")), bindingId, priorReceiptId,
                hex(copy32(priorReceiptSha256,"priorReceiptSha256")), instanceId,
                provisionalSessionFence, socketOwnerInstanceId, Long.toString(socketOwnerEpoch))));
    }

    public static byte[] reconnectSemantic(byte[] subject, byte[] authority, String challengeId,
            String bindingId, String priorReceiptId, byte[] priorReceiptSha256, String instanceId,
            byte[] verifier, byte[] challenge) {
        uuid(challengeId,"challengeId"); uuid(bindingId,"runtimeBindingId");
        uuid(priorReceiptId,"priorRegistrationReceiptId"); uuid(instanceId,"runtimeInstanceId");
        return sha256(lp(List.of("CYF-RUNTIME-V2-RECONNECT-SEMANTIC-V1", hex(copy32(subject,"subject")),
                hex(copy32(authority,"authority")), challengeId, bindingId, priorReceiptId,
                hex(copy32(priorReceiptSha256,"priorReceiptSha256")), instanceId,
                hex(sha256(copy32(verifier,"verifier"))), hex(sha256(copy32(challenge,"challenge"))))));
    }

    public static byte[] apiKeyCredential(HmacKeyRef key, String apiKeyId, String apiKeySecret) {
        exact(apiKeyId,200,"apiKeyId"); exact(apiKeySecret,1024,"apiKeySecret");
        return hmac(key, concat("CYF-RUNTIME-V2-API-KEY-CREDENTIAL\0".getBytes(StandardCharsets.US_ASCII),
                lp(List.of(apiKeyId, apiKeySecret))));
    }

    public static byte[] reconnectVerifier(HmacKeyRef key, String bindingId, long version, byte[] verifier) {
        uuid(bindingId,"runtimeBindingId"); positive(version,"verifierVersion");
        return hmac(key, concat("CYF-RUNTIME-V2-RECONNECT-VERIFIER\0".getBytes(StandardCharsets.US_ASCII),
                uuidBytes(bindingId), uint64(version), copy32(verifier,"verifier")));
    }

    public static byte[] reconnectChallenge(HmacKeyRef key, String challengeId, String bindingId,
            long generation, String priorReceiptId, String instanceId, byte[] plaintext) {
        uuid(challengeId,"challengeId"); uuid(bindingId,"runtimeBindingId");
        nonnegative(generation,"bindingGeneration"); uuid(priorReceiptId,"priorReceiptId");
        uuid(instanceId,"runtimeInstanceId");
        return hmac(key, concat("CYF-RUNTIME-V2-RECONNECT-CHALLENGE\0".getBytes(StandardCharsets.US_ASCII),
                uuidBytes(challengeId), uuidBytes(bindingId), uint64(generation), uuidBytes(priorReceiptId),
                uuidBytes(instanceId), copy32(plaintext,"challenge")));
    }

    public static byte[] executionOrigin(ExecutionOriginSnapshot value) {
        List<String> fields = new ArrayList<>(); fields.add("CYF-RUNTIME-V2-EXECUTION-ORIGIN-SNAPSHOT-V1");
        fields.addAll(value.fingerprintFields()); return sha256(lp(fields));
    }

    public static byte[] authenticatedHolder(AuthenticatedHolderGenerationSnapshot value) {
        List<String> fields = new ArrayList<>(); fields.add("CYF-RUNTIME-V2-CONNECTED-HOLDER-SNAPSHOT-V1");
        fields.addAll(value.fingerprintFields()); return sha256(lp(fields));
    }

    static void validateExecutionOriginValues(String runtimeDispatchId, long inboxId, long deliveryId,
            String tenantId, String dataDomainId, String executionPoolId, String commandCallerClientId,
            String runtimeCredentialClientId, String ownerJiacn, String agentId,
            String producerDeploymentId, String claimantDeploymentId, long claimantWorkerGeneration,
            long receiverRegistrationRevision, long admissionEpoch, String moduleId,
            long migrationEpoch, String runtimeBindingId, long dispatchBindingGeneration,
            String runtimeInstanceId, String dispatchGenerationFactId) {
        uuid(runtimeDispatchId,"runtimeDispatchId"); positive(inboxId,"inboxId"); positive(deliveryId,"deliveryId");
        tenant(tenantId); exact(dataDomainId,64,"dataDomainId"); exact(executionPoolId,100,"executionPoolId");
        exact(commandCallerClientId,50,"commandCallerClientId"); exact(runtimeCredentialClientId,50,"runtimeCredentialClientId");
        exact(ownerJiacn,100,"ownerJiacn"); exact(agentId,100,"agentId"); exact(producerDeploymentId,100,"producerDeploymentId");
        exact(claimantDeploymentId,100,"claimantDeploymentId"); nonnegative(claimantWorkerGeneration,"claimantWorkerGeneration");
        nonnegative(receiverRegistrationRevision,"receiverRegistrationRevision"); nonnegative(admissionEpoch,"admissionEpoch");
        exact(moduleId,64,"moduleId"); nonnegative(migrationEpoch,"migrationEpoch"); uuid(runtimeBindingId,"runtimeBindingId");
        nonnegative(dispatchBindingGeneration,"dispatchBindingGeneration"); uuid(runtimeInstanceId,"runtimeInstanceId");
        uuid(dispatchGenerationFactId,"dispatchGenerationFactId");
    }

    static void validateHolderValues(String bindingGenerationFactId, long factSequence,
            String previousFactId, String runtimeBindingId, long bindingGeneration,
            String runtimeInstanceId, String registrationAuthorityId,
            long registrationAuthorityRevision, String tenantId, String dataDomainId,
            String executionPoolId, String runtimeCredentialClientId, String ownerJiacn,
            String agentId, String claimantDeploymentId, long claimantWorkerGeneration,
            long receiverRegistrationRevision, long admissionEpoch, String moduleId,
            long migrationEpoch, long verifierVersion, String registrationReceiptId,
            String connectionAdmissionKind, String drainControlOperationId, String sessionFence,
            String socketOwnerInstanceId, long socketOwnerEpoch, long socketOwnerLeaseUntil,
            long effectiveAt) {
        uuid(bindingGenerationFactId,"bindingGenerationFactId"); nonnegative(factSequence,"factSequence");
        if ((factSequence==0) != (previousFactId==null)) throw new IllegalArgumentException("previousFactId shape is invalid");
        if (previousFactId!=null) uuid(previousFactId,"previousFactId"); uuid(runtimeBindingId,"runtimeBindingId");
        nonnegative(bindingGeneration,"bindingGeneration"); uuid(runtimeInstanceId,"runtimeInstanceId");
        uuid(registrationAuthorityId,"registrationAuthorityId"); nonnegative(registrationAuthorityRevision,"registrationAuthorityRevision");
        tenant(tenantId); exact(dataDomainId,64,"dataDomainId"); exact(executionPoolId,100,"executionPoolId");
        exact(runtimeCredentialClientId,50,"runtimeCredentialClientId"); exact(ownerJiacn,100,"ownerJiacn"); exact(agentId,100,"agentId");
        exact(claimantDeploymentId,100,"claimantDeploymentId"); nonnegative(claimantWorkerGeneration,"claimantWorkerGeneration");
        nonnegative(receiverRegistrationRevision,"receiverRegistrationRevision"); nonnegative(admissionEpoch,"admissionEpoch");
        exact(moduleId,64,"moduleId"); nonnegative(migrationEpoch,"migrationEpoch"); positive(verifierVersion,"verifierVersion");
        uuid(registrationReceiptId,"registrationReceiptId");
        if (!List.of("CURRENT_ADMISSION","FROZEN_DRAIN").contains(connectionAdmissionKind)) throw new IllegalArgumentException("connectionAdmissionKind is invalid");
        if (("FROZEN_DRAIN".equals(connectionAdmissionKind)) != (drainControlOperationId!=null)) throw new IllegalArgumentException("drain operation shape is invalid");
        if (drainControlOperationId!=null) exact(drainControlOperationId,100,"drainControlOperationId");
        uuid(sessionFence,"sessionFence"); uuid(socketOwnerInstanceId,"socketOwnerInstanceId");
        nonnegative(socketOwnerEpoch,"socketOwnerEpoch"); positive(socketOwnerLeaseUntil,"socketOwnerLeaseUntil"); positive(effectiveAt,"effectiveAt");
        if (socketOwnerLeaseUntil <= effectiveAt) throw new IllegalArgumentException("holder route is not fresh at connection time");
    }

    public static boolean constantTimeEquals(byte[] left, byte[] right) {
        return left != null && right != null && MessageDigest.isEqual(left, right);
    }
    public static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(value)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    public static String hex(byte[] value) { return HEX.formatHex(value); }
    public static byte[] copy32(byte[] value,String name) { if(value==null||value.length!=32) throw new IllegalArgumentException(name+" must be 32 bytes"); return value.clone(); }
    public static byte[] lp(List<String> fields) { try { ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(b); for(String field:fields){ if(field==null){out.writeInt(-1);}else{byte[] e=field.getBytes(StandardCharsets.UTF_8);out.writeInt(e.length);out.write(e);} } return b.toByteArray(); } catch(IOException e){throw new IllegalStateException(e);} }
    private static byte[] hmac(HmacKeyRef key,byte[] value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(key.key());return mac.doFinal(value);}catch(Exception e){throw new IllegalStateException("HMAC unavailable",e);}}
    private static byte[] concat(byte[]... values){int n=0;for(byte[] v:values)n=Math.addExact(n,v.length);byte[] out=new byte[n];int p=0;for(byte[] v:values){System.arraycopy(v,0,out,p,v.length);p+=v.length;}return out;}
    private static byte[] uuidBytes(String value){UUID u=UUID.fromString(value);return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();}
    private static byte[] uint64(long value){return ByteBuffer.allocate(8).putLong(value).array();}
    private static void requireHash(String value,String name){if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException(name+" is not lowercase SHA-256");}
    static void uuid(String value,String name){if(value==null||!value.matches(UUID4))throw new IllegalArgumentException(name+" is not UUIDv4");}
    static void exact(String value,int max,String name){RuntimeV2ExactIdentity.require(value,max,name);}
    static void tenant(String value){TenantId.of(value);}
    static void positive(long value,String name){if(value<=0)throw new IllegalArgumentException(name+" must be positive");}
    static void nonnegative(long value,String name){if(value<0)throw new IllegalArgumentException(name+" must be non-negative");}
}
