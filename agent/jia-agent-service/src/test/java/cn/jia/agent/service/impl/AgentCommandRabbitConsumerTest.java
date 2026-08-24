package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
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
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.test.BaseMockTest;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandRabbitConsumerTest extends BaseMockTest {
    private static final long NOW = 1_000_000L;
    private static final long EXPIRES_AT = 2_000_000L;
    private static final AgentRabbitTopologyManifest MANIFEST =
            AgentRabbitTopologyManifest.canonical();

    @Mock AgentCommandInboxService inboxService;
    @Mock AgentTaskCollaborationAccessService accessService;
    @Mock AgentRawCommandDispatcher dispatcher;
    @Mock AgentConfirmedRabbitPublisher publisher;
    @Mock Channel channel;

    @Test
    void successfulExactDispatchUsesRawBytesOutsideTransactionCompletesSentThenAcks()
            throws Exception {
        Message rabbit = message(0);
        AgentInboxClaimToken token = token();
        when(inboxService.claim(any(AgentInboxMessage.class), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(accessService.resolveMemberAccess("tenant-a", "client-a", "task-1", "agent-1"))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertArrayEquals(rabbit.getBody(), invocation.getArgument(4));
            return AgentRawCommandDispatchResult.sent(1, 1);
        }).when(dispatcher).dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", rabbit.getBody());
        stubCompletion(token, AgentInboxDisposition.Type.SENT);

        consumer().consume(rabbit, channel);

        ArgumentCaptor<AgentInboxMessage> claimed = ArgumentCaptor.forClass(AgentInboxMessage.class);
        verify(inboxService).claim(claimed.capture(), any(), anyLong(), anyLong());
        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                claimed.getValue().consumerName());
        assertArrayEquals(rabbit.getBody(), claimed.getValue().rawWireBytes());
        InOrder order = inOrder(inboxService, accessService, dispatcher, channel);
        order.verify(inboxService).claim(any(), any(), anyLong(), anyLong());
        order.verify(accessService).resolveMemberAccess(
                "tenant-a", "client-a", "task-1", "agent-1");
        order.verify(dispatcher).dispatchExactRawCommand(
                "tenant-a", "client-a", "task-1", "agent-1", rabbit.getBody());
        order.verify(inboxService).complete(any(), any(), anyLong());
        order.verify(channel).basicAck(77L, false);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        verify(publisher, never()).publish(any(), anyLong());
    }

    @Test
    void offlineDurablyCompletesWaitingAgentThenAcksWithoutRabbitRetryOrRequeue()
            throws Exception {
        AgentInboxClaimToken token = token();
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.offline());
        stubCompletion(token, AgentInboxDisposition.Type.WAITING_AGENT);

        consumer().consume(message(0), channel);

        ArgumentCaptor<AgentInboxDisposition> disposition =
                ArgumentCaptor.forClass(AgentInboxDisposition.class);
        verify(inboxService).complete(any(), disposition.capture(), anyLong());
        assertEquals(AgentInboxDisposition.Type.WAITING_AGENT, disposition.getValue().type());
        assertEquals(NOW + AgentCommandRabbitConsumer.OFFLINE_RETRY_MILLIS,
                disposition.getValue().nextRetryAt());
        assertEquals(AgentCommandRabbitConsumer.AGENT_OFFLINE, disposition.getValue().errorCode());
        InOrder order = inOrder(inboxService, channel);
        order.verify(inboxService).complete(any(), any(), anyLong());
        order.verify(channel).basicAck(77L, false);
        verify(publisher, never()).publish(any(), anyLong());
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void sourcePublishBeforeSettlementUsesTypedBoundedConfirmedParkingWithFrozenProvenance()
            throws Exception {
        Message rabbit = message(7);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxSourceNotSettledException("OUTBOX_NOT_PUBLISHED"));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(rabbit, channel);

        AgentConfirmedPublishRequest retry = capturedPublish();
        assertEquals(AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE, retry.destination());
        assertEquals(AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY, retry.routingKey());
        assertEquals("msg-1", retry.messageId());
        assertEquals("evt-1", retry.eventId());
        assertEquals(41L, retry.deliveryId());
        assertEquals("cmd-1", retry.commandId());
        assertEquals("tenant-a", retry.tenantId());
        assertEquals("client-a", retry.clientId());
        assertEquals("task-1", retry.taskId());
        assertEquals("agent-1", retry.targetAgentId());
        assertEquals(1, retry.activeAttempt());
        assertEquals(8, retry.sourceSettlementRetry());
        assertArrayEquals(rabbit.getBody(), retry.wirePayload());
        assertArrayEquals(AgentCommandAmqpContract.sha256(rabbit.getBody()),
                retry.wirePayloadHash());
        verify(channel).basicAck(77L, false);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
    }

    @Test
    void sourceOrClaimTransientParkingFailurePreservesOriginalByRequeueing() throws Exception {
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxSourceNotSettledException("DELIVERY_NOT_PUBLISHED"));
        when(publisher.publish(any(), anyLong())).thenReturn(timeout());

        consumer().consume(message(0), channel);

        verify(channel).basicNack(77L, false, true);
        verify(channel, never()).basicAck(anyLong(), any(Boolean.class));

        org.mockito.Mockito.reset(channel, inboxService, publisher);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("temporary database failure"));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(0), channel);

        verify(publisher).publish(any(), eq(AgentCommandRabbitConsumer.CONFIRM_TIMEOUT_MILLIS));
        verify(channel).basicAck(77L, false);
    }

    @Test
    void duplicatePriorResultAcksAndMalformedPriorResultParksWithoutDispatch() throws Exception {
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.priorResult(result(token(), "PROCESSED", "SENT")));

        consumer().consume(message(0), channel);

        verify(channel).basicAck(77L, false);
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
        verify(dispatcher, never()).dispatchExactRawCommand(any(), any(), any(), any(), any());

        org.mockito.Mockito.reset(channel, inboxService, publisher);
        AgentInboxResult mismatched = new AgentInboxResult(
                91L, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-other", "evt-1", "cmd-1", 41L,
                "PROCESSED", "SENT", NOW, null);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.priorResult(mismatched));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(0), channel);

        verify(publisher).publish(any(), anyLong());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void inFlightChoosesLargestFittingFrozenLaneAndFallsBackThroughSmallerLanes()
            throws Exception {
        long expiresAt = NOW + 40_000L;
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(40_000L));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(2, expiresAt), channel);

        assertEquals(AgentRabbitTopologyManifest.RETRY_30S_ROUTING_KEY,
                capturedPublish().routingKey());
        verify(channel).basicAck(77L, false);

        org.mockito.Mockito.reset(channel, inboxService, publisher);
        expiresAt = NOW + 10_000L;
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(40_000L));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(2, expiresAt), channel);

        assertEquals(AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY,
                capturedPublish().routingKey());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void inFlightPublishFailuresTryFittingFallbackThenRequeueOriginal() throws Exception {
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(40_000L));
        when(publisher.publish(any(), anyLong())).thenReturn(timeout());

        consumer().consume(message(0, NOW + 400_000L), channel);

        ArgumentCaptor<AgentConfirmedPublishRequest> attempts =
                ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        verify(publisher, times(3)).publish(attempts.capture(), anyLong());
        assertEquals(List.of(
                        AgentRabbitTopologyManifest.RETRY_5M_ROUTING_KEY,
                        AgentRabbitTopologyManifest.RETRY_30S_ROUTING_KEY,
                        AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY),
                attempts.getAllValues().stream()
                        .map(AgentConfirmedPublishRequest::routingKey).toList());
        verify(channel).basicNack(77L, false, true);
        verify(channel, never()).basicAck(anyLong(), any(Boolean.class));
    }

    @Test
    void inFlightDoesNotFinalDlqAtPolicyMaxBeforeLeaseAndWireMaxPreservesOriginal()
            throws Exception {
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(4_000L));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(
                message(AgentCommandRabbitConsumer.MAX_SOURCE_SETTLEMENT_RETRIES), channel);

        assertEquals(AgentCommandRabbitConsumer.MAX_SOURCE_SETTLEMENT_RETRIES + 1,
                capturedPublish().sourceSettlementRetry());
        verify(channel).basicAck(77L, false);

        org.mockito.Mockito.reset(channel, inboxService, publisher);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(4_000L));

        consumer().consume(message(AgentCommandRabbitConsumer.MAX_WIRE_SOURCE_SETTLEMENT_RETRY),
                channel);

        verify(publisher, never()).publish(any(), anyLong());
        verify(channel).basicNack(77L, false, true);

        org.mockito.Mockito.reset(channel, inboxService, publisher);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.inFlight(4_000L));

        consumer().consume(message(0, NOW + 5_000L), channel);

        verify(publisher, never()).publish(any(), anyLong());
        verify(channel).basicNack(77L, false, true);
    }

    @Test
    void sourceSettlementLimitOrExpiryUsesConfirmedTerminalLaneInsteadOfNackDrop()
            throws Exception {
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxSourceNotSettledException("OUTBOX_NOT_PUBLISHED"));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(AgentCommandRabbitConsumer.MAX_SOURCE_SETTLEMENT_RETRIES),
                channel);

        AgentConfirmedPublishRequest terminal = capturedPublish();
        assertEquals(AgentRabbitTopologyManifest.DEAD_ROUTING_KEY, terminal.routingKey());
        assertEquals(AgentCommandRabbitConsumer.MAX_SOURCE_SETTLEMENT_RETRIES,
                terminal.sourceSettlementRetry());
        verify(channel).basicAck(77L, false);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));

        org.mockito.Mockito.reset(channel, inboxService, publisher);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxSourceNotSettledException("OUTBOX_NOT_PUBLISHED"));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(0, NOW), channel);

        assertEquals(AgentRabbitTopologyManifest.DEAD_ROUTING_KEY,
                capturedPublish().routingKey());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void staleAcquiredTokenParksAndNeverChecksAclOrSends() throws Exception {
        AgentInboxClaimToken stale = new AgentInboxClaimToken(
                91L, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-other", "evt-1", "cmd-1", 41L,
                "d05-test-consumer", NOW + AgentCommandRabbitConsumer.CLAIM_LEASE_MILLIS,
                1, 0L, 1, 7L, EXPIRES_AT);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(stale));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(0), channel);

        verify(publisher).publish(any(), anyLong());
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
        verify(dispatcher, never()).dispatchExactRawCommand(any(), any(), any(), any(), any());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void deterministicMalformedIdentityScopeAndAclDenialFailClosed() throws Exception {
        Message malformed = message(0);
        malformed.getMessageProperties().getHeaders().put(
                AgentCommandAmqpContract.HEADER_TENANT_ID, "tenant-b");
        consumer().consume(malformed, channel);
        verify(channel).basicNack(77L, false, false);
        verify(inboxService, never()).claim(any(), any(), anyLong(), anyLong());

        org.mockito.Mockito.reset(channel, inboxService);
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenThrow(new AgentInboxIdentityConflictException("OUTBOX_WIRE_BYTES_DRIFT"));
        consumer().consume(message(0), channel);
        verify(channel).basicNack(77L, false, false);

        org.mockito.Mockito.reset(channel, inboxService);
        consumer(gateFor("tenant-b", "client-a")).consume(message(0), channel);
        verify(channel).basicNack(77L, false, false);
        verify(inboxService, never()).claim(any(), any(), anyLong(), anyLong());

        org.mockito.Mockito.reset(channel, inboxService);
        AgentInboxClaimToken token = token();
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(accessService.resolveMemberAccess(any(), any(), any(), any()))
                .thenReturn(AgentTaskAccessLevel.READ_ONLY);
        stubCompletion(token, AgentInboxDisposition.Type.DEAD);
        consumer().consume(message(0), channel);
        verify(dispatcher, never()).dispatchExactRawCommand(any(), any(), any(), any(), any());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void aclResolverExceptionPublishesBeforeRetryCompletionAndAcks() throws Exception {
        AgentInboxClaimToken token = token();
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(accessService.resolveMemberAccess(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("temporary ACL database failure"));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());
        stubCompletion(token, AgentInboxDisposition.Type.RETRY);

        consumer().consume(message(0), channel);

        ArgumentCaptor<AgentInboxDisposition> disposition =
                ArgumentCaptor.forClass(AgentInboxDisposition.class);
        InOrder order = inOrder(publisher, inboxService, channel);
        order.verify(publisher).publish(any(), anyLong());
        order.verify(inboxService).complete(any(), disposition.capture(), anyLong());
        order.verify(channel).basicAck(77L, false);
        assertEquals(AgentInboxDisposition.Type.RETRY, disposition.getValue().type());
        assertEquals(AgentCommandRabbitConsumer.ACL_RESOLUTION_FAILED,
                disposition.getValue().errorCode());
        verify(dispatcher, never()).dispatchExactRawCommand(any(), any(), any(), any(), any());
    }

    @Test
    void aclResolverRetryPublishFailureKeepsOriginalAndDoesNotWriteDeadOrRetry()
            throws Exception {
        AgentInboxClaimToken token = token();
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(accessService.resolveMemberAccess(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("temporary ACL database failure"));
        when(publisher.publish(any(), anyLong())).thenReturn(timeout());

        consumer().consume(message(0), channel);

        verify(inboxService, never()).complete(any(), any(), anyLong());
        verify(channel).basicNack(77L, false, true);
        verify(channel, never()).basicAck(anyLong(), any(Boolean.class));
    }

    @Test
    void websocketFailurePublishesBeforeRetryCompletionAndUsesRawBytes() throws Exception {
        Message rabbit = message(0);
        AgentInboxClaimToken token = token();
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.sendFailed(2));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());
        stubCompletion(token, AgentInboxDisposition.Type.RETRY);

        consumer().consume(rabbit, channel);

        ArgumentCaptor<AgentConfirmedPublishRequest> retry =
                ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        ArgumentCaptor<AgentInboxDisposition> disposition =
                ArgumentCaptor.forClass(AgentInboxDisposition.class);
        InOrder order = inOrder(publisher, inboxService, channel);
        order.verify(publisher).publish(retry.capture(), anyLong());
        order.verify(inboxService).complete(any(), disposition.capture(), anyLong());
        order.verify(channel).basicAck(77L, false);
        assertArrayEquals(rabbit.getBody(), retry.getValue().wirePayload());
        assertEquals(AgentInboxDisposition.Type.RETRY, disposition.getValue().type());
        assertEquals(NOW + 5_000L, disposition.getValue().nextRetryAt());
        assertEquals(AgentCommandRabbitConsumer.WS_SEND_FAILED,
                disposition.getValue().errorCode());
    }

    @Test
    void confirmedRetryAllowsAckWhenRetryCompletionThrowsOrReturnsMismatchedResult()
            throws Exception {
        AgentInboxClaimToken token = token();
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.sendFailed(1));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());
        when(inboxService.complete(any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("temporary completion failure"));

        consumer().consume(message(0), channel);

        verify(channel).basicAck(77L, false);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));

        org.mockito.Mockito.reset(
                channel, inboxService, accessService, dispatcher, publisher);
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.sendFailed(1));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());
        when(inboxService.complete(any(), any(), anyLong()))
                .thenReturn(result(token, "DEAD", "DEAD"));

        consumer().consume(message(0), channel);

        verify(channel).basicAck(77L, false);
    }

    @Test
    void sentCompletionFailureParksAtLeaseLaneAndParkingFailureRequeuesWithoutResend()
            throws Exception {
        AgentInboxClaimToken token = token();
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.sent(1, 1));
        when(inboxService.complete(any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("temporary completion failure"));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(0), channel);

        assertEquals(AgentRabbitTopologyManifest.RETRY_5M_ROUTING_KEY,
                capturedPublish().routingKey());
        verify(channel).basicAck(77L, false);
        verify(dispatcher, times(1)).dispatchExactRawCommand(any(), any(), any(), any(), any());

        org.mockito.Mockito.reset(
                channel, inboxService, accessService, dispatcher, publisher);
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.sent(1, 1));
        when(inboxService.complete(any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("temporary completion failure"));
        when(publisher.publish(any(), anyLong())).thenReturn(timeout());

        consumer().consume(message(0), channel);

        verify(channel).basicNack(77L, false, true);
        verify(channel, never()).basicAck(anyLong(), any(Boolean.class));
        verify(dispatcher, times(1)).dispatchExactRawCommand(any(), any(), any(), any(), any());
    }

    @Test
    void offlineCompletionFailureCreatesRecoveryCopyAndDeadLettersOriginalWithoutHotRequeue()
            throws Exception {
        AgentInboxClaimToken token = token();
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.offline());
        when(inboxService.complete(any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("temporary completion failure"));
        when(publisher.publish(any(), anyLong())).thenReturn(AgentRabbitPublishResult.ack());

        consumer().consume(message(0), channel);

        verify(publisher).publish(any(), anyLong());
        verify(channel).basicNack(77L, false, false);
        verify(channel, never()).basicNack(77L, false, true);
        verify(channel, never()).basicAck(anyLong(), any(Boolean.class));
    }

    @Test
    void retryExhaustionForAclOrWebsocketIsAuditedDeadOnlyAfterExplicitMax()
            throws Exception {
        AgentInboxClaimToken token = token();
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(accessService.resolveMemberAccess(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("temporary ACL database failure"));
        stubCompletion(token, AgentInboxDisposition.Type.DEAD);

        consumer().consume(message(AgentCommandRabbitConsumer.MAX_SOURCE_SETTLEMENT_RETRIES),
                channel);

        ArgumentCaptor<AgentInboxDisposition> dead =
                ArgumentCaptor.forClass(AgentInboxDisposition.class);
        verify(inboxService).complete(any(), dead.capture(), anyLong());
        assertEquals(AgentCommandRabbitConsumer.ACL_RESOLUTION_RETRY_EXHAUSTED,
                dead.getValue().errorCode());
        verify(publisher, never()).publish(any(), anyLong());
        verify(channel).basicAck(77L, false);

        org.mockito.Mockito.reset(
                channel, inboxService, accessService, dispatcher, publisher);
        acquiredWritable(token);
        when(dispatcher.dispatchExactRawCommand(any(), any(), any(), any(), any()))
                .thenReturn(AgentRawCommandDispatchResult.sendFailed(1));
        stubCompletion(token, AgentInboxDisposition.Type.DEAD);

        consumer().consume(message(AgentCommandRabbitConsumer.MAX_SOURCE_SETTLEMENT_RETRIES),
                channel);

        ArgumentCaptor<AgentInboxDisposition> wsDead =
                ArgumentCaptor.forClass(AgentInboxDisposition.class);
        verify(inboxService).complete(any(), wsDead.capture(), anyLong());
        assertEquals(AgentCommandRabbitConsumer.WS_RETRY_EXHAUSTED,
                wsDead.getValue().errorCode());
        verify(publisher, never()).publish(any(), anyLong());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void listenerIsPinnedToFrozenQueueConsumerIdAndD04Factory() throws Exception {
        RabbitListener listener = AgentCommandRabbitConsumer.class
                .getMethod("consume", Message.class, Channel.class)
                .getAnnotation(RabbitListener.class);

        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, listener.id());
        assertEquals(List.of(AgentRabbitTopologyManifest.DISPATCH_QUEUE),
                List.of(listener.queues()));
        assertEquals("agentCommandListenerContainerFactory", listener.containerFactory());
        ConditionalOnProperty gate = AgentCommandRabbitConsumer.class
                .getAnnotation(ConditionalOnProperty.class);
        assertEquals("agent.rabbit-dispatch", gate.prefix());
        assertEquals(List.of("enabled"), List.of(gate.name()));
        assertEquals("true", gate.havingValue());
    }

    private AgentConfirmedPublishRequest capturedPublish() {
        ArgumentCaptor<AgentConfirmedPublishRequest> retry =
                ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        verify(publisher).publish(retry.capture(),
                eq(AgentCommandRabbitConsumer.CONFIRM_TIMEOUT_MILLIS));
        return retry.getValue();
    }

    private void acquiredWritable(AgentInboxClaimToken token) {
        when(inboxService.claim(any(), any(), anyLong(), anyLong()))
                .thenReturn(AgentInboxClaim.acquired(token));
        when(accessService.resolveMemberAccess(any(), any(), any(), any()))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
    }

    private AgentCommandRabbitConsumer consumer() {
        return consumer(allowedGate());
    }

    private AgentCommandRabbitConsumer consumer(AgentRabbitSafetyGate gate) {
        return new AgentCommandRabbitConsumer(
                inboxService, accessService, dispatcher, publisher, gate, MANIFEST,
                () -> NOW, "d05-test-consumer");
    }

    private void stubCompletion(
            AgentInboxClaimToken token, AgentInboxDisposition.Type expectedType) {
        when(inboxService.complete(any(), any(), anyLong())).thenAnswer(invocation -> {
            AgentInboxDisposition disposition = invocation.getArgument(1);
            assertEquals(expectedType, disposition.type());
            String result = disposition.type().name();
            String status = result.equals("SENT") ? "PROCESSED" : result;
            return new AgentInboxResult(
                    token.inboxId(), token.consumerName(), token.tenantId(), token.clientId(),
                    token.messageId(), token.eventId(), token.commandId(), token.deliveryId(),
                    status, result, NOW, disposition.errorCode());
        });
    }

    private AgentInboxResult result(
            AgentInboxClaimToken token, String status, String resultStatus) {
        return new AgentInboxResult(
                token.inboxId(), token.consumerName(), token.tenantId(), token.clientId(),
                token.messageId(), token.eventId(), token.commandId(), token.deliveryId(),
                status, resultStatus, NOW, resultStatus.equals("SENT") ? null : resultStatus);
    }

    private AgentInboxClaimToken token() {
        return token(EXPIRES_AT);
    }

    private AgentInboxClaimToken token(long expiresAt) {
        return new AgentInboxClaimToken(
                91L, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 41L,
                "d05-test-consumer",
                Math.min(NOW + AgentCommandRabbitConsumer.CLAIM_LEASE_MILLIS, expiresAt),
                1, 0L, 1, 7L, expiresAt);
    }

    private AgentRabbitPublishResult timeout() {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.TIMEOUT, "TIMEOUT", "NOT_RETURNED",
                null, null, "RABBIT_CONFIRM_TIMEOUT");
    }

    private Message message(int sourceRetry) {
        return message(sourceRetry, EXPIRES_AT);
    }

    private Message message(int sourceRetry, long expiresAt) {
        byte[] body = wire(expiresAt).getBytes(StandardCharsets.UTF_8);
        AgentConfirmedPublishRequest request = new AgentConfirmedPublishRequest(
                AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                body, AgentCommandAmqpContract.sha256(body), "msg-1", "evt-1", 41L,
                "cmd-1", "tenant-a", "client-a", "task-1", "agent-1",
                AgentProtocolConstants.COMMAND_TASK_INVITE, 1, expiresAt,
                MANIFEST.sha256(), sourceRetry);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(AgentCommandAmqpContract.CONTENT_TYPE);
        properties.setContentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING);
        properties.setType(AgentCommandAmqpContract.MESSAGE_TYPE);
        properties.setMessageId("msg-1");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setConsumerQueue(AgentRabbitTopologyManifest.DISPATCH_QUEUE);
        properties.setReceivedExchange(AgentRabbitTopologyManifest.MAIN_EXCHANGE);
        properties.setReceivedRoutingKey(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY);
        properties.setDeliveryTag(77L);
        properties.setHeaders(new HashMap<>(AgentCommandAmqpContract.headers(request)));
        return new Message(body, properties);
    }

    private String wire(long expiresAt) {
        return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\","
                + "\"tenantId\":\"tenant-a\",\"clientId\":\"client-a\","
                + "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\","
                + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,"
                + "\"expiresAt\":" + expiresAt
                + ",\"payload\":{\"instruction\":\"execute\"}}");
    }

    private AgentRabbitSafetyGate allowedGate() {
        return gateFor("tenant-a", "client-a");
    }

    private AgentRabbitSafetyGate gateFor(String tenantId, String clientId) {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "user", "password", "/d05"));
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                tenantId, clientId))));
    }
}
