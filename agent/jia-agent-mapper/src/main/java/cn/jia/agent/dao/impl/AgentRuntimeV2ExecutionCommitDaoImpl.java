package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2ExecutionCommitDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import cn.jia.core.util.DateUtil;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.util.Objects;

import static cn.jia.agent.dao.impl.AgentRuntimeV2DaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2ExecutionCommitDaoImpl implements AgentRuntimeV2ExecutionCommitDao {
    private final AgentRuntimeV2BindingMapper bindings;
    private final AgentRuntimeV2BindingGenerationFactMapper generationFacts;
    private final AgentRuntimeV2DispatchMapper dispatches;
    private final AgentCommandV2AckReceiptMapper receipts;
    private final AgentCommandV2ExecutionLeaseFactMapper facts;
    private final AgentCommandV2ExecutionGrantMapper grants;
    private final AgentCommandV2ExecutionContinuationAnchorMapper anchors;
    private final AgentCommandV2ExecutionRequestMapper requests;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public AgentCommandV2ExecutionGrantEntity appendStarted(CommandProducerScope p,
            RuntimeReceiverScope r, AuthenticatedRuntimeSession session, String dispatchId,
            AgentCommandV2AckReceiptEntity receipt, AgentCommandV2ExecutionLeaseFactEntity fact,
            AgentCommandV2ExecutionGrantEntity grant,
            AgentCommandV2ExecutionContinuationAnchorEntity anchor) {
        writable(); scopes(p, r); uuid(dispatchId, "runtimeDispatchId");
        require(receipt != null && fact != null && grant != null && anchor != null
                        && receipt.getAckAt() != null,
                "START persistence input is incomplete");
        requireUnsetProvenance(receipt.getEvidenceProvenance());
        requireUnsetProvenance(fact.getEvidenceProvenance());
        requireUnsetProvenance(grant.getEvidenceProvenance());
        requireUnsetProvenance(anchor.getEvidenceProvenance());
        AgentRuntimeV2BindingEntity binding = lockCurrentSession(r, session, DateUtil.nowTime());
        AgentCommandDeliveryEntity delivery = lockDelivery(p, r, session.runtimeBindingId(), dispatchId);
        require(delivery != null, "delivery is not in exact persisted scope");
        AgentConsumerInboxEntity inbox = lockDispatch(p, r, session.runtimeBindingId(), dispatchId);
        require(inbox != null, "dispatch is not in exact persisted scope");
        AgentCommandV2AckReceiptEntity received = lockReceipt(p, r, session.runtimeBindingId(),
                dispatchId, "RECEIVED");
        require(received != null, "RECEIVED receipt is unavailable");
        require(Objects.equals(delivery.getId(), inbox.getDeliveryId())
                        && Objects.equals(delivery.getCommandId(), inbox.getCommandId())
                        && "RECEIVED".equals(delivery.getStatus())
                        && delivery.getVersion() != null && delivery.getVersion() >= 0
                        && Objects.equals(inbox.getAckDeliveryVersion(), delivery.getVersion())
                        && "RECEIVED".equals(inbox.getAckStatus())
                        && Objects.equals(inbox.getAckRuntimeBindingId(), session.runtimeBindingId())
                        && Objects.equals(inbox.getAckRuntimeBindingGeneration(), session.bindingGeneration())
                        && Objects.equals(inbox.getAckRuntimeInstanceId(), session.runtimeInstanceId())
                        && Objects.equals(delivery.getActiveMessageId(), receipt.getSourceMessageId())
                        && Objects.equals(inbox.getMessageId(), receipt.getSourceMessageId())
                        && Objects.equals(inbox.getAckMessageId(), received.getAckMessageId())
                        && Objects.equals(inbox.getAckAt(), received.getAckAt())
                        && Objects.equals(received.getSourceMessageId(), receipt.getSourceMessageId())
                        && Objects.equals(received.getRuntimeDispatchId(), dispatchId)
                        && Objects.equals(received.getInboxId(), inbox.getId())
                        && Objects.equals(received.getDeliveryId(), delivery.getId())
                        && Objects.equals(received.getRuntimeBindingId(), session.runtimeBindingId())
                        && Objects.equals(received.getDispatchBindingGeneration(), session.bindingGeneration())
                        && Objects.equals(received.getAckHolderBindingGeneration(), session.bindingGeneration())
                        && Objects.equals(received.getRuntimeInstanceId(), session.runtimeInstanceId())
                        && Objects.equals(received.getPostcommitDeliveryVersion(), delivery.getVersion())
                        && Objects.equals(received.getPostcommitInboxVersion(), inbox.getVersion())
                        && received.getRequestFingerprint() != null
                        && MessageDigest.isEqual(received.getRequestFingerprint(),
                                AgentRuntimeV2WireCodec.ackRequestFingerprint(received.getAckMessageId(),
                                        dispatchId, received.getSourceMessageId(), "RECEIVED", received.getAckAt()))
                        && received.getAckAt() <= receipt.getAckAt(),
                "START requires the exact committed RECEIVED transition and source message");
        require(inbox.getExecutionCurrentLeaseFactId() == null && inbox.getExecutionLeaseRevision() == null,
                "dispatch already has execution authority");
        require(session.bindingGeneration() == inbox.getRuntimeBindingGeneration()
                        && session.runtimeInstanceId().equals(inbox.getDispatchRuntimeInstanceId()),
                "START holder must be the immutable dispatch generation");
        require("STARTED".equals(receipt.getStatus()) && "START_GRANTED".equals(fact.getFactType())
                        && "START".equals(grant.getGrantKind()), "initial execution shape is invalid");
        require(receipt.getRequestFingerprint() != null && MessageDigest.isEqual(
                        receipt.getRequestFingerprint(), AgentRuntimeV2WireCodec.ackRequestFingerprint(
                                receipt.getAckMessageId(), dispatchId, receipt.getSourceMessageId(),
                                receipt.getStatus(), receipt.getAckAt())),
                "ACK request fingerprint does not match the original seven fields");
        long serverIssueAt = freshAuthorityIssueTime(binding);
        require(Long.valueOf(0).equals(fact.getFactSequence()) && Long.valueOf(0).equals(fact.getLeaseRevision())
                        && fact.getPreviousLeaseFactId() == null && fact.getEffectiveAt() != null
                        && fact.getEffectiveAt() > 0 && fact.getEffectiveAt() <= serverIssueAt
                        && fact.getLeaseUntil() != null && fact.getLeaseUntil() > fact.getEffectiveAt()
                        && fact.getLeaseUntil() > serverIssueAt, "initial lease shape is invalid or expired");
        require(receipt.getAckAt() != null && receipt.getAckAt() <= serverIssueAt
                        && inbox.getClaimDeliveryVersion() != null && inbox.getClaimDeliveryVersion() >= 0
                        && inbox.getVersion() != null && inbox.getVersion() >= 0,
                "START commit evidence cannot be derived from the locked projection");
        long postcommitDeliveryVersion = Math.addExact(delivery.getVersion(), 1L);
        long postcommitInboxVersion = Math.addExact(inbox.getVersion(), 1L);
        receipt.setCommittedAt(serverIssueAt)
                .setPostcommitDeliveryVersion(postcommitDeliveryVersion)
                .setPostcommitInboxVersion(postcommitInboxVersion);
        fact.setCreatedAt(serverIssueAt);
        grant.setIssuedAt(serverIssueAt);

        String receiptId = id(), factId = id(), grantId = id();
        fillReceipt(p, r, inbox, session, receipt, receiptId, factId);
        fillFact(p, r, inbox, session, fact, factId, receiptId);
        fillGrant(p, r, inbox, session, grant, grantId, receipt, fact, factId, null);
        fillAnchor(p, r, anchor, dispatchId, receiptId, session.bindingGeneration(), grantId, serverIssueAt);

        require(insertFact(p, r, fact) == 1, "START fact insert failed");
        require(advanceDeliveryStarted(p, r, delivery, receipt, serverIssueAt) == 1,
                "START delivery ACK CAS lost");
        require(commitStartedProjection(p, r, binding, inbox, session, receipt, fact,
                postcommitDeliveryVersion, serverIssueAt) == 1, "START inbox ACK/projection CAS lost");
        require(insertReceipt(p, r, receipt) == 1, "START receipt insert failed");
        require(insertGrant(p, r, grant) == 1, "START grant insert failed");
        require(insertAnchor(p, r, anchor) == 1, "START anchor insert failed");
        return grant;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public AgentCommandV2ExecutionGrantEntity continueExecution(CommandProducerScope p,
            RuntimeReceiverScope r, AuthenticatedRuntimeSession session,
            AgentCommandV2ExecutionRequestEntity request,
            AgentCommandV2ExecutionLeaseFactEntity transfer,
            AgentCommandV2ExecutionGrantEntity grant,
            AgentCommandV2ExecutionContinuationAnchorEntity anchor) {
        writable(); scopes(p, r); requestShape(request, "CONTINUATION");
        requireUnsetProvenance(request.getEvidenceProvenance());
        requireUnsetProvenance(transfer.getEvidenceProvenance());
        requireUnsetProvenance(grant.getEvidenceProvenance());
        requireUnsetProvenance(anchor.getEvidenceProvenance());
        AgentRuntimeV2BindingEntity binding = lockCurrentSession(r, session, DateUtil.nowTime());
        AgentConsumerInboxEntity inbox = lockDispatch(p, r, session.runtimeBindingId(), request.getRuntimeDispatchId());
        require(inbox != null, "dispatch is not in exact persisted scope");
        AgentCommandV2AckReceiptEntity started = lockStarted(p, r, session.runtimeBindingId(), request.getRuntimeDispatchId());
        require(started != null && "STARTED".equals(started.getStatus()), "STARTED receipt is unavailable");
        AgentCommandV2ExecutionLeaseFactEntity originalStart = lockFact(p, r, started.getLeaseFactId());
        require(originalStart != null && "START_GRANTED".equals(originalStart.getFactType())
                        && Objects.equals(originalStart.getCausingReceiptId(), started.getReceiptId()),
                "STARTED receipt is not linked to its original START_GRANTED fact");
        requireStartLink(inbox, started, originalStart);
        verifyContinuationFingerprint(request, started);
        AgentCommandV2ExecutionLeaseFactEntity latest = lockLatest(p, r, session.runtimeBindingId(), request.getRuntimeDispatchId());
        long serverIssueAt = freshAuthorityIssueTime(binding);
        request.setDecidedAt(serverIssueAt);
        require(active(latest, serverIssueAt), "latest lease is not executable");
        requireProjection(inbox, latest);

        AgentCommandV2ExecutionContinuationAnchorEntity existing = lockAnchor(p, r,
                request.getRuntimeDispatchId(), started.getReceiptId(), session.bindingGeneration());
        AgentCommandV2ExecutionRequestEntity priorRequest = lockRequest(p, r, request.getExecutionRequestId());
        if (priorRequest != null) {
            requireRequestMapping(priorRequest, request, started, session, "CONTINUATION");
            AgentCommandV2ExecutionGrantEntity replay = lockGrant(p, r, priorRequest.getExecutionGrantId());
            requireGrantLink(replay, inbox, started, originalStart, latest, session,
                    "mapped continuation grant is no longer current");
            AgentRuntimeV2WireCodec.verifyGrantRow(replay); return replay;
        }
        if (existing != null) {
            AgentCommandV2ExecutionGrantEntity winner = lockGrant(p, r, existing.getExecutionGrantId());
            requireGrantLink(winner, inbox, started, originalStart, latest, session,
                    "continuation anchor is superseded");
            require(Objects.equals(existing.getRuntimeDispatchId(), inbox.getRuntimeDispatchId())
                            && Objects.equals(existing.getStartedReceiptId(), started.getReceiptId())
                            && Objects.equals(existing.getHolderBindingGeneration(), session.bindingGeneration())
                            && Objects.equals(existing.getExecutionGrantId(), winner.getExecutionGrantId()),
                    "continuation anchor links are inconsistent");
            AgentRuntimeV2WireCodec.verifyGrantRow(winner);
            fillRequest(p, r, session, request, started.getReceiptId(), winner.getExecutionGrantId(), "GRANTED", null);
            require(insertRequest(p, r, request) == 1, "continuation mapping insert failed");
            return winner;
        }

        require("HOLDER_TRANSFERRED".equals(transfer.getFactType())
                        && session.bindingGeneration() > latest.getHolderBindingGeneration()
                        && transfer.getEffectiveAt() >= latest.getEffectiveAt(),
                "new continuation requires a holder transfer");
        require(Objects.equals(transfer.getFactSequence(), Math.addExact(latest.getFactSequence(), 1L))
                        && Objects.equals(transfer.getLeaseRevision(), latest.getLeaseRevision())
                        && Objects.equals(transfer.getLeaseUntil(), latest.getLeaseUntil())
                        && Objects.equals(transfer.getPreviousLeaseFactId(), latest.getLeaseFactId()),
                "holder transfer must preserve expiry/revision and advance only fact sequence");
        require("CONTINUATION".equals(grant.getGrantKind()), "continuation grant kind is invalid");

        transfer.setCreatedAt(serverIssueAt);
        grant.setIssuedAt(serverIssueAt);
        String factId = id(), grantId = id();
        fillFact(p, r, inbox, session, transfer, factId, null);
        AgentCommandV2ExecutionGrantEntity previous = lockCurrentGrant(p, r, latest);
        require(previous != null, "latest lease fact has no exact current grant");
        requireGrantLink(previous, inbox, started, originalStart, latest,
                latest.getRuntimeBindingId(), latest.getHolderBindingGeneration(),
                latest.getRuntimeInstanceId(),
                "latest lease fact current grant has inconsistent links");
        AgentRuntimeV2WireCodec.verifyGrantRow(previous);
        fillGrant(p, r, inbox, session, grant, grantId, started, transfer,
                originalStart.getLeaseFactId(), previous.getExecutionGrantId());
        fillAnchor(p, r, anchor, request.getRuntimeDispatchId(), started.getReceiptId(),
                session.bindingGeneration(), grantId, serverIssueAt);
        fillRequest(p, r, session, request, started.getReceiptId(), grantId, "GRANTED", null);
        require(insertFact(p, r, transfer) == 1, "holder-transfer fact insert failed");
        require(project(p, r, inbox, session, transfer, latest.getLeaseRevision()) == 1,
                "holder-transfer projection CAS lost");
        require(insertGrant(p, r, grant) == 1, "continuation grant insert failed");
        require(insertAnchor(p, r, anchor) == 1, "continuation anchor insert failed");
        require(insertRequest(p, r, request) == 1, "continuation mapping insert failed");
        return grant;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public AgentCommandV2ExecutionGrantEntity renewExecution(CommandProducerScope p,
            RuntimeReceiverScope r, AuthenticatedRuntimeSession session,
            AgentCommandV2ExecutionRequestEntity request,
            AgentCommandV2ExecutionLeaseFactEntity renewal,
            AgentCommandV2ExecutionGrantEntity grant) {
        writable(); scopes(p, r); requestShape(request, "RENEWAL");
        requireUnsetProvenance(request.getEvidenceProvenance());
        requireUnsetProvenance(renewal.getEvidenceProvenance());
        requireUnsetProvenance(grant.getEvidenceProvenance());
        AgentRuntimeV2BindingEntity binding = lockCurrentSession(r, session, DateUtil.nowTime());
        AgentConsumerInboxEntity inbox = lockDispatch(p, r, session.runtimeBindingId(), request.getRuntimeDispatchId());
        require(inbox != null && Objects.equals(inbox.getExecutionHolderBindingGeneration(), session.bindingGeneration())
                        && Objects.equals(inbox.getExecutionHolderRuntimeInstanceId(), session.runtimeInstanceId()),
                "renewal holder does not match the current projection");
        AgentCommandV2AckReceiptEntity started = lockStarted(p, r, session.runtimeBindingId(), request.getRuntimeDispatchId());
        require(started != null, "STARTED receipt is unavailable");
        require(Objects.equals(request.getStartedReceiptId(), started.getReceiptId()),
                "renewal STARTED receipt link is inconsistent");
        AgentCommandV2ExecutionLeaseFactEntity originalStart = lockFact(p, r, started.getLeaseFactId());
        require(originalStart != null && "START_GRANTED".equals(originalStart.getFactType()),
                "original START_GRANTED fact is unavailable");
        requireStartLink(inbox, started, originalStart);
        AgentCommandV2ExecutionLeaseFactEntity latest = lockLatest(p, r, session.runtimeBindingId(), request.getRuntimeDispatchId());
        long serverIssueAt = freshAuthorityIssueTime(binding);
        request.setDecidedAt(serverIssueAt);
        require(active(latest, serverIssueAt), "latest lease is not renewable"); requireProjection(inbox, latest);
        AgentCommandV2ExecutionRequestEntity prior = lockRequest(p, r, request.getExecutionRequestId());
        if (prior != null) {
            requireRequestMapping(prior, request, started, session, "RENEWAL");
            AgentCommandV2ExecutionGrantEntity replay = lockGrant(p, r, prior.getExecutionGrantId());
            requireGrantLink(replay, inbox, started, originalStart, latest, session,
                    "mapped renewal grant is no longer current");
            require("RENEWAL".equals(replay.getGrantKind()),
                    "mapped renewal grant has the wrong kind");
            AgentRuntimeV2WireCodec.verifyGrantRow(replay); return replay;
        }
        require("RENEWED".equals(renewal.getFactType())
                        && Objects.equals(renewal.getFactSequence(), Math.addExact(latest.getFactSequence(), 1L))
                        && Objects.equals(renewal.getLeaseRevision(), Math.addExact(latest.getLeaseRevision(), 1L))
                        && Objects.equals(renewal.getPreviousLeaseFactId(), latest.getLeaseFactId())
                        && Objects.equals(session.bindingGeneration(), latest.getHolderBindingGeneration())
                        && renewal.getEffectiveAt() >= latest.getEffectiveAt()
                        && renewal.getLeaseUntil() != null && renewal.getLeaseUntil() > latest.getLeaseUntil(),
                "renewal must advance revision and expiry exactly once");
        require("RENEWAL".equals(grant.getGrantKind()), "renewal grant kind is invalid");
        renewal.setCreatedAt(serverIssueAt);
        grant.setIssuedAt(serverIssueAt);
        String factId = id(), grantId = id();
        fillFact(p, r, inbox, session, renewal, factId, null);
        AgentCommandV2ExecutionGrantEntity previous = lockCurrentGrant(p, r, latest);
        require(previous != null, "latest lease fact has no exact current grant");
        requireGrantLink(previous, inbox, started, originalStart, latest,
                latest.getRuntimeBindingId(), latest.getHolderBindingGeneration(),
                latest.getRuntimeInstanceId(),
                "latest lease fact current grant has inconsistent links");
        AgentRuntimeV2WireCodec.verifyGrantRow(previous);
        fillGrant(p, r, inbox, session, grant, grantId, started, renewal,
                originalStart.getLeaseFactId(), previous.getExecutionGrantId());
        fillRequest(p, r, session, request, started.getReceiptId(), grantId, "GRANTED", null);
        require(insertFact(p, r, renewal) == 1, "renewal fact insert failed");
        require(project(p, r, inbox, session, renewal, latest.getLeaseRevision()) == 1, "renewal projection CAS lost");
        require(insertGrant(p, r, grant) == 1, "renewal grant insert failed");
        require(insertRequest(p, r, request) == 1, "renewal mapping insert failed");
        return grant;
    }

    private static void writable() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("runtime-v2 execution persistence requires an active writable transaction");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static boolean active(AgentCommandV2ExecutionLeaseFactEntity fact, Long at) {
        return fact != null && at != null && at > 0 && fact.getLeaseUntil() != null && fact.getLeaseUntil() > at
                && java.util.Set.of("START_GRANTED", "RENEWED", "HOLDER_TRANSFERRED").contains(fact.getFactType());
    }
    private static void requireProjection(AgentConsumerInboxEntity inbox,
            AgentCommandV2ExecutionLeaseFactEntity latest) {
        require(Objects.equals(latest.getRuntimeDispatchId(), inbox.getRuntimeDispatchId())
                        && Objects.equals(latest.getInboxId(), inbox.getId())
                        && Objects.equals(latest.getDeliveryId(), inbox.getDeliveryId())
                        && Objects.equals(latest.getRuntimeBindingId(), inbox.getRuntimeBindingId())
                        && Objects.equals(latest.getDispatchBindingGeneration(), inbox.getRuntimeBindingGeneration())
                        && Objects.equals(inbox.getExecutionCurrentLeaseFactId(), latest.getLeaseFactId())
                        && Objects.equals(inbox.getExecutionHolderBindingId(), latest.getRuntimeBindingId())
                        && Objects.equals(inbox.getExecutionLeaseRevision(), latest.getLeaseRevision())
                        && Objects.equals(inbox.getExecutionLeaseUntil(), latest.getLeaseUntil())
                        && Objects.equals(inbox.getExecutionHolderBindingGeneration(), latest.getHolderBindingGeneration())
                        && Objects.equals(inbox.getExecutionHolderRuntimeInstanceId(), latest.getRuntimeInstanceId()),
                "lease fact and inbox holder projection diverged");
    }
    private static void requestShape(AgentCommandV2ExecutionRequestEntity request, String kind) {
        require(request != null && kind.equals(request.getRequestKind()) && request.getRequestFingerprint() != null
                && request.getRequestFingerprint().length == 32, "request mapping shape is invalid");
        uuid(request.getExecutionRequestId(), "executionRequestId"); uuid(request.getRuntimeDispatchId(), "runtimeDispatchId");
    }
    private static void verifyContinuationFingerprint(AgentCommandV2ExecutionRequestEntity request,
            AgentCommandV2AckReceiptEntity started) {
        require(Objects.equals(request.getStartedReceiptId(), started.getReceiptId()),
                "continuation STARTED receipt link is inconsistent");
        byte[] expected = AgentRuntimeV2WireCodec.continuationRequestFingerprint(
                request.getExecutionRequestId(), request.getRuntimeDispatchId(),
                started.getReceiptId(), started.getReceiptWireSha256());
        require(MessageDigest.isEqual(expected, request.getRequestFingerprint()),
                "continuation fingerprint does not match its exact six fields");
    }
    private static void requireStartLink(AgentConsumerInboxEntity inbox,
            AgentCommandV2AckReceiptEntity started,
            AgentCommandV2ExecutionLeaseFactEntity originalStart) {
        require(Objects.equals(started.getRuntimeDispatchId(), inbox.getRuntimeDispatchId())
                        && Objects.equals(started.getInboxId(), inbox.getId())
                        && Objects.equals(started.getDeliveryId(), inbox.getDeliveryId())
                        && Objects.equals(started.getRuntimeBindingId(), inbox.getRuntimeBindingId())
                        && Objects.equals(started.getDispatchBindingGeneration(),
                                inbox.getRuntimeBindingGeneration())
                        && Objects.equals(started.getLeaseFactId(), originalStart.getLeaseFactId())
                        && Objects.equals(originalStart.getRuntimeDispatchId(), inbox.getRuntimeDispatchId())
                        && Objects.equals(originalStart.getInboxId(), inbox.getId())
                        && Objects.equals(originalStart.getDeliveryId(), inbox.getDeliveryId())
                        && Objects.equals(originalStart.getRuntimeBindingId(), inbox.getRuntimeBindingId())
                        && Objects.equals(originalStart.getDispatchBindingGeneration(),
                                inbox.getRuntimeBindingGeneration())
                        && Objects.equals(originalStart.getHolderBindingGeneration(),
                                started.getAckHolderBindingGeneration())
                        && Objects.equals(originalStart.getRuntimeInstanceId(), started.getRuntimeInstanceId())
                        && Long.valueOf(0).equals(originalStart.getFactSequence())
                        && Long.valueOf(0).equals(originalStart.getLeaseRevision())
                        && Objects.equals(originalStart.getCausingReceiptId(), started.getReceiptId()),
                "STARTED receipt, original fact and dispatch links are inconsistent");
    }
    private static void requireRequestMapping(AgentCommandV2ExecutionRequestEntity prior,
            AgentCommandV2ExecutionRequestEntity request, AgentCommandV2AckReceiptEntity started,
            AuthenticatedRuntimeSession session, String kind) {
        require(kind.equals(prior.getRequestKind())
                        && MessageDigest.isEqual(prior.getRequestFingerprint(),
                                request.getRequestFingerprint())
                        && Objects.equals(prior.getRuntimeDispatchId(), request.getRuntimeDispatchId())
                        && Objects.equals(request.getStartedReceiptId(), started.getReceiptId())
                        && Objects.equals(prior.getStartedReceiptId(), started.getReceiptId())
                        && Objects.equals(prior.getRuntimeBindingId(), session.runtimeBindingId())
                        && Objects.equals(prior.getRuntimeInstanceId(), session.runtimeInstanceId())
                        && Objects.equals(prior.getRequestedHolderBindingGeneration(),
                                session.bindingGeneration())
                        && "GRANTED".equals(prior.getDecision())
                        && prior.getExecutionGrantId() != null && prior.getDenialCode() == null
                        && Integer.valueOf(2).equals(prior.getContractVersion()),
                "request ID conflicts with its immutable mapping");
    }
    private static void requireGrantLink(AgentCommandV2ExecutionGrantEntity grant,
            AgentConsumerInboxEntity inbox, AgentCommandV2AckReceiptEntity started,
            AgentCommandV2ExecutionLeaseFactEntity originalStart,
            AgentCommandV2ExecutionLeaseFactEntity current,
            AuthenticatedRuntimeSession session, String message) {
        requireGrantLink(grant, inbox, started, originalStart, current,
                session.runtimeBindingId(), session.bindingGeneration(), session.runtimeInstanceId(), message);
    }
    private static void requireGrantLink(AgentCommandV2ExecutionGrantEntity grant,
            AgentConsumerInboxEntity inbox, AgentCommandV2AckReceiptEntity started,
            AgentCommandV2ExecutionLeaseFactEntity originalStart,
            AgentCommandV2ExecutionLeaseFactEntity current, String bindingId,
            long holderGeneration, String runtimeInstanceId, String message) {
        require(grant != null
                        && Objects.equals(current.getRuntimeDispatchId(), inbox.getRuntimeDispatchId())
                        && Objects.equals(current.getInboxId(), inbox.getId())
                        && Objects.equals(current.getDeliveryId(), inbox.getDeliveryId())
                        && Objects.equals(current.getRuntimeBindingId(), inbox.getRuntimeBindingId())
                        && Objects.equals(current.getDispatchBindingGeneration(),
                                inbox.getRuntimeBindingGeneration())
                        && Objects.equals(current.getHolderBindingGeneration(), holderGeneration)
                        && Objects.equals(current.getRuntimeInstanceId(), runtimeInstanceId)
                        && Objects.equals(grant.getRuntimeDispatchId(), inbox.getRuntimeDispatchId())
                        && Objects.equals(grant.getStartedReceiptId(), started.getReceiptId())
                        && grant.getStartedReceiptSha256() != null
                        && started.getReceiptWireSha256() != null
                        && MessageDigest.isEqual(grant.getStartedReceiptSha256(),
                                started.getReceiptWireSha256())
                        && Objects.equals(grant.getRuntimeBindingId(), bindingId)
                        && Objects.equals(grant.getRuntimeInstanceId(), runtimeInstanceId)
                        && Objects.equals(grant.getDispatchBindingGeneration(),
                                inbox.getRuntimeBindingGeneration())
                        && Objects.equals(grant.getHolderBindingGeneration(), holderGeneration)
                        && Objects.equals(grant.getOriginalStartLeaseFactId(),
                                originalStart.getLeaseFactId())
                        && Objects.equals(grant.getCurrentLeaseFactId(), current.getLeaseFactId())
                        && Objects.equals(grant.getLeaseRevision(), current.getLeaseRevision())
                        && Objects.equals(grant.getLeaseUntil(), current.getLeaseUntil()), message);
    }

    private static long freshAuthorityIssueTime(AgentRuntimeV2BindingEntity binding) {
        long serverIssueAt = DateUtil.nowTime();
        require(binding.getSocketOwnerLeaseUntil() != null
                        && binding.getSocketOwnerLeaseUntil() > serverIssueAt,
                "authenticated runtime route expired before authority issuance");
        return serverIssueAt;
    }

    private AgentRuntimeV2BindingEntity lockCurrentSession(RuntimeReceiverScope r,
            AuthenticatedRuntimeSession session, long serverNow) {
        AgentRuntimeV2BindingEntity binding = bindings.lockExact(r.tenantId().value(), r.dataDomainId().value(),
                r.executionPoolId(), r.runtimeCredentialClientId(), r.ownerJiacn(), r.agentId(),
                r.claimantDeploymentId(), r.claimantWorkerGeneration(), r.receiverRegistrationRevision(),
                r.admissionEpoch(), r.moduleId(), r.migrationEpoch(), session.runtimeBindingId());
        if (binding != null) requireEnforcedProvenance(binding.getEvidenceProvenance());
        require(binding != null && Integer.valueOf(2).equals(binding.getContractVersion())
                        && "ACTIVE".equals(binding.getStatus())
                        && Objects.equals(binding.getBindingGeneration(), session.bindingGeneration())
                        && Objects.equals(binding.getRuntimeInstanceId(), session.runtimeInstanceId())
                        && Objects.equals(binding.getCurrentSessionFence(), session.sessionFence())
                        && Objects.equals(binding.getSocketOwnerInstanceId(), session.socketOwnerInstanceId())
                        && Objects.equals(binding.getSocketOwnerEpoch(), session.socketOwnerEpoch())
                        && binding.getSocketOwnerLeaseUntil() != null
                        && binding.getSocketOwnerLeaseUntil() > serverNow,
                "authenticated runtime session is not the active routed binding");
        AgentRuntimeV2BindingGenerationFactEntity latest = generationFacts.lockLatest(
                r.tenantId().value(), r.dataDomainId().value(), r.executionPoolId(),
                r.runtimeCredentialClientId(), r.ownerJiacn(), r.agentId(), r.claimantDeploymentId(),
                r.claimantWorkerGeneration(), r.receiverRegistrationRevision(), r.admissionEpoch(),
                r.moduleId(), r.migrationEpoch(), session.runtimeBindingId());
        if (latest != null) requireEnforcedProvenance(latest.getEvidenceProvenance());
        require(latest != null && "CONNECTED".equals(latest.getEventType())
                        && Objects.equals(latest.getBindingGeneration(), session.bindingGeneration())
                        && Objects.equals(latest.getRuntimeInstanceId(), session.runtimeInstanceId())
                        && Objects.equals(latest.getSessionFence(), session.sessionFence())
                        && Objects.equals(latest.getSocketOwnerInstanceId(), session.socketOwnerInstanceId())
                        && Objects.equals(latest.getSocketOwnerEpoch(), session.socketOwnerEpoch())
                        && latest.getSocketOwnerLeaseUntil() != null
                        && binding.getSocketOwnerLeaseUntil() >= latest.getSocketOwnerLeaseUntil(),
                "authenticated runtime session does not match the latest CONNECTED lifecycle fact");
        return binding;
    }

    private AgentCommandDeliveryEntity lockDelivery(CommandProducerScope p, RuntimeReceiverScope r,
            String binding, String dispatch) {
        return dispatches.lockDelivery(p.tenantId().value(), p.dataDomainId().value(),
                p.executionPoolId(), p.commandCallerClientId(), r.runtimeCredentialClientId(),
                p.ownerJiacn(), p.targetAgentId(), p.producerDeploymentId(),
                r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(),
                p.migrationEpoch(), binding, dispatch);
    }

    private int advanceDeliveryStarted(CommandProducerScope p, RuntimeReceiverScope r,
            AgentCommandDeliveryEntity delivery, AgentCommandV2AckReceiptEntity receipt, long now) {
        return dispatches.advanceDeliveryStarted(p.tenantId().value(), p.dataDomainId().value(),
                p.executionPoolId(), p.commandCallerClientId(), r.runtimeCredentialClientId(),
                p.ownerJiacn(), p.targetAgentId(), p.producerDeploymentId(),
                r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(),
                p.migrationEpoch(), delivery.getId(), delivery.getCommandId(),
                receipt.getSourceMessageId(), delivery.getVersion(), now);
    }

    private int commitStartedProjection(CommandProducerScope p, RuntimeReceiverScope r,
            AgentRuntimeV2BindingEntity binding, AgentConsumerInboxEntity inbox,
            AuthenticatedRuntimeSession session, AgentCommandV2AckReceiptEntity receipt,
            AgentCommandV2ExecutionLeaseFactEntity fact, long postcommitDeliveryVersion, long now) {
        return dispatches.commitStartedProjection(p.tenantId().value(), p.dataDomainId().value(),
                p.executionPoolId(), p.commandCallerClientId(), r.runtimeCredentialClientId(),
                p.ownerJiacn(), p.targetAgentId(), p.producerDeploymentId(),
                r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(),
                p.migrationEpoch(), inbox.getId(), inbox.getDeliveryId(), inbox.getVersion(),
                inbox.getRuntimeDispatchId(), inbox.getRuntimeBindingId(),
                inbox.getRuntimeBindingGeneration(), session.runtimeBindingId(),
                session.bindingGeneration(), session.runtimeInstanceId(),
                binding.getRuntimeInstallationId(), inbox.getAckDeliveryVersion(),
                inbox.getAckMessageId(), postcommitDeliveryVersion, receipt.getAckMessageId(),
                receipt.getAckAt(), fact.getLeaseFactId(), fact.getLeaseRevision(),
                fact.getEffectiveAt(), fact.getLeaseUntil(), now);
    }

    private AgentConsumerInboxEntity lockDispatch(CommandProducerScope p, RuntimeReceiverScope r, String binding, String dispatch) {
        AgentConsumerInboxEntity row = dispatches.lock(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(), p.commandCallerClientId(),
                r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(), p.producerDeploymentId(),
                r.claimantDeploymentId(), r.claimantWorkerGeneration(), r.receiverRegistrationRevision(),
                p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), binding, dispatch);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
    private AgentCommandV2AckReceiptEntity lockReceipt(CommandProducerScope p,
            RuntimeReceiverScope r, String binding, String dispatch, String status) {
        AgentCommandV2AckReceiptEntity row = receipts.lockCommitted(p.tenantId().value(),
                p.dataDomainId().value(), p.executionPoolId(), p.commandCallerClientId(),
                r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(),
                r.claimantWorkerGeneration(), r.receiverRegistrationRevision(),
                p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), binding, dispatch, status);
        if (row != null) AgentRuntimeV2WireCodec.verifyReceiptRow(row);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
    private AgentCommandV2AckReceiptEntity lockStarted(CommandProducerScope p,
            RuntimeReceiverScope r, String binding, String dispatch) {
        return lockReceipt(p, r, binding, dispatch, "STARTED");
    }
    private AgentCommandV2ExecutionLeaseFactEntity lockLatest(CommandProducerScope p,
            RuntimeReceiverScope r, String binding, String dispatch) {
        AgentCommandV2ExecutionLeaseFactEntity row = facts.lockLatest(
                p.tenantId().value(), p.dataDomainId().value(),
                p.executionPoolId(), p.commandCallerClientId(), r.runtimeCredentialClientId(),
                p.ownerJiacn(), p.targetAgentId(), p.producerDeploymentId(),
                r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(),
                p.migrationEpoch(), binding, dispatch);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
    private AgentCommandV2ExecutionContinuationAnchorEntity lockAnchor(CommandProducerScope p, RuntimeReceiverScope r,
            String dispatch, String receipt, long generation) {
        AgentCommandV2ExecutionContinuationAnchorEntity row = anchors.lockAnchor(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), dispatch, receipt, generation);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
    private AgentCommandV2ExecutionRequestEntity lockRequest(CommandProducerScope p, RuntimeReceiverScope r, String request) {
        AgentCommandV2ExecutionRequestEntity row = requests.lockByRequestId(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), request);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
    private AgentCommandV2ExecutionGrantEntity lockGrant(CommandProducerScope p, RuntimeReceiverScope r, String grant) {
        AgentCommandV2ExecutionGrantEntity row = grants.lockById(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), grant);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
    private AgentCommandV2ExecutionGrantEntity lockCurrentGrant(CommandProducerScope p, RuntimeReceiverScope r,
            AgentCommandV2ExecutionLeaseFactEntity fact) {
        AgentCommandV2ExecutionGrantEntity row = grants.lockCurrentByFact(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(),
                fact.getRuntimeDispatchId(), fact.getLeaseFactId());
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }
    private AgentCommandV2ExecutionLeaseFactEntity lockFact(CommandProducerScope p, RuntimeReceiverScope r,
            String factId) {
        uuid(factId, "leaseFactId");
        AgentCommandV2ExecutionLeaseFactEntity row = facts.lockById(
                p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), factId);
        if (row != null) requireEnforcedProvenance(row.getEvidenceProvenance());
        return row;
    }

    private static void fillReceipt(CommandProducerScope p, RuntimeReceiverScope r, AgentConsumerInboxEntity inbox,
            AuthenticatedRuntimeSession session, AgentCommandV2AckReceiptEntity e, String receiptId, String factId) {
        e.setReceiptId(receiptId).setRuntimeDispatchId(inbox.getRuntimeDispatchId()).setInboxId(inbox.getId())
                .setDeliveryId(inbox.getDeliveryId()).setRuntimeBindingId(session.runtimeBindingId())
                .setDispatchBindingGeneration(inbox.getRuntimeBindingGeneration())
                .setAckHolderBindingGeneration(session.bindingGeneration()).setRuntimeInstanceId(session.runtimeInstanceId())
                .setRuntimeCredentialClientId(r.runtimeCredentialClientId()).setReceiverRegistrationRevision(r.receiverRegistrationRevision())
                .setLeaseFactId(factId).setContractVersion(2).setReceiptWireContractVersion(2);
        scope(p, r, e); e.init4Creation();
        byte[] wire = AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(receiptId,
                e.getRuntimeDispatchId(), e.getAckMessageId(), e.getSourceMessageId(), e.getStatus(), e.getAckAt(),
                e.getCommittedAt(), e.getPostcommitDeliveryVersion(), e.getPostcommitInboxVersion(), factId));
        e.setReceiptWireBytes(wire).setReceiptWireSha256(AgentRuntimeV2WireCodec.sha256(wire));
    }
    private static void fillFact(CommandProducerScope p, RuntimeReceiverScope r, AgentConsumerInboxEntity inbox,
            AuthenticatedRuntimeSession session, AgentCommandV2ExecutionLeaseFactEntity e, String factId, String receiptId) {
        e.setLeaseFactId(factId).setRuntimeDispatchId(inbox.getRuntimeDispatchId()).setInboxId(inbox.getId())
                .setDeliveryId(inbox.getDeliveryId()).setCausingReceiptId(receiptId)
                .setRuntimeBindingId(session.runtimeBindingId()).setDispatchBindingGeneration(inbox.getRuntimeBindingGeneration())
                .setHolderBindingGeneration(session.bindingGeneration()).setRuntimeInstanceId(session.runtimeInstanceId())
                .setRuntimeCredentialClientId(r.runtimeCredentialClientId()).setReceiverRegistrationRevision(r.receiverRegistrationRevision());
        scope(p, r, e); e.init4Creation();
    }
    private static void fillGrant(CommandProducerScope p, RuntimeReceiverScope r, AgentConsumerInboxEntity inbox,
            AuthenticatedRuntimeSession session, AgentCommandV2ExecutionGrantEntity e, String grantId,
            AgentCommandV2AckReceiptEntity started, AgentCommandV2ExecutionLeaseFactEntity fact,
            String originalStartFactId, String previousGrant) {
        e.setExecutionGrantId(grantId).setRuntimeDispatchId(inbox.getRuntimeDispatchId())
                .setStartedReceiptId(started.getReceiptId()).setStartedReceiptSha256(started.getReceiptWireSha256())
                .setRuntimeBindingId(session.runtimeBindingId()).setRuntimeInstanceId(session.runtimeInstanceId())
                .setDispatchBindingGeneration(inbox.getRuntimeBindingGeneration()).setHolderBindingGeneration(session.bindingGeneration())
                .setCurrentLeaseFactId(fact.getLeaseFactId()).setLeaseRevision(fact.getLeaseRevision())
                .setLeaseUntil(fact.getLeaseUntil()).setPreviousExecutionGrantId(previousGrant)
                .setOriginalStartLeaseFactId(originalStartFactId)
                .setRuntimeCredentialClientId(r.runtimeCredentialClientId()).setReceiverRegistrationRevision(r.receiverRegistrationRevision())
                .setGrantWireContractVersion(2);
        scope(p, r, e); e.init4Creation();
        byte[] wire = AgentRuntimeV2WireCodec.encodeExecutionGrant(new AgentRuntimeV2WireCodec.ExecutionGrant(
                grantId, e.getGrantKind(), e.getRuntimeDispatchId(), e.getStartedReceiptId(), e.getStartedReceiptSha256(),
                e.getRuntimeBindingId(), e.getRuntimeInstanceId(), e.getDispatchBindingGeneration(),
                e.getHolderBindingGeneration(), e.getOriginalStartLeaseFactId(), e.getCurrentLeaseFactId(),
                e.getLeaseRevision(), e.getLeaseUntil(), e.getPreviousExecutionGrantId(), e.getIssuedAt()));
        e.setGrantWireBytes(wire).setGrantWireSha256(AgentRuntimeV2WireCodec.sha256(wire));
    }
    private static void fillAnchor(CommandProducerScope p, RuntimeReceiverScope r,
            AgentCommandV2ExecutionContinuationAnchorEntity e, String dispatch, String receipt,
            long generation, String grant, long createdAt) {
        e.setRuntimeDispatchId(dispatch).setStartedReceiptId(receipt).setHolderBindingGeneration(generation)
                .setExecutionGrantId(grant).setCreatedAt(createdAt).setContractVersion(2)
                .setRuntimeCredentialClientId(r.runtimeCredentialClientId()).setReceiverRegistrationRevision(r.receiverRegistrationRevision());
        scope(p, r, e); e.init4Creation();
    }
    private static void fillRequest(CommandProducerScope p, RuntimeReceiverScope r, AuthenticatedRuntimeSession session,
            AgentCommandV2ExecutionRequestEntity e, String receipt, String grant, String decision, String denial) {
        e.setStartedReceiptId(receipt).setRuntimeBindingId(session.runtimeBindingId())
                .setRuntimeInstanceId(session.runtimeInstanceId()).setRequestedHolderBindingGeneration(session.bindingGeneration())
                .setDecision(decision).setExecutionGrantId(grant).setDenialCode(denial).setContractVersion(2)
                .setRuntimeCredentialClientId(r.runtimeCredentialClientId()).setReceiverRegistrationRevision(r.receiverRegistrationRevision());
        scope(p, r, e); e.init4Creation();
    }
    private static void scope(CommandProducerScope p, RuntimeReceiverScope r, cn.jia.core.entity.BaseEntity e) {
        e.setTenantId(p.tenantId().value()); e.setClientId(p.commandCallerClientId());
        if (e instanceof AgentCommandV2AckReceiptEntity x) { x.setDataDomainId(p.dataDomainId().value()).setExecutionPoolId(p.executionPoolId()).setOwnerJiacn(p.ownerJiacn()).setAgentId(p.targetAgentId()).setProducerDeploymentId(p.producerDeploymentId()).setClaimantDeploymentId(r.claimantDeploymentId()).setClaimantWorkerGeneration(r.claimantWorkerGeneration()).setAdmissionEpoch(p.admissionEpoch()).setModuleId(p.moduleId()).setMigrationEpoch(p.migrationEpoch()); }
        else if (e instanceof AgentCommandV2ExecutionLeaseFactEntity x) { x.setDataDomainId(p.dataDomainId().value()).setExecutionPoolId(p.executionPoolId()).setOwnerJiacn(p.ownerJiacn()).setAgentId(p.targetAgentId()).setProducerDeploymentId(p.producerDeploymentId()).setClaimantDeploymentId(r.claimantDeploymentId()).setClaimantWorkerGeneration(r.claimantWorkerGeneration()).setAdmissionEpoch(p.admissionEpoch()).setModuleId(p.moduleId()).setMigrationEpoch(p.migrationEpoch()); }
        else if (e instanceof AgentCommandV2ExecutionGrantEntity x) { x.setDataDomainId(p.dataDomainId().value()).setExecutionPoolId(p.executionPoolId()).setOwnerJiacn(p.ownerJiacn()).setAgentId(p.targetAgentId()).setProducerDeploymentId(p.producerDeploymentId()).setClaimantDeploymentId(r.claimantDeploymentId()).setClaimantWorkerGeneration(r.claimantWorkerGeneration()).setAdmissionEpoch(p.admissionEpoch()).setModuleId(p.moduleId()).setMigrationEpoch(p.migrationEpoch()); }
        else if (e instanceof AgentCommandV2ExecutionContinuationAnchorEntity x) { x.setDataDomainId(p.dataDomainId().value()).setExecutionPoolId(p.executionPoolId()).setOwnerJiacn(p.ownerJiacn()).setAgentId(p.targetAgentId()).setProducerDeploymentId(p.producerDeploymentId()).setClaimantDeploymentId(r.claimantDeploymentId()).setClaimantWorkerGeneration(r.claimantWorkerGeneration()).setAdmissionEpoch(p.admissionEpoch()).setModuleId(p.moduleId()).setMigrationEpoch(p.migrationEpoch()); }
        else if (e instanceof AgentCommandV2ExecutionRequestEntity x) { x.setDataDomainId(p.dataDomainId().value()).setExecutionPoolId(p.executionPoolId()).setOwnerJiacn(p.ownerJiacn()).setAgentId(p.targetAgentId()).setProducerDeploymentId(p.producerDeploymentId()).setClaimantDeploymentId(r.claimantDeploymentId()).setClaimantWorkerGeneration(r.claimantWorkerGeneration()).setAdmissionEpoch(p.admissionEpoch()).setModuleId(p.moduleId()).setMigrationEpoch(p.migrationEpoch()); }
    }

    private int project(CommandProducerScope p, RuntimeReceiverScope r, AgentConsumerInboxEntity inbox,
            AuthenticatedRuntimeSession session, AgentCommandV2ExecutionLeaseFactEntity fact, Long expectedRevision) {
        return dispatches.projectLease(p.tenantId().value(), p.dataDomainId().value(), p.executionPoolId(),
                p.commandCallerClientId(), r.runtimeCredentialClientId(), p.ownerJiacn(), p.targetAgentId(),
                p.producerDeploymentId(), r.claimantDeploymentId(), r.claimantWorkerGeneration(),
                r.receiverRegistrationRevision(), p.admissionEpoch(), p.moduleId(), p.migrationEpoch(), inbox.getId(),
                inbox.getVersion(), inbox.getRuntimeDispatchId(), inbox.getRuntimeBindingId(), inbox.getRuntimeBindingGeneration(),
                session.runtimeBindingId(), session.bindingGeneration(), session.runtimeInstanceId(), fact.getLeaseFactId(),
                expectedRevision, fact.getLeaseRevision(),
                inbox.getExecutionStartedAt() == null ? fact.getEffectiveAt() : inbox.getExecutionStartedAt(),
                fact.getLeaseUntil(), fact.getCreatedAt());
    }
    private int insertReceipt(CommandProducerScope p, RuntimeReceiverScope r, AgentCommandV2AckReceiptEntity e) { return receipts.insert(p.tenantId().value(),p.dataDomainId().value(),p.executionPoolId(),p.commandCallerClientId(),r.runtimeCredentialClientId(),p.ownerJiacn(),p.targetAgentId(),p.producerDeploymentId(),r.claimantDeploymentId(),r.claimantWorkerGeneration(),r.receiverRegistrationRevision(),p.admissionEpoch(),p.moduleId(),p.migrationEpoch(),e); }
    private int insertFact(CommandProducerScope p, RuntimeReceiverScope r, AgentCommandV2ExecutionLeaseFactEntity e) { return facts.insert(p.tenantId().value(),p.dataDomainId().value(),p.executionPoolId(),p.commandCallerClientId(),r.runtimeCredentialClientId(),p.ownerJiacn(),p.targetAgentId(),p.producerDeploymentId(),r.claimantDeploymentId(),r.claimantWorkerGeneration(),r.receiverRegistrationRevision(),p.admissionEpoch(),p.moduleId(),p.migrationEpoch(),e); }
    private int insertGrant(CommandProducerScope p, RuntimeReceiverScope r, AgentCommandV2ExecutionGrantEntity e) { return grants.insert(p.tenantId().value(),p.dataDomainId().value(),p.executionPoolId(),p.commandCallerClientId(),r.runtimeCredentialClientId(),p.ownerJiacn(),p.targetAgentId(),p.producerDeploymentId(),r.claimantDeploymentId(),r.claimantWorkerGeneration(),r.receiverRegistrationRevision(),p.admissionEpoch(),p.moduleId(),p.migrationEpoch(),e); }
    private int insertAnchor(CommandProducerScope p, RuntimeReceiverScope r, AgentCommandV2ExecutionContinuationAnchorEntity e) { return anchors.insert(p.tenantId().value(),p.dataDomainId().value(),p.executionPoolId(),p.commandCallerClientId(),r.runtimeCredentialClientId(),p.ownerJiacn(),p.targetAgentId(),p.producerDeploymentId(),r.claimantDeploymentId(),r.claimantWorkerGeneration(),r.receiverRegistrationRevision(),p.admissionEpoch(),p.moduleId(),p.migrationEpoch(),e); }
    private int insertRequest(CommandProducerScope p, RuntimeReceiverScope r, AgentCommandV2ExecutionRequestEntity e) { return requests.insert(p.tenantId().value(),p.dataDomainId().value(),p.executionPoolId(),p.commandCallerClientId(),r.runtimeCredentialClientId(),p.ownerJiacn(),p.targetAgentId(),p.producerDeploymentId(),r.claimantDeploymentId(),r.claimantWorkerGeneration(),r.receiverRegistrationRevision(),p.admissionEpoch(),p.moduleId(),p.migrationEpoch(),e); }
}
