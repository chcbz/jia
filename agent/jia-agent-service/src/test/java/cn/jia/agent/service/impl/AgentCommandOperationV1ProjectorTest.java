package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentCommandOperationStatusRow;
import cn.jia.agent.entity.AgentCommandOperationV1View;
import cn.jia.agent.entity.AgentCommandOperationsException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandOperationV1ProjectorTest {
    private static final long SUBMITTED = 1_700_000_000_000L;
    private static final long FINISHED = SUBMITTED + 500L;
    private static final String OPERATION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SOURCE_MESSAGE = "11111111-1111-1111-1111-111111111111";
    private static final String NEW_MESSAGE = "22222222-2222-2222-2222-222222222222";
    private final AgentCommandOperationV1Projector projector =
            new AgentCommandOperationV1Projector();

    @Test
    void requestOnlyProjectsAcceptedVersionZeroWithoutInventingExecutionState() {
        AgentCommandOperationV1View view = project(List.of(request("BROKER_REDRIVE", 1)));

        assertEquals("ACCEPTED", view.status());
        assertEquals("0", view.version());
        assertEquals(SUBMITTED, view.submittedAt());
        assertNull(view.startedAt());
        assertNull(view.finishedAt());
        assertNull(view.result());
        assertNull(view.error());
        assertFalse(view.retryable());
        assertEquals("/agent/internal/command-operations/v1/operations/" + OPERATION,
                view.statusUrl());
    }

    @Test
    void successProjectsOnlyBoundedTransportReceiptAndMonotonicVersionOne() {
        AgentCommandOperationV1View broker = project(List.of(
                request("BROKER_REDRIVE", 1),
                result("BROKER_REDRIVE", "SUCCEEDED", null, null, null)));
        assertEquals("SUCCEEDED", broker.status());
        assertEquals("1", broker.version());
        assertEquals("7", broker.result().deliveryId());
        assertEquals(SOURCE_MESSAGE, broker.result().messageId());
        assertEquals(1, broker.result().attempt());
        assertNull(broker.error());

        AgentCommandOperationV1View manual = project(List.of(
                request("MANUAL_REISSUE", 1),
                result("MANUAL_REISSUE", "SUCCEEDED", null, NEW_MESSAGE, 2)));
        assertEquals(NEW_MESSAGE, manual.result().messageId());
        assertEquals(2, manual.result().attempt());
    }

    @Test
    void failuresUseFinitePublicErrorsAndNeverExposeUnknownRawCodes() {
        AgentCommandOperationV1View publish = project(List.of(
                request("BROKER_REDRIVE", 1),
                result("BROKER_REDRIVE", "FAILED", "RABBIT_NACK", null, null)));
        assertEquals("FAILED", publish.status());
        assertEquals("COMMAND_OPERATION_PUBLISH_FAILED", publish.error().code());
        assertTrue(publish.error().retryable());
        assertTrue(publish.retryable());

        AgentCommandOperationV1View unknown = project(List.of(
                request("BROKER_REDRIVE", 1),
                result("BROKER_REDRIVE", "FAILED", "VENDOR_PRIVATE_DETAIL", null, null)));
        assertEquals("COMMAND_OPERATION_FAILED", unknown.error().code());
        assertEquals("Command operation failed", unknown.error().message());
        assertFalse(unknown.error().retryable());
    }

    @Test
    void terminalRetentionIsInclusiveAndOlderRowsBecomeNonEnumeratingNotFound() {
        project(List.of(request("BROKER_REDRIVE", 1),
                result("BROKER_REDRIVE", "SUCCEEDED", null, null, null)),
                FINISHED + AgentCommandOperationV1Projector.TERMINAL_RETENTION_MILLIS);

        AgentCommandOperationsException expired = assertThrows(
                AgentCommandOperationsException.class,
                () -> project(List.of(request("BROKER_REDRIVE", 1),
                        result("BROKER_REDRIVE", "SUCCEEDED", null, null, null)),
                        FINISHED + AgentCommandOperationV1Projector.TERMINAL_RETENTION_MILLIS + 1));
        assertEquals(AgentCommandOperationsException.Reason.NOT_FOUND_OR_FORBIDDEN,
                expired.reason());
    }

    @Test
    void mismatchedScopeDuplicateOrNonMonotonicRowsFailClosed() {
        AgentCommandOperationStatusRow wrongRequester = new AgentCommandOperationStatusRow(
                1, OPERATION, "REQUEST", "BROKER_REDRIVE", 7, SOURCE_MESSAGE,
                null, 1, null, "operator-b", SUBMITTED, null, "REQUESTED", null,
                SUBMITTED, "tenant-a", "client-a");
        assertConflict(List.of(wrongRequester));

        AgentCommandOperationStatusRow staleResult = new AgentCommandOperationStatusRow(
                1, OPERATION, "RESULT", "BROKER_REDRIVE", 7, SOURCE_MESSAGE,
                null, 1, null, "operator-a", SUBMITTED, FINISHED, "SUCCEEDED", null,
                FINISHED, "tenant-a", "client-a");
        assertConflict(List.of(request("BROKER_REDRIVE", 1), staleResult));

        assertConflict(List.of(request("BROKER_REDRIVE", 1),
                result("BROKER_REDRIVE", "SUCCEEDED", null, null, null),
                result("BROKER_REDRIVE", "FAILED", "RABBIT_NACK", null, null)));
    }

    private AgentCommandOperationV1View project(List<AgentCommandOperationStatusRow> rows) {
        return project(rows, FINISHED);
    }

    private AgentCommandOperationV1View project(
            List<AgentCommandOperationStatusRow> rows, long now) {
        return projector.project(rows, "tenant-a", "client-a", "operator-a",
                OPERATION, now);
    }

    private void assertConflict(List<AgentCommandOperationStatusRow> rows) {
        AgentCommandOperationsException failure = assertThrows(
                AgentCommandOperationsException.class, () -> project(rows));
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                failure.reason());
    }

    private AgentCommandOperationStatusRow request(String type, Integer sourceAttempt) {
        return new AgentCommandOperationStatusRow(
                1, OPERATION, "REQUEST", type, 7, SOURCE_MESSAGE, null,
                sourceAttempt, null, "operator-a", SUBMITTED, null, "REQUESTED", null,
                SUBMITTED, "tenant-a", "client-a");
    }

    private AgentCommandOperationStatusRow result(
            String type,
            String outcome,
            String errorCode,
            String newMessageId,
            Integer newAttempt) {
        return new AgentCommandOperationStatusRow(
                2, OPERATION, "RESULT", type, 7, SOURCE_MESSAGE, newMessageId,
                1, newAttempt, "operator-a", SUBMITTED, FINISHED, outcome, errorCode,
                FINISHED, "tenant-a", "client-a");
    }
}
