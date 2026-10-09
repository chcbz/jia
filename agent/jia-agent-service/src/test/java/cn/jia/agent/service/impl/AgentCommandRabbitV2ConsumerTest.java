package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentInboxResult;
import cn.jia.agent.entity.AgentInboxSourceNotSettledException;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandInboxV2Service;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentCommandRabbitV2ConsumerTest {
    private static final long NOW = 1_000_000L;
    private static final WorkerScope SCOPE = new WorkerScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "local-test", "consumer-a", 3, 5,
            7, "agent-command", 9);

    private final AgentRabbitV2TopologyManifest manifest =
            AgentRabbitV2TopologyManifest.forScope(SCOPE);
    private AgentCommandInboxV2Service inbox;
    private AgentCommandV2ExecutionPort port;
    private AgentConfirmedRabbitPublisher publisher;
    private Channel channel;
    private AgentCommandRabbitV2Consumer consumer;
    private List<Long> parkBackoffs;

    @BeforeEach
    void setUp() {
        port = mock(AgentCommandV2ExecutionPort.class);
        inbox = mock(AgentCommandInboxV2Service.class, invocation -> {
            if ("executeAdmitted".equals(invocation.getMethod().getName())) {
                return port.execute(new AgentCommandV2ExecutionPort.Command(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        publisher = mock(AgentConfirmedRabbitPublisher.class);
        channel = mock(Channel.class);
        parkBackoffs = new ArrayList<>();
        consumer = new AgentCommandRabbitV2Consumer(
                inbox, port, publisher, SCOPE, manifest, 5_000L,
                () -> NOW, "lease-a", parkBackoffs::add);
    }

    @Test
    void durableHandoffAcknowledgesDirectlyAndNeverCompetesOldCompleteVersion() throws Exception {
        when(port.durableHandoff()).thenReturn(true);when(inbox.claim(any(),any(),anyLong(),anyLong())).thenReturn(AgentInboxClaim.acquired(token()));when(port.execute(any())).thenReturn(AgentCommandV2ExecutionPort.Result.dispatched());doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isSynchronizationActive());assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());return null;}).when(channel).basicAck(71L,false);consumer.consume(message(),channel);verify(inbox,never()).complete(any(),any(),anyLong());verify(channel).basicAck(71L,false);verifyNoInteractions(publisher);
    }

    @Test
    void durableRedeliveryVerifiesUniqueOriginalHandoffBeforeRabbitAckAndDropsCorruption() throws Exception {
        when(port.durableHandoff()).thenReturn(true);when(inbox.claim(any(),any(),anyLong(),anyLong())).thenReturn(AgentInboxClaim.priorResult(result("PROCESSED","SENT")));consumer.consume(message(),channel);var order=inOrder(port,channel);order.verify(port).verifyPrior(11);order.verify(channel).basicAck(71L,false);verify(port,never()).execute(any());verify(inbox,never()).complete(any(),any(),anyLong());reset(channel);doThrow(new SecurityException("isolated corrupt origin")).when(port).verifyPrior(11);consumer.consume(message(),channel);verify(channel).basicNack(71L,false,false);verifyNoInteractions(publisher);
    }

    @Test
    void consumerRejectsAmbientResourcesBeforeAnyClaimOrNetworkIo() throws Exception {
        Object key=new Object();TransactionSynchronizationManager.bindResource(key,new Object());try{assertThrows(IllegalStateException.class,()->consumer.consume(message(),channel));verifyNoInteractions(inbox,port,publisher,channel);}finally{TransactionSynchronizationManager.unbindResource(key);}
    }

    @Test
    void validCommandClaimsExecutesCompletesThenAcknowledgesOutsideTransactions()
            throws Exception {
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), eq("lease-a"), eq(NOW),
                eq(AgentCommandRabbitV2Consumer.CLAIM_LEASE_MILLIS)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return AgentInboxClaim.acquired(token);
                });
        when(port.execute(any())).thenAnswer(invocation -> {
            AgentCommandV2ExecutionPort.Command command = invocation.getArgument(0);
            assertEquals(token, command.claimToken());
            assertEquals("owner-a", command.ownerJiacn());
            return AgentCommandV2ExecutionPort.Result.dispatched();
        });
        when(inbox.complete(eq(token), any(), eq(NOW)))
                .thenReturn(result("PROCESSED", "SENT"));
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(channel).basicAck(71L, false);

        consumer.consume(message(), channel);

        ArgumentCaptor<AgentInboxMessage> durable = ArgumentCaptor.forClass(AgentInboxMessage.class);
        verify(inbox).claim(durable.capture(), eq("lease-a"), eq(NOW),
                eq(AgentCommandRabbitV2Consumer.CLAIM_LEASE_MILLIS));
        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                durable.getValue().consumerName());
        assertEquals("consumer-a", durable.getValue().claimantDeploymentId());
        assertEquals(3, durable.getValue().claimantWorkerGeneration());
        InOrder order = inOrder(inbox, port, channel);
        order.verify(inbox).claim(any(), eq("lease-a"), eq(NOW), anyLong());
        order.verify(inbox).executeAdmitted(
                eq(token), eq("owner-a"), any(), eq(NOW), eq(port));
        order.verify(port).execute(any());
        order.verify(inbox).complete(eq(token), any(), eq(NOW));
        order.verify(channel).basicAck(71L, false);
    }

    @Test
    void malformedWrongScopeRouteAndDigestAreRejectedBeforeDatabase() throws Exception {
        Message malformed = message();
        malformed.getMessageProperties().getHeaders().remove(
                AgentCommandAmqpContract.HEADER_COMMAND_ID);
        Message wrongScope = message();
        wrongScope.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_TENANT_ID, "2");
        Message wrongRoute = message();
        wrongRoute.getMessageProperties().setReceivedRoutingKey("agent.command.shadow");
        Message wrongDigest = message();
        wrongDigest.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256, "0".repeat(64));

        for (Message invalid : new Message[]{malformed, wrongScope, wrongRoute, wrongDigest}) {
            consumer.consume(invalid, channel);
            verify(channel).basicNack(71L, false, false);
            verifyNoInteractions(inbox, port);
            reset(channel);
        }
    }

    @Test
    void durableIdentityConflictIsPermanentlyRejected() throws Exception {
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxIdentityConflictException("SOURCE_IDENTITY_CONFLICT"));

        consumer.consume(message(), channel);

        verify(channel).basicNack(71L, false, false);
        verifyNoInteractions(port);
    }

    @Test
    void duplicateFinalResultAcknowledgesWithoutExecutingAgain() throws Exception {
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.priorResult(result("PROCESSED", "SENT")));

        consumer.consume(message(), channel);

        verify(channel).basicAck(71L, false);
        verify(port).durableHandoff();
        verify(port,never()).execute(any());
        verify(port,never()).verifyPrior(anyLong());
        verify(inbox, never()).executeAdmitted(any(), any(), any(), anyLong(), any());
        verify(inbox, never()).complete(any(), any(), anyLong());
    }

    @Test
    void conflictingPriorResultDropsWhileInflightAndDisabledUseConfirmedScopedRetry()
            throws Exception {
        AgentInboxResult conflicting = new AgentInboxResult(11,
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2, "1", "client-a",
                "other-message", "evt-1", "cmd-1", 41,
                "PROCESSED", "SENT", NOW, null);
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.priorResult(conflicting));
        consumer.consume(message(), channel);
        verify(channel).basicNack(71L, false, false);
        verifyNoInteractions(publisher);

        reset(channel, inbox, publisher);
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(300_000));
        when(publisher.publish(any(), eq(5_000L)))
                .thenReturn(AgentRabbitPublishResult.ack());
        consumer.consume(message(), channel);
        assertScopedPark(capturedPublish(), manifest.retry5mRoutingKey(), 1);
        verify(channel).basicAck(71L, false);
        assertTrue(parkBackoffs.isEmpty());

        reset(channel, inbox, publisher);
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.disabled(
                        AgentInboxClaim.DisabledReason.RABBIT_CONSUME_DISABLED));
        when(publisher.publish(any(), eq(5_000L)))
                .thenReturn(AgentRabbitPublishResult.ack());
        consumer.consume(message(), channel);
        assertScopedPark(capturedPublish(), manifest.retry30sRoutingKey(), 1);
        verify(channel).basicAck(71L, false);
        verifyNoInteractions(port);
    }

    @Test
    void longParkAttemptsOnlyCeilingLaneThenBacksOffAndRequeuesOnTimeout()
            throws Exception {
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(300_000L));
        List<AgentConfirmedPublishRequest> attempts = new ArrayList<>();
        when(publisher.publish(any(), eq(5_000L))).thenAnswer(invocation -> {
            attempts.add(invocation.getArgument(0));
            return new AgentRabbitPublishResult(
                    AgentRabbitPublishResult.Type.TIMEOUT, "TIMEOUT", "NOT_RETURNED",
                    null, null, "PUBLISH_TIMEOUT");
        });
        doAnswer(invocation -> {
            assertEquals(1, attempts.size());
            assertEquals(List.of(AgentCommandRabbitV2Consumer.PARK_FAILURE_BACKOFF_MILLIS),
                    parkBackoffs);
            return null;
        }).when(channel).basicNack(71L, false, true);

        consumer.consume(message(), channel);

        assertEquals(1, attempts.size());
        assertScopedPark(attempts.get(0), manifest.retry5mRoutingKey(), 1);
        verify(publisher).publish(any(), eq(5_000L));
        verify(channel).basicNack(71L, false, true);
        verify(channel, never()).basicAck(anyLong(), eq(false));
    }

    @Test
    void sixtyAndThreeHundredSecondRetriesPersistBeyondLeaseAndRequireExactRetryTime()
            throws Exception {
        assertDurableRetry(60_000L);

        reset(channel, inbox, port, publisher);
        assertDurableRetry(300_000L);

        reset(channel, inbox, port, publisher);
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(port.execute(any()))
                .thenReturn(AgentCommandV2ExecutionPort.Result.retryable(60_000L));
        when(inbox.complete(eq(token), any(), eq(NOW))).thenReturn(
                result("RETRY", "RETRY",
                        AgentCommandRabbitV2Consumer.EXECUTION_RETRYABLE, NOW + 30_000L));
        when(publisher.publish(any(), eq(5_000L)))
                .thenReturn(AgentRabbitPublishResult.ack());

        consumer.consume(message(), channel);

        assertScopedPark(capturedPublish(), manifest.retry5mRoutingKey(), 1);
        InOrder order = inOrder(port, inbox, publisher, channel);
        order.verify(port).execute(any());
        order.verify(inbox).complete(eq(token), any(), eq(NOW));
        order.verify(publisher).publish(any(), eq(5_000L));
        order.verify(channel).basicAck(71L, false);
    }

    @Test
    void uncertainExecutionPersistsRetryBeforeAcknowledging() throws Exception {
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(port.execute(any())).thenThrow(new IllegalStateException("runtime unavailable"));
        when(inbox.complete(eq(token), any(), eq(NOW))).thenReturn(
                result("RETRY", "RETRY",
                        AgentCommandRabbitV2Consumer.EXECUTION_UNCERTAIN, NOW + 5_000L));
        when(publisher.publish(any(), eq(5_000L)))
                .thenReturn(AgentRabbitPublishResult.ack());

        consumer.consume(message(), channel);

        assertScopedPark(capturedPublish(), manifest.retry5sRoutingKey(), 1);
        verify(channel).basicAck(71L, false);
        verify(inbox).complete(eq(token), any(), eq(NOW));
        InOrder order = inOrder(inbox, publisher, channel);
        order.verify(inbox).complete(eq(token), any(), eq(NOW));
        order.verify(publisher).publish(any(), eq(5_000L));
        order.verify(channel).basicAck(71L, false);
    }

    @Test
    void executionRejectionIsDurablyFinalRatherThanBrokerRequeued() throws Exception {
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(port.execute(any())).thenReturn(AgentCommandV2ExecutionPort.Result.rejected());
        when(inbox.complete(eq(token), any(), eq(NOW)))
                .thenReturn(result("DEAD", "DEAD",
                        AgentCommandRabbitV2Consumer.EXECUTION_REJECTED, null));

        consumer.consume(message(), channel);

        verify(inbox).complete(eq(token), any(), eq(NOW));
        verify(channel).basicAck(71L, false);
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(true));
    }

    @Test
    void sourceRaceAndDatabaseFailuresUseConfirmedScopedRetryCopies() throws Exception {
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxSourceNotSettledException("OUTBOX_NOT_PUBLISHED"));
        when(publisher.publish(any(), eq(5_000L)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return AgentRabbitPublishResult.ack();
                });
        consumer.consume(message(), channel);
        assertScopedPark(capturedPublish(), manifest.retry5sRoutingKey(), 1);
        verify(channel).basicAck(71L, false);

        reset(channel, inbox, publisher);
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(publisher.publish(any(), eq(5_000L)))
                .thenReturn(AgentRabbitPublishResult.ack());
        consumer.consume(message(), channel);
        assertScopedPark(capturedPublish(), manifest.retry5sRoutingKey(), 1);
        verify(channel).basicAck(71L, false);
        verifyNoInteractions(port);

        reset(channel, inbox, publisher);
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        doThrow(new IllegalStateException("admission closed"))
                .when(inbox).executeAdmitted(
                        eq(token), eq("owner-a"), any(), eq(NOW), eq(port));
        when(publisher.publish(any(), eq(5_000L)))
                .thenReturn(AgentRabbitPublishResult.ack());
        consumer.consume(message(), channel);
        assertScopedPark(capturedPublish(), manifest.retry5sRoutingKey(), 1);
        verify(channel).basicAck(71L, false);
        verifyNoInteractions(port);
    }

    @Test
    void completionFailureAfterExternalSideEffectConfirmsCopyBeforeAcknowledging()
            throws Exception {
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(port.execute(any())).thenReturn(AgentCommandV2ExecutionPort.Result.dispatched());
        when(inbox.complete(eq(token), any(), eq(NOW)))
                .thenThrow(new IllegalStateException("settlement unavailable"));
        when(publisher.publish(any(), eq(5_000L)))
                .thenReturn(AgentRabbitPublishResult.ack());

        consumer.consume(message(), channel);

        assertScopedPark(capturedPublish(), manifest.retry5sRoutingKey(), 1);
        InOrder order = inOrder(port, inbox, publisher, channel);
        order.verify(port).execute(any());
        order.verify(inbox).complete(eq(token), any(), eq(NOW));
        order.verify(publisher).publish(any(), eq(5_000L));
        order.verify(channel).basicAck(71L, false);
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(true));
    }

    @Test
    void publishReturnNackTimeoutExceptionAndThrownFailureBackoffThenRequeue()
            throws Exception {
        assertRetryPublishFailureRequeues(new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.RETURNED, "ACK", "RETURNED",
                312, "NO_ROUTE", "PUBLISH_RETURNED"), null);
        assertRetryPublishFailureRequeues(new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.NACK, "NACK", "NOT_RETURNED",
                null, null, "PUBLISH_NACK"), null);
        assertRetryPublishFailureRequeues(new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.TIMEOUT, "TIMEOUT", "NOT_RETURNED",
                null, null, "PUBLISH_TIMEOUT"), null);
        assertRetryPublishFailureRequeues(new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION, "NONE", "NOT_RETURNED",
                null, null, "PUBLISH_EXCEPTION"), null);
        assertRetryPublishFailureRequeues(null, null);
        assertRetryPublishFailureRequeues(null,
                new IllegalStateException("publisher unavailable"));
    }

    @Test
    void expiredAndSettlementLimitDropThroughDispatchScopedDlxWithoutPublishing()
            throws Exception {
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("database unavailable"));

        consumer.consume(message(0, NOW), channel);

        verifyNoInteractions(publisher);
        verify(channel).basicNack(71L, false, false);
        assertTrue(parkBackoffs.isEmpty());

        reset(channel, inbox, publisher);
        parkBackoffs.clear();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxSourceNotSettledException("DELIVERY_NOT_PUBLISHED"));

        consumer.consume(message(AgentCommandRabbitV2Consumer.MAX_SOURCE_SETTLEMENT_RETRIES,
                9_999_999_999L), channel);

        verifyNoInteractions(publisher);
        verify(channel).basicNack(71L, false, false);
        assertTrue(parkBackoffs.isEmpty());
    }

    @Test
    void durableRetryPublishTimeoutNullAndThrowRetainOriginalAfterSingleAttempt()
            throws Exception {
        assertDurableRetryPublishFailure(new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.TIMEOUT, "TIMEOUT", "NOT_RETURNED",
                null, null, "PUBLISH_TIMEOUT"), null);
        assertDurableRetryPublishFailure(null, null);
        assertDurableRetryPublishFailure(null,
                new IllegalStateException("publisher unavailable"));
    }

    @Test
    void channelSettlementFailureOccursAfterDurableWorkAndPropagates() throws Exception {
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(port.execute(any())).thenReturn(AgentCommandV2ExecutionPort.Result.dispatched());
        when(inbox.complete(eq(token), any(), eq(NOW)))
                .thenReturn(result("PROCESSED", "SENT"));
        doThrow(new IOException("ack failed")).when(channel).basicAck(71L, false);

        assertThrows(IOException.class, () -> consumer.consume(message(), channel));
        verify(inbox).complete(eq(token), any(), eq(NOW));

        reset(channel, inbox, port);
        Message invalid = message();
        invalid.getMessageProperties().getHeaders().remove(
                AgentCommandAmqpContract.HEADER_EVENT_ID);
        doThrow(new IOException("nack failed"))
                .when(channel).basicNack(71L, false, false);
        assertThrows(IOException.class, () -> consumer.consume(invalid, channel));
        verifyNoInteractions(inbox, port);
    }

    @Test
    void forgedClaimTokenCannotReachExecutionPort() throws Exception {
        AgentInboxClaimToken valid = token();
        AgentInboxClaimToken forged = new AgentInboxClaimToken(valid.inboxId(),
                valid.consumerName(), valid.tenantId(), valid.dataDomainId(),
                "shared", valid.admissionEpoch(), valid.producerDeploymentId(),
                valid.moduleId(), valid.migrationEpoch(), valid.protocolVersion(),
                valid.claimantDeploymentId(), valid.claimantWorkerGeneration(),
                valid.clientId(), valid.messageId(), valid.eventId(), valid.commandId(),
                valid.taskId(), valid.targetAgentId(), valid.commandType(), valid.deliveryId(),
                valid.leaseOwner(), valid.leaseUntil(), valid.activeAttempt(),
                valid.inboxVersion(), valid.deliveryActiveAttempt(), valid.deliveryVersion(),
                valid.expiresAt());
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(forged));

        consumer.consume(message(), channel);

        verify(channel).basicNack(71L, false, false);
        verifyNoInteractions(port);
        verify(inbox, never()).executeAdmitted(any(), any(), any(), anyLong(), any());
    }

    private void assertDurableRetry(long retryAfterMillis) throws Exception {
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(port.execute(any()))
                .thenReturn(AgentCommandV2ExecutionPort.Result.retryable(retryAfterMillis));
        when(inbox.complete(eq(token), any(), eq(NOW))).thenReturn(
                result("RETRY", "RETRY",
                        AgentCommandRabbitV2Consumer.EXECUTION_RETRYABLE,
                        NOW + retryAfterMillis));
        when(publisher.publish(any(), eq(5_000L))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return AgentRabbitPublishResult.ack();
        });

        consumer.consume(message(), channel);

        ArgumentCaptor<AgentInboxDisposition> disposition =
                ArgumentCaptor.forClass(AgentInboxDisposition.class);
        verify(inbox).complete(eq(token), disposition.capture(), eq(NOW));
        assertEquals(AgentInboxDisposition.Type.RETRY, disposition.getValue().type());
        assertEquals(NOW + retryAfterMillis, disposition.getValue().nextRetryAt());
        assertEquals(AgentCommandRabbitV2Consumer.EXECUTION_RETRYABLE,
                disposition.getValue().errorCode());
        assertScopedPark(capturedPublish(), manifest.retry5mRoutingKey(), 1);
        verify(channel).basicAck(71L, false);
        InOrder order = inOrder(port, inbox, publisher, channel);
        order.verify(port).execute(any());
        order.verify(inbox).complete(eq(token), any(), eq(NOW));
        order.verify(publisher).publish(any(), eq(5_000L));
        order.verify(channel).basicAck(71L, false);
    }

    private void assertDurableRetryPublishFailure(AgentRabbitPublishResult outcome,
            RuntimeException failure) throws Exception {
        reset(channel, inbox, port, publisher);
        parkBackoffs.clear();
        AgentInboxClaimToken token = token();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(port.execute(any()))
                .thenReturn(AgentCommandV2ExecutionPort.Result.retryable(60_000L));
        when(inbox.complete(eq(token), any(), eq(NOW))).thenReturn(
                result("RETRY", "RETRY",
                        AgentCommandRabbitV2Consumer.EXECUTION_RETRYABLE,
                        NOW + 60_000L));
        if (failure == null) {
            when(publisher.publish(any(), eq(5_000L))).thenReturn(outcome);
        } else {
            doThrow(failure).when(publisher).publish(any(), eq(5_000L));
        }

        consumer.consume(message(), channel);

        assertScopedPark(capturedPublish(), manifest.retry5mRoutingKey(), 1);
        verify(publisher).publish(any(), eq(5_000L));
        verify(channel).basicNack(71L, false, true);
        verify(channel, never()).basicAck(anyLong(), eq(false));
        assertEquals(List.of(AgentCommandRabbitV2Consumer.PARK_FAILURE_BACKOFF_MILLIS),
                parkBackoffs);
        InOrder order = inOrder(inbox, publisher, channel);
        order.verify(inbox).complete(eq(token), any(), eq(NOW));
        order.verify(publisher).publish(any(), eq(5_000L));
        order.verify(channel).basicNack(71L, false, true);
    }

    private void assertRetryPublishFailureRequeues(AgentRabbitPublishResult outcome,
            RuntimeException failure) throws Exception {
        reset(channel, inbox, port, publisher);
        parkBackoffs.clear();
        when(inbox.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(5_000L));
        if (failure == null) {
            when(publisher.publish(any(), eq(5_000L))).thenAnswer(invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return outcome;
            });
        } else {
            doThrow(failure).when(publisher).publish(any(), eq(5_000L));
        }
        AtomicBoolean requeueObserved = new AtomicBoolean();
        doAnswer(invocation -> {
            assertEquals(List.of(AgentCommandRabbitV2Consumer.PARK_FAILURE_BACKOFF_MILLIS),
                    parkBackoffs);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            requeueObserved.set(true);
            return null;
        }).when(channel).basicNack(71L, false, true);

        consumer.consume(message(), channel);

        assertTrue(requeueObserved.get());
        assertScopedPark(capturedPublish(), manifest.retry5sRoutingKey(), 1);
        verify(publisher, times(1)).publish(any(), eq(5_000L));
        verify(channel).basicNack(71L, false, true);
        verify(channel, never()).basicAck(anyLong(), eq(false));
    }

    private AgentConfirmedPublishRequest capturedPublish() {
        ArgumentCaptor<AgentConfirmedPublishRequest> capture =
                ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        verify(publisher).publish(capture.capture(), eq(5_000L));
        return capture.getValue();
    }

    private void assertScopedPark(AgentConfirmedPublishRequest request,
            String expectedRoutingKey, int expectedSourceRetry) {
        assertEquals(manifest.deadLetterExchange(), request.destination());
        assertEquals(expectedRoutingKey, request.routingKey());
        assertNotEquals(AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                request.destination());
        assertNotEquals(AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY,
                request.routingKey());
        assertEquals(2, request.protocolVersion());
        assertEquals("1", request.tenantId());
        assertEquals("cyf", request.dataDomainId());
        assertEquals("local-test", request.executionPoolId());
        assertEquals(7, request.admissionEpoch());
        assertEquals("producer-a", request.producerDeploymentId());
        assertEquals("agent-command", request.moduleId());
        assertEquals(9, request.migrationEpoch());
        assertEquals("client-a", request.clientId());
        assertEquals("msg-1", request.messageId());
        assertEquals("evt-1", request.eventId());
        assertEquals("cmd-1", request.commandId());
        assertEquals(41, request.deliveryId());
        assertEquals("task-1", request.taskId());
        assertEquals("agent-1", request.targetAgentId());
        assertEquals("TASK_INVITE", request.commandType());
        assertEquals(1, request.activeAttempt());
        assertEquals(manifest.sha256(), request.topologySha256());
        assertEquals(expectedSourceRetry, request.sourceSettlementRetry());
        assertArrayEquals(AgentCommandAmqpContract.sha256(request.wirePayload()),
                request.wirePayloadHash());
    }

    private AgentInboxClaimToken token() {
        return new AgentInboxClaimToken(11,
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                "1", "cyf", "local-test", 7, "producer-a", "agent-command", 9, 2,
                "consumer-a", 3, "client-a", "msg-1", "evt-1", "cmd-1",
                "task-1", "agent-1", "TASK_INVITE", 41,
                "lease-a", NOW + 60_000, 1, 0, 1, 2, 9_999_999_999L);
    }

    private AgentInboxResult result(String status, String resultStatus) {
        return result(status, resultStatus, null, null);
    }

    private AgentInboxResult result(String status, String resultStatus,
            String errorCode, Long nextRetryAt) {
        return new AgentInboxResult(11, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                "1", "client-a", "msg-1", "evt-1", "cmd-1", 41,
                status, resultStatus, NOW, errorCode, nextRetryAt);
    }

    private Message message() {
        return message(0, 9_999_999_999L);
    }

    private Message message(int sourceSettlementRetry, long expiresAt) {
        long issuedAt = Math.max(1L, expiresAt - 1_000L);
        String json = "{\"schemaVersion\":2,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\","
                + "\"deliveryId\":\"41\","
                + "\"correlationId\":\"task-1\",\"causationId\":\"cause-1\","
                + "\"tenantId\":\"1\",\"dataDomainId\":\"cyf\","
                + "\"executionPoolId\":\"local-test\","
                + "\"producerDeploymentId\":\"producer-a\",\"admissionEpoch\":7,"
                + "\"moduleId\":\"agent-command\",\"migrationEpoch\":9,"
                + "\"clientId\":\"client-a\",\"ownerJiacn\":\"owner-a\","
                + "\"taskId\":\"task-1\",\"workItemId\":null,"
                + "\"targetAgentId\":\"agent-1\",\"commandType\":\"TASK_INVITE\","
                + "\"issuedAt\":" + issuedAt + ",\"expiresAt\":" + expiresAt
                + ",\"attempt\":1,"
                + "\"payload\":{\"actionType\":\"task_briefing\","
                + "\"reason\":\"reason\",\"instruction\":\"instruction\","
                + "\"taskTitle\":\"title\",\"requiredAbilities\":[\"analysis\"],"
                + "\"coordinatorAgentId\":\"agent-1\","
                + "\"collaboratorAgentIds\":[\"agent-1\"],"
                + "\"assignmentRole\":\"coordinator\",\"acceptance\":\"acceptance\","
                + "\"conversationType\":\"juyiting\"}}";
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(AgentCommandAmqpContract.CONTENT_TYPE);
        properties.setContentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING);
        properties.setType(AgentCommandAmqpContract.MESSAGE_TYPE);
        properties.setMessageId("msg-1");
        properties.setDeliveryTag(71L);
        properties.setConsumerQueue(manifest.dispatchQueue());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setReceivedExchange(manifest.mainExchange());
        properties.setReceivedRoutingKey(manifest.generalRoutingKey());
        Map<String, Object> headers = new HashMap<>();
        headers.put(AgentCommandAmqpContract.HEADER_WIRE_VERSION, 2);
        headers.put(AgentCommandAmqpContract.HEADER_EVENT_ID, "evt-1");
        headers.put(AgentCommandAmqpContract.HEADER_DELIVERY_ID, "41");
        headers.put(AgentCommandAmqpContract.HEADER_COMMAND_ID, "cmd-1");
        headers.put(AgentCommandAmqpContract.HEADER_TENANT_ID, "1");
        headers.put(AgentCommandAmqpContract.HEADER_DATA_DOMAIN_ID, "cyf");
        headers.put(AgentCommandAmqpContract.HEADER_EXECUTION_POOL_ID, "local-test");
        headers.put(AgentCommandAmqpContract.HEADER_PRODUCER_DEPLOYMENT_ID, "producer-a");
        headers.put(AgentCommandAmqpContract.HEADER_ADMISSION_EPOCH, 7L);
        headers.put(AgentCommandAmqpContract.HEADER_MODULE_ID, "agent-command");
        headers.put(AgentCommandAmqpContract.HEADER_MIGRATION_EPOCH, 9L);
        headers.put(AgentCommandAmqpContract.HEADER_CLIENT_ID, "client-a");
        headers.put(AgentCommandAmqpContract.HEADER_TASK_ID, "task-1");
        headers.put(AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID, "agent-1");
        headers.put(AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT, 1);
        headers.put(AgentCommandAmqpContract.HEADER_EXPIRES_AT, expiresAt);
        headers.put(AgentCommandAmqpContract.HEADER_WIRE_SHA256,
                AgentCommandAmqpContract.hex(AgentCommandAmqpContract.sha256(raw)));
        headers.put(AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256, manifest.sha256());
        headers.put(AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY,
                sourceSettlementRetry);
        properties.setHeaders(headers);
        return new Message(raw, properties);
    }
}
