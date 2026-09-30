package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2RegistrationContractTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final String BINDING = "44444444-4444-4444-8444-444444444444";
    private static final String INSTANCE = "22222222-2222-4222-8222-222222222222";
    private static final String CREATE_RECEIPT_ID = "33333333-3333-4333-8333-333333333333";
    private static final byte[] PROCESS_PUBLIC = Base64.getDecoder().decode(
            "MCowBQYDK2VuAyEAB6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw=");
    private static final byte[] PROCESS_PRIVATE = key("302e020100300506032b656e04220420",
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    private static final byte[] VERIFIER1 = HEX.parseHex(
            "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");
    private static final byte[] CHALLENGE = HEX.parseHex(
            "c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf");
    private static final byte[] VERIFIER2 = HEX.parseHex(
            "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");

    private static final String[] JSON = {
        "{\"schemaVersion\":2,\"messageType\":\"runtime.registration.create\",\"registrationRequestId\":\"11111111-1111-4111-8111-111111111111\",\"runtimeInstanceId\":\"22222222-2222-4222-8222-222222222222\",\"processPublicKeyBase64\":\"MCowBQYDK2VuAyEAB6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw=\"}",
        "{\"schemaVersion\":2,\"messageType\":\"runtime.registration.receipt\",\"registrationReceiptId\":\"33333333-3333-4333-8333-333333333333\",\"receiptKind\":\"CREATE\",\"registrationRequestId\":\"11111111-1111-4111-8111-111111111111\",\"previousRegistrationReceiptId\":null,\"previousRegistrationReceiptSha256\":null,\"runtimeBindingId\":\"44444444-4444-4444-8444-444444444444\",\"bindingGeneration\":\"0\",\"bindingGenerationFactId\":\"55555555-5555-4555-8555-555555555555\",\"runtimeInstanceId\":\"22222222-2222-4222-8222-222222222222\",\"processPublicKeySha256\":\"ef1f7d8ef0ab699b890076ef20201662cde62fccd4571b98c263d73c2d1d574f\",\"verifierVersion\":\"1\",\"verifierSha256\":\"00e988677eecf94c0bb9233371c7c0d6f4db8ebdcdecb7c5ebaa666f17249227\",\"verifierCipherSuite\":\"X25519-HKDF-SHA256-AES-256-GCM\",\"serverEphemeralPublicKeyBase64\":\"MCowBQYDK2VuAyEAWGmv9FBUlzLLqu1eXfmzCm2jHLDldCutWtShp2jxpns=\",\"nonceBase64\":\"AQIDBAUGBwgJCgsM\",\"sealedVerifierBase64\":\"XobpgfrxbI+lLv1am7yTJZ8QplMmDzDgTXwX24Ih4cMc8qeeuZxRQWUfIxRfl7Cg\",\"tenantId\":\"1\",\"dataDomainId\":\"cyf\",\"executionPoolId\":\"local-experiment-a\",\"runtimeCredentialClientId\":\"runtime-client\",\"ownerJiacn\":\"owner-a\",\"agentId\":\"agent-a\",\"registrationAuthorityId\":\"66666666-6666-4666-8666-666666666666\",\"registrationAuthorityRevision\":\"5\",\"claimantDeploymentId\":\"local-api-a\",\"claimantWorkerGeneration\":\"4\",\"receiverRegistrationRevision\":\"11\",\"admissionEpoch\":\"7\",\"moduleId\":\"agent-command\",\"migrationEpoch\":\"3\",\"connectedAt\":\"1790776800000\"}",
        "{\"schemaVersion\":2,\"messageType\":\"runtime.registration.reconnect.challenge.request\",\"challengeRequestId\":\"77777777-7777-4777-8777-777777777777\",\"runtimeBindingId\":\"44444444-4444-4444-8444-444444444444\",\"priorRegistrationReceiptId\":\"33333333-3333-4333-8333-333333333333\",\"priorRegistrationReceiptSha256\":\"78c7b100ea9ff082f4f2ff79221c1ceaf1a3d6eaff0518bbd5daee95762b719b\",\"runtimeInstanceId\":\"22222222-2222-4222-8222-222222222222\"}",
        "{\"schemaVersion\":2,\"messageType\":\"runtime.registration.reconnect.challenge\",\"challengeRequestId\":\"77777777-7777-4777-8777-777777777777\",\"challengeId\":\"88888888-8888-4888-8888-888888888888\",\"runtimeBindingId\":\"44444444-4444-4444-8444-444444444444\",\"bindingGeneration\":\"0\",\"runtimeInstanceId\":\"22222222-2222-4222-8222-222222222222\",\"priorRegistrationReceiptId\":\"33333333-3333-4333-8333-333333333333\",\"priorRegistrationReceiptSha256\":\"78c7b100ea9ff082f4f2ff79221c1ceaf1a3d6eaff0518bbd5daee95762b719b\",\"challengeCipherSuite\":\"X25519-HKDF-SHA256-AES-256-GCM\",\"serverEphemeralPublicKeyBase64\":\"MCowBQYDK2VuAyEAZLEBsdC+WocEvQePmJUAH8A+jp+VIvGI3RKNmEbUhGY=\",\"nonceBase64\":\"DQ4PEBESExQVFhcY\",\"sealedChallengeBase64\":\"BRIQPciMDSTsMv1a5fsu89rILGzfYoHe9K82qTxny/PuBdQ+8p6T58m2yZ+HdfaQ\",\"challengeSha256\":\"ec071e0a0136c837c051cee6a7713edbaea6936712d1a3ca37e84fee3226e61d\",\"expiresAt\":\"1790776860000\"}",
        "{\"schemaVersion\":2,\"messageType\":\"runtime.registration.reconnect.commit\",\"reconnectRequestId\":\"99999999-9999-4999-8999-999999999999\",\"challengeId\":\"88888888-8888-4888-8888-888888888888\",\"runtimeBindingId\":\"44444444-4444-4444-8444-444444444444\",\"priorRegistrationReceiptId\":\"33333333-3333-4333-8333-333333333333\",\"priorRegistrationReceiptSha256\":\"78c7b100ea9ff082f4f2ff79221c1ceaf1a3d6eaff0518bbd5daee95762b719b\",\"runtimeInstanceId\":\"22222222-2222-4222-8222-222222222222\",\"verifierBase64\":\"oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8=\",\"challengeBase64\":\"wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t8=\"}",
        "{\"schemaVersion\":2,\"messageType\":\"runtime.registration.receipt\",\"registrationReceiptId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"receiptKind\":\"RECONNECT\",\"registrationRequestId\":\"99999999-9999-4999-8999-999999999999\",\"previousRegistrationReceiptId\":\"33333333-3333-4333-8333-333333333333\",\"previousRegistrationReceiptSha256\":\"78c7b100ea9ff082f4f2ff79221c1ceaf1a3d6eaff0518bbd5daee95762b719b\",\"runtimeBindingId\":\"44444444-4444-4444-8444-444444444444\",\"bindingGeneration\":\"1\",\"bindingGenerationFactId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\",\"runtimeInstanceId\":\"22222222-2222-4222-8222-222222222222\",\"processPublicKeySha256\":\"ef1f7d8ef0ab699b890076ef20201662cde62fccd4571b98c263d73c2d1d574f\",\"verifierVersion\":\"2\",\"verifierSha256\":\"9432c1a7d343fcfacb164bdc44ff71c1281c004886b1c428419088d06cd3561a\",\"verifierCipherSuite\":\"X25519-HKDF-SHA256-AES-256-GCM\",\"serverEphemeralPublicKeyBase64\":\"MCowBQYDK2VuAyEAJE/juWPomd0pW6/84kjTUw86mnR5ugYwAmgOv+etrUk=\",\"nonceBase64\":\"GRobHB0eHyAhIiMk\",\"sealedVerifierBase64\":\"C/SDWXv2fdvviWvoGB9rC6SCFGcIKzud13gD57Qpzp8O50oPi+vhoGWTIu2kAiys\",\"tenantId\":\"1\",\"dataDomainId\":\"cyf\",\"executionPoolId\":\"local-experiment-a\",\"runtimeCredentialClientId\":\"runtime-client\",\"ownerJiacn\":\"owner-a\",\"agentId\":\"agent-a\",\"registrationAuthorityId\":\"66666666-6666-4666-8666-666666666666\",\"registrationAuthorityRevision\":\"5\",\"claimantDeploymentId\":\"local-api-a\",\"claimantWorkerGeneration\":\"4\",\"receiverRegistrationRevision\":\"11\",\"admissionEpoch\":\"7\",\"moduleId\":\"agent-command\",\"migrationEpoch\":\"3\",\"connectedAt\":\"1790776810000\"}"
    };
    private static final String[] JSON_HASH = {
        "7152bf086cc0829446f08cbf8eb57d6a35fd1b141431cc9cf37ddd7daebf8482",
        "78c7b100ea9ff082f4f2ff79221c1ceaf1a3d6eaff0518bbd5daee95762b719b",
        "9cfe00db7f5ebe82f88e00c837a7bed98a05cb2916c2ee7492b1b5e8750f623d",
        "f13ce89a7d91d108f1968e7bb1338122abf937aedf04a2621e11b643d24e0be4",
        "777599c5e8e4f1a1b0500fd670391bcc200675cb266dc721d79220a70d8c3799",
        "6241b72a437940cbc163e24d1e95ea42c54cb58d507f6b7d03e12a4d0df732ae"
    };

    @Test
    void sixCanonicalJsonVectorsAreByteExact() {
        for (int i = 0; i < JSON.length; i++) {
            byte[] wire = JSON[i].getBytes(StandardCharsets.UTF_8);
            assertEquals(JSON_HASH[i], HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(wire)));
            byte[] encoded = switch (i) {
                case 0 -> AgentRuntimeV2RegistrationCodec.encodeCreate(AgentRuntimeV2RegistrationCodec.decodeCreate(wire));
                case 1, 5 -> AgentRuntimeV2RegistrationCodec.encodeReceipt(AgentRuntimeV2RegistrationCodec.decodeReceipt(wire));
                case 2 -> AgentRuntimeV2RegistrationCodec.encodeChallengeRequest(AgentRuntimeV2RegistrationCodec.decodeChallengeRequest(wire));
                case 3 -> AgentRuntimeV2RegistrationCodec.encodeChallenge(AgentRuntimeV2RegistrationCodec.decodeChallenge(wire));
                case 4 -> AgentRuntimeV2RegistrationCodec.encodeCommit(AgentRuntimeV2RegistrationCodec.decodeCommit(wire));
                default -> throw new AssertionError();
            };
            assertArrayEquals(wire, encoded);
        }
    }

    @Test
    void fingerprintsHmacAndThreeCryptoVectorsMatchGolden() throws Exception {
        var subject = new AgentRuntimeV2RegistrationFingerprints.Subject(
                "API_KEY", "api-key-1", "1", "runtime-client", "owner-a", "agent-a");
        assertEquals("5a8567e1c9c076fab680c4c9240f01bb110f982609f63e014ff89a23eb2e8151",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.subject(subject)));
        List<String> fields = Arrays.asList(
                "5a8567e1c9c076fab680c4c9240f01bb110f982609f63e014ff89a23eb2e8151",
                "42", "9", "test-hmac-2026-09-30",
                "3931239d05c1fe6b6be8fbc19ae161a5916cbc6ccc62d5cad05230b32cdfd497",
                null, "101", "201", "1790776700000", "301", "1790776710000", "8", "3",
                "66666666-6666-4666-8666-666666666666", "5", "cyf", "local-experiment-a",
                "ENABLED", "12", "6", "7", "2", "agent-command", "DOMAIN", "4", "3", "1", "2",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, null,
                "local-api-a", "LOCAL_TEST",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "ACTIVE", "4", "11", "13", "1", "1", "DOMAIN", "4", "3", "7", "2",
                "11", "13", "1", "1", "ACTIVE");
        byte[] authority = AgentRuntimeV2RegistrationFingerprints.authority(
                new AgentRuntimeV2RegistrationAuthority(fields));
        assertEquals("0f2eb47a8e8646fd33285d206df1300209d19c292bd176a664b083eefba9b453",
                HEX.formatHex(authority));
        var key = new AgentRuntimeV2RegistrationFingerprints.HmacKeyRef("test-hmac-2026-09-30",
                new SecretKeySpec(HEX.parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), "HmacSHA256"));
        assertEquals("3931239d05c1fe6b6be8fbc19ae161a5916cbc6ccc62d5cad05230b32cdfd497",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.apiKeyCredential(key,
                        "api-key-1", "0123456789abcdef0123456789abcdef")));
        assertEquals("5c4eb21cb2a5a7e88b4a8e1f73d14307f542784a149cf897fc850c161299dd3a",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.reconnectVerifier(key, BINDING, 1, VERIFIER1)));
        assertEquals("540a1b8c0350b5a6834cb9fee63d1e6db3f901f0c127834bd9d3c66dd4204dd8",
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.reconnectChallenge(key,
                        "88888888-8888-4888-8888-888888888888", BINDING, 0, CREATE_RECEIPT_ID, INSTANCE, CHALLENGE)));

        verifyCryptoVector(AgentRuntimeV2RegistrationCodec.decodeReceipt(bytes(JSON[1])), VERIFIER1,
                key("302e020100300506032b656e04220420", "2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40"), false);
        verifyCryptoVector(AgentRuntimeV2RegistrationCodec.decodeChallenge(bytes(JSON[3])), CHALLENGE,
                key("302e020100300506032b656e04220420", "4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60"), true);
        verifyCryptoVector(AgentRuntimeV2RegistrationCodec.decodeReceipt(bytes(JSON[5])), VERIFIER2,
                key("302e020100300506032b656e04220420", "6162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f80"), false);
    }

    @Test
    void productionSealUsesFinalMetadataInAadAndRejectsTampering() {
        byte[] processHash = AgentRuntimeV2RegistrationFingerprints.sha256(PROCESS_PUBLIC);
        byte[] verifierHash = AgentRuntimeV2RegistrationFingerprints.sha256(VERIFIER1);
        AgentRuntimeV2RegistrationCrypto.Sealed sealed = AgentRuntimeV2RegistrationCrypto.sealVerifier(
                VERIFIER1, PROCESS_PUBLIC, (server, nonce) -> AgentRuntimeV2RegistrationCodec.receiptAad(
                        receipt(server, nonce, new byte[48], processHash, verifierHash, "1")));
        var finalReceipt = receipt(sealed.serverEphemeralPublicKeySpki(), sealed.nonce(), sealed.sealed(),
                processHash, verifierHash, "1");
        byte[] aad = AgentRuntimeV2RegistrationCodec.receiptAad(finalReceipt);
        assertArrayEquals(VERIFIER1, AgentRuntimeV2RegistrationCrypto.openVerifier(sealed.sealed(),
                sealed.nonce(), aad, PROCESS_PRIVATE, sealed.serverEphemeralPublicKeySpki()));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCrypto.openVerifier(
                sealed.sealed(), sealed.nonce(), AgentRuntimeV2RegistrationCodec.receiptAad(
                        receipt(sealed.serverEphemeralPublicKeySpki(), sealed.nonce(), sealed.sealed(),
                                processHash, verifierHash, "2")), PROCESS_PRIVATE,
                sealed.serverEphemeralPublicKeySpki()));
        byte[] changedNonce = sealed.nonce(); changedNonce[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCrypto.openVerifier(
                sealed.sealed(), changedNonce, aad, PROCESS_PRIVATE, sealed.serverEphemeralPublicKeySpki()));

        byte[] challengeHash = AgentRuntimeV2RegistrationFingerprints.sha256(CHALLENGE);
        AgentRuntimeV2RegistrationCrypto.Sealed challengeSealed = AgentRuntimeV2RegistrationCrypto.sealChallenge(
                CHALLENGE, PROCESS_PUBLIC, (server, nonce) -> AgentRuntimeV2RegistrationCodec.challengeAad(
                        challenge(server, nonce, new byte[48], challengeHash)));
        var finalChallenge = challenge(challengeSealed.serverEphemeralPublicKeySpki(), challengeSealed.nonce(),
                challengeSealed.sealed(), challengeHash);
        assertArrayEquals(CHALLENGE, AgentRuntimeV2RegistrationCrypto.openChallenge(challengeSealed.sealed(),
                challengeSealed.nonce(), AgentRuntimeV2RegistrationCodec.challengeAad(finalChallenge),
                PROCESS_PRIVATE, challengeSealed.serverEphemeralPublicKeySpki()));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCrypto.openChallenge(
                challengeSealed.sealed(), challengeSealed.nonce(),
                AgentRuntimeV2RegistrationCodec.challengeAad(challenge(challengeSealed.serverEphemeralPublicKeySpki(),
                        challengeSealed.nonce(), challengeSealed.sealed(), new byte[32])),
                PROCESS_PRIVATE, challengeSealed.serverEphemeralPublicKeySpki()));
    }

    @Test
    void malformedWireReceiptShapesTenantAndUnicodeFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeCreate(
                bytes(JSON[0].replace("{\"schemaVersion\":2,\"messageType\"",
                        "{\"messageType\""))));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeCreate(
                bytes(JSON[0].replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""))));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeCreate(
                bytes(JSON[0].replace("MCowBQYDK2Vu", "!!!!BQYDK2Vu"))));
        for (String tenant : List.of("00", "+1", " 1", "x")) {
            assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeV2RegistrationFingerprints.Subject(
                    "API_KEY", "api-key-1", tenant, "runtime-client", "owner-a", "agent-a"));
        }
        assertDoesNotThrow(() -> new AgentRuntimeV2RegistrationFingerprints.Subject("API_KEY", "api-key-1",
                "12345678901234567890123456789012345678901234567890", "runtime-client", "owner-a", "agent-a"));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeV2RegistrationFingerprints.Subject(
                "PASSWORD", "api-key-1", "1", "runtime-client", "owner-a", "agent-a"));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeV2RegistrationFingerprints.Subject(
                "API_KEY", "bad\ud800", "1", "runtime-client", "owner-a", "agent-a"));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeV2RegistrationFingerprints.Subject(
                "API_KEY", "bad\udc00", "1", "runtime-client", "owner-a", "agent-a"));

        var create = AgentRuntimeV2RegistrationCodec.decodeReceipt(bytes(JSON[1]));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeV2RegistrationCodec.Receipt(
                create.registrationReceiptId(), "CREATE", create.registrationRequestId(), CREATE_RECEIPT_ID,
                null, create.runtimeBindingId(), 0, create.bindingGenerationFactId(), create.runtimeInstanceId(),
                create.processPublicKeySha256(), 1, create.verifierSha256(), create.serverEphemeralPublicKeySpki(),
                create.nonce(), create.sealedVerifier(), create.tenantId(), create.dataDomainId(), create.executionPoolId(),
                create.runtimeCredentialClientId(), create.ownerJiacn(), create.agentId(), create.registrationAuthorityId(),
                create.registrationAuthorityRevision(), create.claimantDeploymentId(), create.claimantWorkerGeneration(),
                create.receiverRegistrationRevision(), create.admissionEpoch(), create.moduleId(), create.migrationEpoch(),
                create.connectedAt()));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeV2RegistrationCodec.Receipt(
                create.registrationReceiptId(), "CREATE", create.registrationRequestId(), null,
                null, create.runtimeBindingId(), 1, create.bindingGenerationFactId(), create.runtimeInstanceId(),
                create.processPublicKeySha256(), 2, create.verifierSha256(), create.serverEphemeralPublicKeySpki(),
                create.nonce(), create.sealedVerifier(), create.tenantId(), create.dataDomainId(), create.executionPoolId(),
                create.runtimeCredentialClientId(), create.ownerJiacn(), create.agentId(), create.registrationAuthorityId(),
                create.registrationAuthorityRevision(), create.claimantDeploymentId(), create.claimantWorkerGeneration(),
                create.receiverRegistrationRevision(), create.admissionEpoch(), create.moduleId(), create.migrationEpoch(),
                create.connectedAt()));
    }

    @Test
    void currentAndFrozenHolderSnapshotsKeepNullableLpPositions() {
        byte[] hash = new byte[32]; Arrays.fill(hash, (byte) 7);
        var current = new AuthenticatedHolderGenerationSnapshot(
                "55555555-5555-4555-8555-555555555555", 0, null, BINDING, 0, INSTANCE,
                hash, "66666666-6666-4666-8666-666666666666", 5, "1", "cyf", "local-experiment-a",
                "runtime-client", "owner-a", "agent-a", "local-api-a", 4, 11, 7,
                "agent-command", 3, hash, 1, CREATE_RECEIPT_ID, hash, "CURRENT_ADMISSION", null,
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                0, 1790776900000L, 1790776800000L);
        assertEquals(32, current.sha256().length);
        var frozen = new AuthenticatedHolderGenerationSnapshot(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", 1,
                "55555555-5555-4555-8555-555555555555", BINDING, 1, INSTANCE,
                hash, "66666666-6666-4666-8666-666666666666", 6, "1", "cyf", "local-experiment-a",
                "runtime-client", "owner-a", "agent-a", "local-api-a", 4, 12, 7,
                "agent-command", 3, hash, 2, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", hash,
                "FROZEN_DRAIN", "drain-op-1", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                "ffffffff-ffff-4fff-8fff-ffffffffffff", 1, 1790777000000L, 1790776810000L);
        assertEquals(32, frozen.sha256().length);
        assertFalse(MessageDigest.isEqual(current.sha256(), frozen.sha256()));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedHolderGenerationSnapshot(
                frozen.bindingGenerationFactId(), 1, frozen.previousFactId(), BINDING, 1, INSTANCE,
                hash, frozen.registrationAuthorityId(), 6, "1", "cyf", "local-experiment-a",
                "runtime-client", "owner-a", "agent-a", "local-api-a", 4, 12, 7,
                "agent-command", 3, hash, 2, frozen.registrationReceiptId(), hash,
                "FROZEN_DRAIN", null, frozen.sessionFence(), frozen.socketOwnerInstanceId(),
                1, 1790777000000L, 1790776810000L));
    }

    private static void verifyCryptoVector(Object value, byte[] plaintext, byte[] serverPrivate,
            boolean challenge) {
        byte[] aad;
        byte[] expectedPublic;
        byte[] expectedNonce;
        byte[] expectedSealed;
        if (value instanceof AgentRuntimeV2RegistrationCodec.Receipt receipt) {
            aad = AgentRuntimeV2RegistrationCodec.receiptAad(receipt);
            expectedPublic = receipt.serverEphemeralPublicKeySpki();
            expectedNonce = receipt.nonce();
            expectedSealed = receipt.sealedVerifier();
        } else {
            var response = (AgentRuntimeV2RegistrationCodec.Challenge) value;
            aad = AgentRuntimeV2RegistrationCodec.challengeAad(response);
            expectedPublic = response.serverEphemeralPublicKeySpki();
            expectedNonce = response.nonce();
            expectedSealed = response.sealedChallenge();
        }
        var sealed = AgentRuntimeV2RegistrationCrypto.sealForTest(
                plaintext, PROCESS_PUBLIC, aad, serverPrivate, expectedNonce, challenge);
        assertArrayEquals(expectedPublic, sealed.serverEphemeralPublicKeySpki());
        assertArrayEquals(expectedSealed, sealed.sealed());
        byte[] opened = challenge
                ? AgentRuntimeV2RegistrationCrypto.openChallenge(sealed.sealed(), sealed.nonce(), aad,
                        PROCESS_PRIVATE, sealed.serverEphemeralPublicKeySpki())
                : AgentRuntimeV2RegistrationCrypto.openVerifier(sealed.sealed(), sealed.nonce(), aad,
                        PROCESS_PRIVATE, sealed.serverEphemeralPublicKeySpki());
        assertArrayEquals(plaintext, opened);
    }

    private static AgentRuntimeV2RegistrationCodec.Receipt receipt(byte[] server, byte[] nonce, byte[] sealed,
            byte[] processHash, byte[] verifierHash, String tenant) {
        return new AgentRuntimeV2RegistrationCodec.Receipt(CREATE_RECEIPT_ID, "CREATE",
                "11111111-1111-4111-8111-111111111111", null, null, BINDING, 0,
                "55555555-5555-4555-8555-555555555555", INSTANCE, processHash, 1, verifierHash,
                server, nonce, sealed, tenant, "cyf", "local-experiment-a", "runtime-client",
                "owner-a", "agent-a", "66666666-6666-4666-8666-666666666666", 5,
                "local-api-a", 4, 11, 7, "agent-command", 3, 1790776800000L);
    }

    private static AgentRuntimeV2RegistrationCodec.Challenge challenge(byte[] server, byte[] nonce,
            byte[] sealed, byte[] challengeHash) {
        return new AgentRuntimeV2RegistrationCodec.Challenge(
                "77777777-7777-4777-8777-777777777777", "88888888-8888-4888-8888-888888888888",
                BINDING, 0, INSTANCE, CREATE_RECEIPT_ID, HEX.parseHex(JSON_HASH[1]), server, nonce,
                sealed, challengeHash, 1790776860000L);
    }

    private static byte[] key(String prefix, String seed) { return HEX.parseHex(prefix + seed); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
