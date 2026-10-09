package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandV2AckReceiptEntity;
import cn.jia.agent.entity.AgentCommandV2ExecutionGrantEntity;
import cn.jia.agent.entity.AgentRuntimeV2WireCodec;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2WirePersistenceTest {
    private static final String RECEIPT = "00000000-0000-4000-8000-000000000001";
    private static final String DISPATCH = "00000000-0000-4000-8000-000000000002";
    private static final String FACT = "00000000-0000-4000-8000-000000000003";
    private static final String GRANT = "00000000-0000-4000-8000-000000000004";
    private static final String BINDING = "00000000-0000-4000-8000-000000000005";

    @Test
    void receiptGoldenPreservesQuotesBackslashesUnicodeAndLongMax() {
        AgentRuntimeV2WireCodec.Receipt value = new AgentRuntimeV2WireCodec.Receipt(
                RECEIPT, DISPATCH, "a\"\\雪", "源/二", "STARTED",
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0, FACT);
        byte[] wire = AgentRuntimeV2WireCodec.encodeReceipt(value);
        assertEquals("{\"schemaVersion\":2,\"messageType\":\"command.ack.receipt\","
                + "\"receiptId\":\"" + RECEIPT + "\",\"runtimeDispatchId\":\"" + DISPATCH + "\","
                + "\"ackMessageId\":\"a\\\"\\\\雪\",\"sourceMessageId\":\"源/二\","
                + "\"ackStatus\":\"STARTED\",\"ackAt\":\"9223372036854775807\","
                + "\"committedAt\":\"9223372036854775807\","
                + "\"postcommitDeliveryVersion\":\"9223372036854775807\","
                + "\"postcommitInboxVersion\":\"0\",\"leaseFactId\":\"" + FACT + "\","
                + "\"commitResult\":\"ADVANCED\"}", new String(wire, StandardCharsets.UTF_8));
        assertEquals("9e7bd5900d1822de41c3ac74310ff96af92428ac7353f6e78f0ab788b0877584",
                HexFormat.of().formatHex(AgentRuntimeV2WireCodec.sha256(wire)));
        assertEquals(value, AgentRuntimeV2WireCodec.decodeReceipt(wire));
    }

    @Test
    void parserRejectsUnknownOrderAliasesWhitespaceAndNoncanonicalDecimals() {
        byte[] canonical = AgentRuntimeV2WireCodec.encodeReceipt(receipt());
        String json = new String(canonical, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> AgentRuntimeV2WireCodec.decodeReceipt((" " + json).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRuntimeV2WireCodec.decodeReceipt(json.replace("\"ackAt\":\"7\"", "\"ackAt\":\"07\"")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRuntimeV2WireCodec.decodeReceipt(json.replace("\"commitResult\"", "\"extra\":\"x\",\"commitResult\"")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRuntimeV2WireCodec.decodeReceipt(json.replace("\"receiptId\"", "\"runtimeDispatchIdX\"")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rowVerificationRejectsHashCorrectFieldMismatchAndV1MissingWire() {
        AgentRuntimeV2WireCodec.Receipt value = receipt();
        byte[] wire = AgentRuntimeV2WireCodec.encodeReceipt(value);
        AgentCommandV2AckReceiptEntity row = new AgentCommandV2AckReceiptEntity()
                .setReceiptId(value.receiptId()).setRuntimeDispatchId(value.runtimeDispatchId())
                .setAckMessageId(value.ackMessageId()).setSourceMessageId(value.sourceMessageId())
                .setStatus(value.ackStatus()).setAckAt(value.ackAt()).setCommittedAt(value.committedAt())
                .setPostcommitDeliveryVersion(value.postcommitDeliveryVersion())
                .setPostcommitInboxVersion(value.postcommitInboxVersion()).setLeaseFactId(value.leaseFactId())
                .setReceiptWireBytes(wire).setReceiptWireSha256(AgentRuntimeV2WireCodec.sha256(wire))
                .setReceiptWireContractVersion(2);
        assertDoesNotThrow(() -> AgentRuntimeV2WireCodec.verifyReceiptRow(row));
        row.setAckMessageId("different");
        assertThrows(IllegalStateException.class, () -> AgentRuntimeV2WireCodec.verifyReceiptRow(row));
        row.setReceiptWireContractVersion(1).setReceiptWireBytes(null).setReceiptWireSha256(null);
        assertThrows(IllegalStateException.class, () -> AgentRuntimeV2WireCodec.verifyReceiptRow(row));
    }

    @Test
    void grantRoundTripHasSeventeenFieldsAndRowReferencesAreVerified() {
        byte[] startedHash = AgentRuntimeV2WireCodec.sha256(AgentRuntimeV2WireCodec.encodeReceipt(receipt()));
        AgentRuntimeV2WireCodec.ExecutionGrant value = new AgentRuntimeV2WireCodec.ExecutionGrant(
                GRANT, "CONTINUATION", DISPATCH, RECEIPT, startedHash, BINDING, "运行\\\"实例",
                7, 11, FACT, "00000000-0000-4000-8000-000000000006", 3, Long.MAX_VALUE,
                "00000000-0000-4000-8000-000000000007", 8);
        byte[] wire = AgentRuntimeV2WireCodec.encodeExecutionGrant(value);
        assertEquals(17, new String(wire, StandardCharsets.UTF_8).split("\":").length - 1);
        AgentRuntimeV2WireCodec.ExecutionGrant decoded =
                AgentRuntimeV2WireCodec.decodeExecutionGrant(wire);
        assertEquals(value.executionGrantId(), decoded.executionGrantId());
        assertEquals(value.grantKind(), decoded.grantKind());
        assertEquals(value.runtimeDispatchId(), decoded.runtimeDispatchId());
        assertEquals(value.startedReceiptId(), decoded.startedReceiptId());
        assertArrayEquals(value.startedReceiptSha256(), decoded.startedReceiptSha256());
        assertEquals(value.runtimeBindingId(), decoded.runtimeBindingId());
        assertEquals(value.runtimeInstanceId(), decoded.runtimeInstanceId());
        assertEquals(value.dispatchBindingGeneration(), decoded.dispatchBindingGeneration());
        assertEquals(value.holderBindingGeneration(), decoded.holderBindingGeneration());
        assertEquals(value.originalStartLeaseFactId(), decoded.originalStartLeaseFactId());
        assertEquals(value.currentLeaseFactId(), decoded.currentLeaseFactId());
        assertEquals(value.leaseRevision(), decoded.leaseRevision());
        assertEquals(value.leaseUntil(), decoded.leaseUntil());
        assertEquals(value.previousExecutionGrantId(), decoded.previousExecutionGrantId());
        assertEquals(value.issuedAt(), decoded.issuedAt());
        AgentCommandV2ExecutionGrantEntity row = new AgentCommandV2ExecutionGrantEntity()
                .setExecutionGrantId(value.executionGrantId()).setGrantKind(value.grantKind())
                .setRuntimeDispatchId(value.runtimeDispatchId()).setStartedReceiptId(value.startedReceiptId())
                .setStartedReceiptSha256(value.startedReceiptSha256()).setRuntimeBindingId(value.runtimeBindingId())
                .setRuntimeInstanceId(value.runtimeInstanceId()).setDispatchBindingGeneration(value.dispatchBindingGeneration())
                .setHolderBindingGeneration(value.holderBindingGeneration()).setOriginalStartLeaseFactId(value.originalStartLeaseFactId())
                .setCurrentLeaseFactId(value.currentLeaseFactId()).setLeaseRevision(value.leaseRevision())
                .setLeaseUntil(value.leaseUntil()).setPreviousExecutionGrantId(value.previousExecutionGrantId())
                .setIssuedAt(value.issuedAt()).setGrantWireBytes(wire)
                .setGrantWireSha256(AgentRuntimeV2WireCodec.sha256(wire)).setGrantWireContractVersion(2);
        assertDoesNotThrow(() -> AgentRuntimeV2WireCodec.verifyGrantRow(row));
        row.setCurrentLeaseFactId(FACT);
        assertThrows(IllegalStateException.class, () -> AgentRuntimeV2WireCodec.verifyGrantRow(row));
    }

    @Test
    void fingerprintsAndUtf8LimitsAreExact() {
        assertEquals("76090be030938f03aabf519bc8222bd74ab9e6af084d0de8293a6906b28f5e10",
                HexFormat.of().formatHex(AgentRuntimeV2WireCodec.ackRequestFingerprint(
                        "ack-a", DISPATCH, "source-a", "STARTED", 1_800_000_000_000L)));
        String requestId = "00000000-0000-4000-8000-000000000008";
        byte[] receiptHash = AgentRuntimeV2WireCodec.sha256(AgentRuntimeV2WireCodec.encodeReceipt(receipt()));
        AgentRuntimeV2WireCodec.ContinuationRequest request = new AgentRuntimeV2WireCodec.ContinuationRequest(
                requestId, DISPATCH, RECEIPT, receiptHash);
        assertArrayEquals(AgentRuntimeV2WireCodec.sha256(AgentRuntimeV2WireCodec.encodeContinuationRequest(request)),
                AgentRuntimeV2WireCodec.continuationRequestFingerprint(requestId, DISPATCH, RECEIPT, receiptHash));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeReceiverScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "界".repeat(17),
                "owner", "agent", "claimant", 1, 1, 1, "agent-command", 1));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeReceiverScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "\uD83D",
                "owner", "agent", "claimant", 1, 1, 1, "agent-command", 1));
    }

    @Test
    void callerAndCredentialClientsAndProducerAndClaimantDeploymentsMayDiffer() {
        CommandProducerScope producer = new CommandProducerScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "pool-a", "caller-c", "owner", "agent", "producer-a", 1, "agent-command", 2);
        RuntimeReceiverScope receiver = new RuntimeReceiverScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "pool-a", "credential-r", "owner", "agent", "claimant-b", 3, 4, 1, "agent-command", 2);
        assertNotEquals(producer.commandCallerClientId(), receiver.runtimeCredentialClientId());
        assertNotEquals(producer.producerDeploymentId(), receiver.claimantDeploymentId());
    }

    private static AgentRuntimeV2WireCodec.Receipt receipt() {
        return new AgentRuntimeV2WireCodec.Receipt(RECEIPT, DISPATCH, "ack-a", "source-a",
                "STARTED", 7, 8, 9, 10, FACT);
    }
}
