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
    void outerReceiptDeliveryAndRejectedVectorsAreByteExactAndStrict() {
        assertDelivery(JSON[1], "ADVANCED", "CURRENT", 2267,
                "95642e592820acc9b1aff58e50b246315c2c93d0a7fe973c1e1e4ad1cf5ef294");
        assertDelivery(JSON[1], "PRIOR", "RECONNECT_REQUIRED", 2275,
                "6b3dc9ded12b116a63873f5e30966c886ca470fbe36870e5cd303e0819cdf48d");
        assertDelivery(JSON[5], "ADVANCED", "CURRENT", 2402,
                "eb980ff9927aee56828ef1c9a03181916dc1a73cd56f8825edf218a6daff9e75");
        assertDelivery(JSON[5], "PRIOR", "RECONNECT_REQUIRED", 2410,
                "9b71fdf6a655ee50e71d02b2c4493d1b5330c16995cf83fafccc568824621b69");

        assertRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",
                        "11111111-1111-4111-8111-111111111111", null, "NOT_AUTHORIZED"),
                201, "c6ae083bc11a09fff48c7247cfe0ecd6b4d120c4c32113c04574627b31048315");
        assertRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CHALLENGE",
                        "77777777-7777-4777-8777-777777777777", BINDING, "REQUEST_CONFLICT"),
                240, "778d2ca84f1225512f0c95e678c90822ebd257fd4247e264ad8f087ab33eb17e");
        assertRejected(new AgentRuntimeV2RegistrationCodec.Rejected("RECONNECT",
                        "99999999-9999-4999-8999-999999999999", BINDING, "UNAVAILABLE"),
                235, "19802525f1590e9af21584ca4c2fb701a5cb31e5cb18df61318200fb71f8adcf");
        assertRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",
                        "11111111-1111-4111-8111-111111111111", BINDING,
                        "RECONCILIATION_REQUIRED"),
                244, "da7ef194a0cea2c7e14c42dfaebc14e8f387492bace6d4cda508b23b8b26a85f");
        assertRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CHALLENGE",
                        "77777777-7777-4777-8777-777777777777", null, "NOT_AUTHORIZED"),
                204, "7732eeb6708be19ab22f37ea78947aea993e5e746c3a33f01a305e6a0558b688");
        assertRejected(new AgentRuntimeV2RegistrationCodec.Rejected("RECONNECT",
                        "99999999-9999-4999-8999-999999999999", null, "UNAVAILABLE"),
                201, "0c1d816a214a90e059aa0e1c7debb67c3a853814a8e6780a4fa6905c7bd43b5b");

        byte[] valid = AgentRuntimeV2RegistrationCodec.encodeReceiptDelivery(
                delivery(JSON[1], "ADVANCED", "CURRENT"));
        for (byte[] malformed : List.of(
                (" " + new String(valid, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                (new String(valid, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8),
                new String(valid, StandardCharsets.UTF_8).replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\"")
                        .getBytes(StandardCharsets.UTF_8),
                new String(valid, StandardCharsets.UTF_8).replace("\"receiptEncoding\":\"base64\"", "\"receiptEncoding\":\"BASE64\"")
                        .getBytes(StandardCharsets.UTF_8),
                new String(valid, StandardCharsets.UTF_8).replace("\"receiptSha256\":", "\"unknown\":1,\"receiptSha256\":")
                        .getBytes(StandardCharsets.UTF_8))) {
            assertThrows(IllegalArgumentException.class,
                    () -> AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(malformed));
        }
        String validText = new String(valid, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(
                validText.replaceFirst("(\\\"receiptBytesBase64\\\":\\\"[^\\\"]*)=(\\\",\\\"receiptSha256\\\")", "$1$2")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(
                validText.replace("\"correlationType\":\"CREATE\"", "\"correlationType\":\"RECONNECT\"")
                        .getBytes(StandardCharsets.UTF_8)));

        byte[] rejected = AgentRuntimeV2RegistrationCodec.encodeRejected(
                new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",
                        "11111111-1111-4111-8111-111111111111", null, "NOT_AUTHORIZED"));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeRejected(
                (new String(rejected, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeV2RegistrationCodec.decodeRejected(
                new String(rejected, StandardCharsets.UTF_8).replace("\"reasonCode\":", "\"reasonCodeAlias\":")
                        .getBytes(StandardCharsets.UTF_8)));
        for (String correlationType : List.of("CREATE", "CHALLENGE", "RECONNECT")) {
            String correlationId = switch (correlationType) {
                case "CREATE" -> "11111111-1111-4111-8111-111111111111";
                case "CHALLENGE" -> "77777777-7777-4777-8777-777777777777";
                default -> "99999999-9999-4999-8999-999999999999";
            };
            var nullableBinding = new AgentRuntimeV2RegistrationCodec.Rejected(
                    correlationType, correlationId, null, "NOT_AUTHORIZED");
            byte[] nullableWire = AgentRuntimeV2RegistrationCodec.encodeRejected(nullableBinding);
            assertNull(AgentRuntimeV2RegistrationCodec.decodeRejected(nullableWire).runtimeBindingId());
        }
    }

    @Test
    void bothOuterDecodersRejectDuplicateMissingReorderedBomUtf8UuidEnumsAndNull() {
        String deliveryText=new String(AgentRuntimeV2RegistrationCodec.encodeReceiptDelivery(delivery(JSON[1],"ADVANCED","CURRENT")),StandardCharsets.UTF_8);
        String rejectedText=new String(AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE","11111111-1111-4111-8111-111111111111",null,"NOT_AUTHORIZED")),StandardCharsets.UTF_8);
        for(String text:List.of(deliveryText,rejectedText)) {
            java.util.function.Consumer<byte[]> decoder=text==deliveryText?wire->AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(wire):wire->AgentRuntimeV2RegistrationCodec.decodeRejected(wire);
            String messageType=text==deliveryText?"runtime.registration.receipt.delivery":"runtime.registration.rejected";
            List<byte[]> malformed=new java.util.ArrayList<>();
            malformed.add(bytes(text.replace("\"schemaVersion\":2,","\"schemaVersion\":2,\"schemaVersion\":2,")));
            malformed.add(bytes(text.replace("\"schemaVersion\":2,","")));
            malformed.add(bytes(text.replace("\"schemaVersion\":2,\"messageType\":\""+messageType+"\"","\"messageType\":\""+messageType+"\",\"schemaVersion\":2")));
            malformed.add(bytes("\ufeff"+text));
            byte[] invalidUtf8=bytes(text);invalidUtf8[invalidUtf8.length-2]=(byte)0xff;malformed.add(invalidUtf8);
            malformed.add(bytes(text.replace("11111111-1111-4111-8111-111111111111","11111111-1111-1111-8111-111111111111")));
            malformed.add(bytes(text.replace("\"correlationType\":\"CREATE\"","\"correlationType\":\"UNKNOWN\"")));
            malformed.add(bytes(text.replace("\"correlationId\":\"11111111-1111-4111-8111-111111111111\"","\"correlationId\":null")));
            for(byte[] wire:malformed)assertThrows(IllegalArgumentException.class,()->decoder.accept(wire));
        }
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(bytes(deliveryText.replace("\"deliveryResult\":\"ADVANCED\"","\"deliveryResult\":\"INVALID\""))));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(bytes(deliveryText.replace("\"receiptSha256\":\""+JSON_HASH[1]+"\"","\"receiptSha256\":\""+"0".repeat(64)+"\""))));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(bytes(deliveryText.replace("\"correlationId\":\"11111111-1111-4111-8111-111111111111\"","\"correlationId\":\"99999999-9999-4999-8999-999999999999\""))));
        byte[] noncanonical=bytes(" "+JSON[1]);
        String matchingHashNoncanonical=deliveryText.replace(Base64.getEncoder().encodeToString(bytes(JSON[1])),Base64.getEncoder().encodeToString(noncanonical)).replace(JSON_HASH[1],HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(noncanonical)));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(bytes(matchingHashNoncanonical)));
        assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2RegistrationCodec.decodeRejected(bytes(rejectedText.replace("\"reasonCode\":\"NOT_AUTHORIZED\"","\"reasonCode\":\"UNKNOWN\""))));
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
    private static AgentRuntimeV2RegistrationCodec.ReceiptDelivery delivery(
            String receiptJson, String result, String disposition) {
        byte[] receiptBytes = bytes(receiptJson);
        AgentRuntimeV2RegistrationCodec.Receipt receipt =
                AgentRuntimeV2RegistrationCodec.decodeReceipt(receiptBytes);
        return new AgentRuntimeV2RegistrationCodec.ReceiptDelivery(result, receipt.receiptKind(),
                receipt.registrationRequestId(), disposition, receiptBytes,
                AgentRuntimeV2RegistrationFingerprints.sha256(receiptBytes));
    }
    private static void assertDelivery(String receiptJson, String result, String disposition,
            int expectedLength, String expectedSha256) {
        byte[] wire = AgentRuntimeV2RegistrationCodec.encodeReceiptDelivery(
                delivery(receiptJson, result, disposition));
        assertEquals(expectedLength, wire.length);
        assertEquals(expectedSha256,
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(wire)));
        assertArrayEquals(wire, AgentRuntimeV2RegistrationCodec.encodeReceiptDelivery(
                AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(wire)));
    }
    private static void assertRejected(AgentRuntimeV2RegistrationCodec.Rejected value,
            int expectedLength, String expectedSha256) {
        byte[] wire = AgentRuntimeV2RegistrationCodec.encodeRejected(value);
        assertEquals(expectedLength, wire.length);
        assertEquals(expectedSha256,
                HEX.formatHex(AgentRuntimeV2RegistrationFingerprints.sha256(wire)));
        assertArrayEquals(wire, AgentRuntimeV2RegistrationCodec.encodeRejected(
                AgentRuntimeV2RegistrationCodec.decodeRejected(wire)));
    }
}
