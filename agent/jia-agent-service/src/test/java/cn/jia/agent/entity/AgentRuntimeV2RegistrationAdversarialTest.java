package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2RegistrationAdversarialTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final String BINDING = "44444444-4444-4444-8444-444444444444";
    private static final String INSTANCE = "22222222-2222-4222-8222-222222222222";
    private static final String RECEIPT = "33333333-3333-4333-8333-333333333333";
    private static final byte[] PROCESS_PUBLIC = Base64.getDecoder().decode(
            "MCowBQYDK2VuAyEAB6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw=");
    private static final byte[] PROCESS_PRIVATE = HEX.parseHex(
            "302e020100300506032b656e042204200102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    private static final byte[] VERIFIER = HEX.parseHex(
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
    private static final byte[] CHALLENGE = HEX.parseHex(
            "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf");
    private static final byte[] PRIOR_HASH = HEX.parseHex(
            "78c7b100ea9ff082f4f2ff79221c1ceaf1a3d6eaff0518bbd5daee95762b719b");
    private static final String CREATE = "{\"schemaVersion\":2,\"messageType\":\"runtime.registration.create\",\"registrationRequestId\":\"11111111-1111-4111-8111-111111111111\",\"runtimeInstanceId\":\"22222222-2222-4222-8222-222222222222\",\"processPublicKeyBase64\":\"MCowBQYDK2VuAyEAB6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw=\"}";

    @Test
    void semanticAndSnapshotGoldensAreFixedAndDefensivelyCopied() {
        var subjectValue = new AgentRuntimeV2RegistrationFingerprints.Subject(
                "API_KEY", "api-key-1", "1", "runtime-client", "owner-a", "agent-a");
        byte[] subject = AgentRuntimeV2RegistrationFingerprints.subject(subjectValue);
        byte[] authority = AgentRuntimeV2RegistrationFingerprints.authority(authority());
        assertEquals("223cb4068819b73fa023de0bab1c5964cbc98d6cea5c343d35f671fd9530c8f9",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.createSemantic(subject, authority,
                        INSTANCE, AgentRuntimeV2RegistrationFingerprints.sha256(PROCESS_PUBLIC))));
        assertEquals("40c536ffd51544ba30d2aba87530ad5a539621d1a0bc8a2082bc2b122eba1c8a",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.challengeSemantic(subject, authority,
                        BINDING, RECEIPT, PRIOR_HASH, INSTANCE,
                        "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                        "dddddddd-dddd-4ddd-8ddd-dddddddddddd", 0)));
        assertEquals("3aef9b28d09fe116406cb6894d9ec02446e9ba4f45caa03000208568ea84d804",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.reconnectSemantic(subject, authority,
                        "88888888-8888-4888-8888-888888888888", BINDING, RECEIPT,
                        PRIOR_HASH, INSTANCE, VERIFIER, CHALLENGE)));

        byte[] evidence = new byte[32];
        Arrays.fill(evidence, (byte) 7);
        var current = holder(evidence, "55555555-5555-4555-8555-555555555555", 0, null,
                0, 5, 11, 1, RECEIPT, "CURRENT_ADMISSION", null,
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd", 0, 1790776900000L, 1790776800000L);
        assertEquals("26f3b52f38b01a0253f4efb93fa554d9cff82abed19084c97209286f226fc7fb",
                HEX.formatHex(current.sha256()));
        var frozen = holder(evidence, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 1,
                "55555555-5555-4555-8555-555555555555", 1, 6, 12, 2,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "FROZEN_DRAIN", "drain-op-1",
                "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                "ffffffff-ffff-4fff-8fff-ffffffffffff", 1, 1790777000000L, 1790776810000L);
        assertEquals("ad53d7d2f335ddd23a1cd045e0e9712c0b2b5893c7167b77fc074692043b64e8",
                HEX.formatHex(frozen.sha256()));
        var origin = new ExecutionOriginSnapshot("12121212-1212-4212-8212-121212121212", 101, 201,
                "1", "cyf", "local-experiment-a", "web-client", "runtime-client", "owner-a",
                "agent-a", "local-producer-a", "local-api-a", 4, 11, 7, "agent-command", 3,
                BINDING, 0, INSTANCE, "55555555-5555-4555-8555-555555555555", current.sha256());
        assertEquals("b58b5b52d08b6b77bd9023b935481a64047f7fe6f791dc02396c8c324e598e47",
                HEX.formatHex(origin.sha256()));
        evidence[0] ^= 1;
        byte[] holderCopy = current.registrationSubjectSha256(); holderCopy[0] ^= 1;
        byte[] originCopy = origin.dispatchGenerationSnapshotSha256(); originCopy[0] ^= 1;
        assertEquals("26f3b52f38b01a0253f4efb93fa554d9cff82abed19084c97209286f226fc7fb",
                HEX.formatHex(current.sha256()));
        assertEquals("b58b5b52d08b6b77bd9023b935481a64047f7fe6f791dc02396c8c324e598e47",
                HEX.formatHex(origin.sha256()));
        var changed = new ExecutionOriginSnapshot(origin.runtimeDispatchId(), origin.inboxId(),
                origin.deliveryId(), origin.tenantId(), origin.dataDomainId(), origin.executionPoolId(),
                "other-client", origin.runtimeCredentialClientId(), origin.ownerJiacn(), origin.agentId(),
                origin.producerDeploymentId(), origin.claimantDeploymentId(), origin.claimantWorkerGeneration(),
                origin.receiverRegistrationRevision(), origin.admissionEpoch(), origin.moduleId(),
                origin.migrationEpoch(), origin.runtimeBindingId(), origin.dispatchBindingGeneration(),
                origin.runtimeInstanceId(), origin.dispatchGenerationFactId(),
                origin.dispatchGenerationSnapshotSha256());
        assertFalse(MessageDigest.isEqual(origin.sha256(), changed.sha256()));
    }

    @Test
    void productionSealRoundTripsOnlyWithFinalCanonicalWireMetadata() {
        byte[] processHash = AgentRuntimeV2RegistrationFingerprints.sha256(PROCESS_PUBLIC);
        byte[] verifierHash = AgentRuntimeV2RegistrationFingerprints.sha256(VERIFIER);
        var sealed = AgentRuntimeV2RegistrationCrypto.sealVerifier(VERIFIER, PROCESS_PUBLIC,
                (server, nonce) -> AgentRuntimeV2RegistrationCodec.receiptAad(
                        receipt(server, nonce, new byte[48], processHash, verifierHash)));
        var decoded = AgentRuntimeV2RegistrationCodec.decodeReceipt(
                AgentRuntimeV2RegistrationCodec.encodeReceipt(receipt(sealed.serverEphemeralPublicKeySpki(),
                        sealed.nonce(), sealed.sealed(), processHash, verifierHash)));
        byte[] aad = AgentRuntimeV2RegistrationCodec.receiptAad(decoded);
        assertArrayEquals(VERIFIER, AgentRuntimeV2RegistrationCrypto.openVerifier(sealed.sealed(),
                sealed.nonce(), aad, PROCESS_PRIVATE, decoded.serverEphemeralPublicKeySpki()));
        byte[] wrongServer = Base64.getDecoder().decode(
                "MCowBQYDK2VuAyEAZLEBsdC+WocEvQePmJUAH8A+jp+VIvGI3RKNmEbUhGY=");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCrypto.openVerifier(
                sealed.sealed(), sealed.nonce(), aad, PROCESS_PRIVATE, wrongServer));
        byte[] changedNonce = sealed.nonce(); changedNonce[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCrypto.openVerifier(
                sealed.sealed(), changedNonce, aad, PROCESS_PRIVATE, decoded.serverEphemeralPublicKeySpki()));

        byte[] challengeHash = AgentRuntimeV2RegistrationFingerprints.sha256(CHALLENGE);
        var challengeSealed = AgentRuntimeV2RegistrationCrypto.sealChallenge(CHALLENGE, PROCESS_PUBLIC,
                (server, nonce) -> AgentRuntimeV2RegistrationCodec.challengeAad(
                        challenge(server, nonce, new byte[48], challengeHash)));
        var decodedChallenge = AgentRuntimeV2RegistrationCodec.decodeChallenge(
                AgentRuntimeV2RegistrationCodec.encodeChallenge(challenge(
                        challengeSealed.serverEphemeralPublicKeySpki(), challengeSealed.nonce(),
                        challengeSealed.sealed(), challengeHash)));
        assertArrayEquals(CHALLENGE, AgentRuntimeV2RegistrationCrypto.openChallenge(
                decodedChallenge.sealedChallenge(), decodedChallenge.nonce(),
                AgentRuntimeV2RegistrationCodec.challengeAad(decodedChallenge), PROCESS_PRIVATE,
                decodedChallenge.serverEphemeralPublicKeySpki()));
    }

    @Test
    void strictCodecAndReceiptShapesFailClosed() {
        assertRejectsCreate(CREATE.replace("\"messageType\":", "\"unknown\":0,\"messageType\":"));
        assertRejectsCreate(CREATE.replace("\"runtimeInstanceId\":",
                "\"registrationRequestId\":\"11111111-1111-4111-8111-111111111111\",\"runtimeInstanceId\":"));
        assertRejectsCreate(CREATE.replace("\"schemaVersion\":2,\"messageType\":\"runtime.registration.create\"",
                "\"messageType\":\"runtime.registration.create\",\"schemaVersion\":2"));
        assertRejectsCreate(CREATE.replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""));
        assertRejectsCreate(CREATE.replace("HHw=", "HHw"));
        assertRejectsCreate(CREATE.replace("MCowBQYDK2VuAyEA", "MCswBQYDK2VuAyEA"));
        var create = AgentRuntimeV2RegistrationCodec.decodeReceipt(createReceiptWire());
        assertThrows(IllegalArgumentException.class, () -> copyReceipt(create, "CREATE", RECEIPT, null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> copyReceipt(create, "RECONNECT", null, PRIOR_HASH, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> copyReceipt(create, "RECONNECT", RECEIPT, PRIOR_HASH, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> copyReceipt(create, "RECONNECT", RECEIPT,
                PRIOR_HASH, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void authorityVectorRejectsNonTypedOrNonExactFieldsWithoutChangingGolden() {
        AgentRuntimeV2RegistrationAuthority valid = authority();
        assertEquals("0f2eb47a8e8646fd33285d206df1300209d19c292bd176a664b083eefba9b453",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.authority(valid)));
        assertAuthorityRejected(valid, 6, null);
        assertAuthorityRejected(valid, 6, "01");
        assertAuthorityRejected(valid, 6, "9223372036854775808");
        assertAuthorityRejected(valid, 4, "A".repeat(64));
        assertAuthorityRejected(valid, 38, "true");
        assertAuthorityRejected(valid, 34, "REVOKED");
        assertAuthorityRejected(valid, 3, "bad\ud800");
        assertAuthorityRejected(valid, 3, "bad\udc00");
        List<String> unicode = new ArrayList<>(valid.fields()); unicode.set(3, "测试-key");
        assertDoesNotThrow(() -> AgentRuntimeV2RegistrationFingerprints.authority(
                new AgentRuntimeV2RegistrationAuthority(unicode)));
    }

    private static AgentRuntimeV2RegistrationAuthority authority() {
        return new AgentRuntimeV2RegistrationAuthority(Arrays.asList(
                "5a8567e1c9c076fab680c4c9240f01bb110f982609f63e014ff89a23eb2e8151",
                "42", "9", "test-hmac-2026-09-30",
                "3931239d05c1fe6b6be8fbc19ae161a5916cbc6ccc62d5cad05230b32cdfd497",
                null, "101", "201", "1790776700000", "301", "1790776710000", "8", "3",
                "66666666-6666-4666-8666-666666666666", "5", "cyf", "local-experiment-a",
                "ENABLED", "12", "6", "7", "2", "agent-command", "DOMAIN", "4", "3", "1", "2",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, null,
                "local-api-a", "LOCAL_TEST",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "ACTIVE", "4", "11", "13", "1", "1", "DOMAIN", "4", "3", "7", "2", "11",
                "13", "1", "1", "ACTIVE"));
    }

    private static void assertAuthorityRejected(AgentRuntimeV2RegistrationAuthority valid, int index,
            String replacement) {
        List<String> fields = new ArrayList<>(valid.fields()); fields.set(index, replacement);
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeV2RegistrationAuthority(fields));
    }

    private static AuthenticatedHolderGenerationSnapshot holder(byte[] hash, String factId,
            long sequence, String previous, long generation, long authorityRevision,
            long receiverRevision, long verifierVersion, String receiptId, String admissionKind,
            String drainOperation, String sessionFence, String socketOwner, long socketEpoch,
            long leaseUntil, long effectiveAt) {
        return new AuthenticatedHolderGenerationSnapshot(factId, sequence, previous, BINDING, generation,
                INSTANCE, hash, "66666666-6666-4666-8666-666666666666", authorityRevision, "1", "cyf",
                "local-experiment-a", "runtime-client", "owner-a", "agent-a", "local-api-a", 4,
                receiverRevision, 7, "agent-command", 3, hash, verifierVersion, receiptId, hash,
                admissionKind, drainOperation, sessionFence, socketOwner, socketEpoch, leaseUntil, effectiveAt);
    }

    private static AgentRuntimeV2RegistrationCodec.Receipt receipt(byte[] server, byte[] nonce,
            byte[] sealed, byte[] processHash, byte[] verifierHash) {
        return new AgentRuntimeV2RegistrationCodec.Receipt(RECEIPT, "CREATE",
                "11111111-1111-4111-8111-111111111111", null, null, BINDING, 0,
                "55555555-5555-4555-8555-555555555555", INSTANCE, processHash, 1, verifierHash,
                server, nonce, sealed, "1", "cyf", "local-experiment-a", "runtime-client",
                "owner-a", "agent-a", "66666666-6666-4666-8666-666666666666", 5,
                "local-api-a", 4, 11, 7, "agent-command", 3, 1790776800000L);
    }

    private static AgentRuntimeV2RegistrationCodec.Receipt copyReceipt(
            AgentRuntimeV2RegistrationCodec.Receipt source, String kind, String previousId,
            byte[] previousHash, long generation, long verifierVersion) {
        return new AgentRuntimeV2RegistrationCodec.Receipt(
                "RECONNECT".equals(kind) ? "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" : source.registrationReceiptId(),
                kind, "RECONNECT".equals(kind) ? "99999999-9999-4999-8999-999999999999" : source.registrationRequestId(),
                previousId, previousHash, source.runtimeBindingId(), generation,
                "RECONNECT".equals(kind) ? "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" : source.bindingGenerationFactId(),
                source.runtimeInstanceId(), source.processPublicKeySha256(), verifierVersion,
                source.verifierSha256(), source.serverEphemeralPublicKeySpki(), source.nonce(),
                source.sealedVerifier(), source.tenantId(), source.dataDomainId(), source.executionPoolId(),
                source.runtimeCredentialClientId(), source.ownerJiacn(), source.agentId(),
                source.registrationAuthorityId(), source.registrationAuthorityRevision(),
                source.claimantDeploymentId(), source.claimantWorkerGeneration(),
                source.receiverRegistrationRevision(), source.admissionEpoch(), source.moduleId(),
                source.migrationEpoch(), source.connectedAt());
    }

    private static AgentRuntimeV2RegistrationCodec.Challenge challenge(byte[] server, byte[] nonce,
            byte[] sealed, byte[] hash) {
        return new AgentRuntimeV2RegistrationCodec.Challenge(
                "77777777-7777-4777-8777-777777777777", "88888888-8888-4888-8888-888888888888",
                BINDING, 0, INSTANCE, RECEIPT, PRIOR_HASH, server, nonce, sealed, hash, 1790776860000L);
    }

    private static byte[] createReceiptWire() {
        return AgentRuntimeV2RegistrationCodec.encodeReceipt(receipt(
                Base64.getDecoder().decode("MCowBQYDK2VuAyEAWGmv9FBUlzLLqu1eXfmzCm2jHLDldCutWtShp2jxpns="),
                Base64.getDecoder().decode("AQIDBAUGBwgJCgsM"), new byte[48],
                AgentRuntimeV2RegistrationFingerprints.sha256(PROCESS_PUBLIC),
                AgentRuntimeV2RegistrationFingerprints.sha256(VERIFIER)));
    }

    private static void assertRejectsCreate(String wire) {
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeCreate(
                wire.getBytes(StandardCharsets.UTF_8)));
    }
}
