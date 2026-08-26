package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandRedriveOperationEntity;
import cn.jia.agent.entity.AgentCommandRedriveOutcomeState;
import cn.jia.agent.entity.AgentCommandRedriveSettlementState;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandBrokerRedrivePolicyTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long ISSUED = NOW - 1_000L;
    private static final long EXPIRES = ISSUED + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS;
    private static final String MESSAGE = "11111111-1111-1111-1111-111111111111";
    private static final String PARENT = "22222222-2222-2222-2222-222222222222";
    private static final String COMMAND = AgentCommandCanonicalCodec.hallCommandId(
            "tenant-a", "client-a", "task-1", "agent-a", "intent-1",
            AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
    private final AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
    private final AgentCommandBrokerRedrivePolicy policy = new AgentCommandBrokerRedrivePolicy();

    @Test
    void exactAttemptOneSourceIsLegalAndResultOwnsDefensiveBytes() {
        Fixture fixture = fixture();

        var evaluation = evaluate(fixture);

        assertTrue(evaluation.legal());
        byte[] first = evaluation.legalSource().orElseThrow().wirePayload();
        first[0] ^= 1;
        assertFalse(java.util.Arrays.equals(first,
                evaluation.legalSource().orElseThrow().wirePayload()));
    }

    @Test
    void duplicateOrDifferentActiveAndCurrentRowsFailClosed() {
        Fixture duplicate = fixture();
        duplicate.active = List.of(duplicate.outbox, duplicate.outbox);
        assertFalse(evaluate(duplicate).legal());

        Fixture different = fixture();
        AgentOutboxEventEntity other = copyOutbox(different.outbox).setId(99L);
        different.current = List.of(other);
        assertFalse(evaluate(different).legal());
    }

    @Test
    void routeTypeAndExactIdentityDriftFailClosed() {
        Fixture route = fixture();
        route.outbox.setRoutingKey("agent.command.poison");
        assertFalse(evaluate(route).legal());

        Fixture type = fixture();
        type.outbox.setAggregateType("saga");
        assertFalse(evaluate(type).legal());

        Fixture identity = fixture();
        identity.outbox.setCommandId("different-command");
        assertFalse(evaluate(identity).legal());
    }

    @Test
    void businessAndWireBytesOrHashesFailClosed() {
        Fixture businessBytes = fixture();
        byte[] alteredBusiness = businessBytes.delivery.getCommandPayload();
        alteredBusiness[0] ^= 1;
        businessBytes.delivery.setCommandPayload(alteredBusiness)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(alteredBusiness));
        assertFalse(evaluate(businessBytes).legal());

        Fixture businessHash = fixture();
        businessHash.delivery.getCommandPayloadHash()[0] ^= 1;
        assertFalse(evaluate(businessHash).legal());

        Fixture wireBytes = fixture();
        byte[] alteredWire = wireBytes.outbox.getWirePayload();
        alteredWire[0] ^= 1;
        wireBytes.outbox.setWirePayload(alteredWire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(alteredWire));
        assertFalse(evaluate(wireBytes).legal());

        Fixture wireHash = fixture();
        wireHash.outbox.getWirePayloadHash()[0] ^= 1;
        assertFalse(evaluate(wireHash).legal());
    }

    @Test
    void attemptGreaterThanOneRequiresExactImmediateParent() {
        Fixture replay = replayFixture();
        assertTrue(evaluate(replay).legal());

        replay.previous = List.of();
        assertFalse(evaluate(replay).legal());

        replay = replayFixture();
        replay.previous.getFirst().setMessageId("not-the-parent");
        assertFalse(evaluate(replay).legal());
    }

    @Test
    void blockingRedriveRejectsDiscoveryButAllowsOnlyExactOwnPendingOperation() {
        Fixture blocked = fixture();
        AgentCommandRedriveOperationEntity pending = pending(blocked, "operation-own");
        blocked.blockers = List.of(pending);
        assertFalse(evaluate(blocked).legal());
        assertTrue(evaluate(blocked, "operation-own").legal());

        pending.setOutcomeState(AgentCommandRedriveOutcomeState.SUCCEEDED)
                .setSettlementState(AgentCommandRedriveSettlementState.SOURCE_ACKED);
        assertFalse(evaluate(blocked, "operation-own").legal());

        Fixture duplicate = fixture();
        duplicate.blockers = List.of(pending(duplicate, "operation-own"),
                pending(duplicate, "operation-own"));
        assertFalse(evaluate(duplicate, "operation-own").legal());
    }

    private AgentCommandBrokerRedrivePolicy.Evaluation evaluate(Fixture fixture) {
        return evaluate(fixture, null);
    }

    private AgentCommandBrokerRedrivePolicy.Evaluation evaluate(
            Fixture fixture, String ownOperationId) {
        return policy.evaluate(new AgentCommandBrokerRedrivePolicy.SourceBundle(
                        "tenant-a", "client-a", 1L, "task-1", "agent-a", MESSAGE,
                        fixture.delivery, fixture.active, fixture.current, fixture.previous,
                        null, fixture.blockers),
                NOW, manifest.defaultCommandPublishRoute(), manifest.sha256(), ownOperationId);
    }

    private Fixture fixture() {
        AgentCommandDraft draft = draft();
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, MESSAGE, 1);
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(1L).setCommandId(COMMAND).setTaskId("task-1").setWorkItemId("work-1")
                .setTargetAgentId("agent-a")
                .setCommandType(AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE)
                .setCommandPayload(business)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveMessageId(MESSAGE)
                .setActiveAttempt(1).setExpiresAt(EXPIRES).setVersion(1L);
        delivery.setTenantId("tenant-a"); delivery.setClientId("client-a");
        delivery.setCreateTime(NOW - 20); delivery.setUpdateTime(NOW - 5);
        AgentOutboxEventEntity outbox = outbox(
                2L, "event-1", MESSAGE, wire, 1, null, null, null, null);
        return new Fixture(delivery, outbox, List.of(outbox), List.of(outbox),
                List.of(), List.of());
    }

    private Fixture replayFixture() {
        Fixture fixture = fixture();
        byte[] activeWire = AgentCommandCanonicalCodec.wireBytes(draft(), MESSAGE, 2);
        fixture.delivery.setActiveAttempt(2).setAttemptCount(2)
                .setReplayParentMessageId(PARENT).setReplayRequesterId("operator-a")
                .setReplayApproverId("approver-b").setReplayReason("manual recovery");
        fixture.outbox.setActiveAttempt(2).setWirePayload(activeWire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(activeWire))
                .setReplayParentMessageId(PARENT).setReplayRequesterId("operator-a")
                .setReplayApproverId("approver-b").setReplayReason("manual recovery");
        byte[] parentWire = AgentCommandCanonicalCodec.wireBytes(draft(), PARENT, 1);
        AgentOutboxEventEntity parent = outbox(
                1L, "event-parent", PARENT, parentWire, 1, null, null, null, null);
        fixture.previous = List.of(parent);
        return fixture;
    }

    private AgentOutboxEventEntity outbox(
            long id, String eventId, String messageId, byte[] wire, int activeAttempt,
            String parent, String requester, String approver, String reason) {
        var route = manifest.defaultCommandPublishRoute();
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setId(id).setEventId(eventId).setMessageId(messageId).setCommandId(COMMAND)
                .setDeliveryId(1L).setAggregateType("task").setAggregateId("task-1")
                .setDestination(route.destination()).setRoutingKey(route.routingKey())
                .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(activeAttempt)
                .setExpiresAt(EXPIRES).setPublisherConfirmStatus("ACK").setConfirmedAt(NOW - 10)
                .setMandatoryReturnStatus("NOT_RETURNED").setPublishedAt(NOW - 9)
                .setVersion(1L).setReplayParentMessageId(parent).setReplayRequesterId(requester)
                .setReplayApproverId(approver).setReplayReason(reason);
        outbox.setTenantId("tenant-a"); outbox.setClientId("client-a");
        outbox.setCreateTime(NOW - 20); outbox.setUpdateTime(NOW - 4);
        return outbox;
    }

    private AgentOutboxEventEntity copyOutbox(AgentOutboxEventEntity source) {
        AgentOutboxEventEntity copy = outbox(
                source.getId(), source.getEventId(), source.getMessageId(),
                source.getWirePayload(), source.getActiveAttempt(),
                source.getReplayParentMessageId(), source.getReplayRequesterId(),
                source.getReplayApproverId(), source.getReplayReason());
        return copy.setAttemptCount(source.getAttemptCount());
    }

    private AgentCommandRedriveOperationEntity pending(Fixture fixture, String operationId) {
        AgentCommandRedriveOperationEntity operation = new AgentCommandRedriveOperationEntity()
                .setId(7L).setOperationId(operationId).setDeliveryId(1L).setTaskId("task-1")
                .setTargetAgentId("agent-a").setCommandId(COMMAND)
                .setSourceEventId(fixture.outbox.getEventId()).setSourceMessageId(MESSAGE)
                .setSourceAttempt(fixture.delivery.getActiveAttempt())
                .setWireHash(fixture.outbox.getWirePayloadHash()).setRequesterId("operator-a")
                .setReason("incident recovery").setTicketReference("INC-42")
                .setOutcomeState(AgentCommandRedriveOutcomeState.PENDING)
                .setSettlementState(AgentCommandRedriveSettlementState.PENDING)
                .setRequestedAt(NOW - 100).setVersion(0L)
                .setDispositionGuard(1).setRedriveGuard(1);
        operation.setTenantId("tenant-a"); operation.setClientId("client-a");
        return operation;
    }

    private AgentCommandDraft draft() {
        return new AgentCommandDraft(
                1, COMMAND, "task-1", "intent-1", "tenant-a", "client-a", "task-1",
                "work-1", "agent-a", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                ISSUED, EXPIRES, "intent-1", new AgentHallCommandPayload(
                        "execute", "Execute bounded work", "juyiting",
                        null, null, null, null, false, null));
    }

    private static final class Fixture {
        private final AgentCommandDeliveryEntity delivery;
        private final AgentOutboxEventEntity outbox;
        private List<AgentOutboxEventEntity> active;
        private List<AgentOutboxEventEntity> current;
        private List<AgentOutboxEventEntity> previous;
        private List<AgentCommandRedriveOperationEntity> blockers;

        private Fixture(
                AgentCommandDeliveryEntity delivery,
                AgentOutboxEventEntity outbox,
                List<AgentOutboxEventEntity> active,
                List<AgentOutboxEventEntity> current,
                List<AgentOutboxEventEntity> previous,
                List<AgentCommandRedriveOperationEntity> blockers) {
            this.delivery = delivery;
            this.outbox = outbox;
            this.active = active;
            this.current = current;
            this.previous = previous;
            this.blockers = blockers;
        }
    }
}
