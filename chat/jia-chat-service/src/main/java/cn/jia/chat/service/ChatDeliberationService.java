package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDeliberationStates;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable, exact-scope admission and Agent callback boundary for Juyi Hall chat turns. */
@Service
public class ChatDeliberationService {
    private static final int MAX_ID = 100;
    private static final int HISTORY_QUERY_LIMIT = 200;
    private static final int RECENT_HISTORY_LIMIT = 24;
    private static final int MAX_HISTORY_CONTENT = 200_000;
    private static final int SUMMARY_EXCERPT_LIMIT = 2_000;
    private static final int SUMMARY_CONTENT_LIMIT = 64_000;
    // Client chat-runtime.mjs v1 validates every facts value recursively against 8192 UTF-8 bytes.
    // This is a measured protocol bound, not a performance deadline or reason to drop a request.
    private static final int CLIENT_FACT_VALUE_BYTES = 8_192;
    private static final Set<String> CAPABILITY_FAILURE_REASONS = Set.of(
            "TARGET_PROFILE_UNSUPPORTED",
            "TARGET_INSPECT_INPUT_NOT_MATERIALIZED",
            "TARGET_EXECUTE_VIA_CHAT_FORBIDDEN");

    private final ChatDeliberationDao dao;
    private final ChatConversationDao conversationDao;
    private final ChatMessageDao messageDao;
    private final AgentService agentService;
    private ChatInteractionStepStore interactionSteps;
    private ChatTypedDeliberationService typedDeliberation;
    private ChatTypedInspectionService typedInspection;
    private ChatInspectionAuthorityService inspectionAuthority;
    private ChatActionFinalService actionFinals;

    @Autowired(required = false)
    public void setActionFinals(ChatActionFinalService actionFinals) { this.actionFinals = actionFinals; }

    // Retain the existing constructor for legacy tests and integrations. In production the
    // scoped store is injected, so durable v2 requests can be read from the same GET endpoint.
    @Autowired
    public void setInteractionSteps(ChatInteractionStepStore interactionSteps) {
        this.interactionSteps = interactionSteps;
    }

    @Autowired(required = false)
    public void setTypedDeliberation(ChatTypedDeliberationService typedDeliberation) {
        this.typedDeliberation = typedDeliberation;
    }

    @Autowired(required = false)
    public void setTypedInspection(ChatTypedInspectionService typedInspection) {
        this.typedInspection = typedInspection;
    }

    @Autowired(required = false)
    public void setInspectionAuthority(ChatInspectionAuthorityService inspectionAuthority) {
        this.inspectionAuthority = inspectionAuthority;
    }

    public ChatDeliberationService(ChatDeliberationDao dao, ChatConversationDao conversationDao,
            ChatMessageDao messageDao, AgentService agentService) {
        this.dao = dao;
        this.conversationDao = conversationDao;
        this.messageDao = messageDao;
        this.agentService = agentService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Admission admit(String tenantId, ServerResolvedSender sender, String conversationId,
            long expectedGeneration, JuyitingConversationScope scope,
            InteractionRoute route, ChatMessageDTO input, Map<String, Object> taskMaterials) {
        return admit(tenantId, sender, conversationId, expectedGeneration, scope, route, input,
                taskMaterials, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Admission admit(String tenantId, ServerResolvedSender sender, String conversationId,
            long expectedGeneration, JuyitingConversationScope scope,
            InteractionRoute route, ChatMessageDTO input, Map<String, Object> taskMaterials,
            Map<String, Object> trustedTypedFacts) {
        return admit(tenantId, sender, conversationId, expectedGeneration, scope, route, input,
                taskMaterials, trustedTypedFacts, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Admission admit(String tenantId, ServerResolvedSender sender, String conversationId,
            long expectedGeneration, JuyitingConversationScope scope,
            InteractionRoute route, ChatMessageDTO input, Map<String, Object> taskMaterials,
            Map<String, Object> trustedTypedFacts, Map<String, Object> trustedTypedAdmission) {
        return admitTrusted(tenantId, sender, conversationId, expectedGeneration, scope, route, input,
                taskMaterials, trustedTypedFacts, trustedTypedAdmission, null, null, null);
    }

    /** Sibling-only authority path. Generic INSPECT callers retain the legacy inputRef guard. */
    @Transactional(rollbackFor = Exception.class)
    public Admission admitInspection(String tenantId, ServerResolvedSender sender, String conversationId,
            long expectedGeneration, JuyitingConversationScope scope, ChatMessageDTO input,
            Map<String, Object> taskMaterials, Map<String, Object> trustedTypedInspection,
            Map<String, Object> trustedTypedAdmission) {
        if (trustedTypedInspection == null) throw invalid("Trusted inspection context is required");
        Map<String, Object> normalizedInspection;
        try {
            normalizedInspection = ChatTypedInspectionContextService
                    .validateTypedInspection(trustedTypedInspection);
        } catch (RuntimeException invalid) {
            throw invalid("Trusted inspection context is invalid");
        }
        return admitTrusted(tenantId, sender, conversationId, expectedGeneration, scope,
                InteractionRoute.INSPECT, input, taskMaterials, null, trustedTypedAdmission,
                normalizedInspection, null, null);
    }

    /** Called under task-root/binding/conversation locks by the durable action consumer only.
     * Reuses the original real USER row; never inserts a synthetic approval or instruction. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Admission admitInspectionContinuation(ChatActionFinalService.BoundAction action,
            ChatTypedInspectionContextService.Context context, JuyitingConversationScope scope) {
        var parent = Objects.requireNonNull(action).outcome(); var owner = parent.scope();
        if (scope == null || !"bounty".equals(scope.scopeType()) || !parent.taskId().equals(scope.taskId())
                || scope.targetAgentIds().size() != 1 || !scope.targetAgentIds().getFirst().equals(
                        action.validated().binding().get("targetAgentId"))) throw unavailable();
        requireLockedConversation(owner.tenantId(), owner.ownerJiacn(), owner.clientId(),
                owner.conversationId(), owner.conversationGeneration());
        var turn = requireLockedTurn(owner.tenantId(), owner.ownerJiacn(), owner.clientId(), parent.turnId());
        if (!parent.finalDigest().equals(turn.getFinalDigest()) || !parent.requestId().equals(turn.getRequestId())
                || !Objects.equals(parent.requestRevision(), turn.getRequestRevision())
                || !Objects.equals(parent.assistantMessageId(), turn.getFinalMessageId())
                || !Objects.equals(owner.conversationId(), turn.getConversationId())
                || !Objects.equals(owner.conversationGeneration(), turn.getConversationGeneration())
                || !Objects.equals(scope.targetAgentIds().getFirst(), turn.getTargetAgentId())
                || !(ChatDeliberationStates.FINAL_PERSISTED.equals(turn.getState())
                    || ChatDeliberationStates.PUBLISHED.equals(turn.getState()))) throw unavailable();
        var original = dao.findRequest(owner.tenantId(), owner.ownerJiacn(), owner.clientId(), parent.requestId());
        if (original == null || !Objects.equals(original.getRequestRevision(), parent.requestRevision())
                || !Objects.equals(original.getConversationId(), owner.conversationId())
                || !Objects.equals(original.getConversationGeneration(), owner.conversationGeneration())
                || !Objects.equals(original.getUserMessageId(), action.admission().userMessageId())) throw unavailable();
        // The ID comes from the exact owner-scoped durable request, never from the browser/model.
        var user = messageDao.selectById(original.getUserMessageId());
        if (user == null || !Objects.equals(original.getUserMessageId(), user.getId())
                || !owner.tenantId().equals(user.getTenantId()) || !owner.ownerJiacn().equals(user.getJiacn())
                || !owner.clientId().equals(user.getClientId()) || !owner.conversationId().equals(user.getConversationId())
                || !"USER".equals(user.getMessageType()) || !"user".equals(user.getSenderType())
                || !"juyiting".equals(user.getConversationType())) throw unavailable();
        String actionId = ChatActionFinalValidator.actionEventId(action.validated());
        String requestId = inspectionContinuationRequestId(actionId);
        var inspection = ChatTypedInspectionContextService.validateTypedInspection(context.typedInspection());
        Map<String, Object> manifest = castContextMap(inspection.get("manifest"));
        Map<String, Object> contextScope = castContextMap(manifest.get("scope"));
        if (!contextScope.equals(Map.of("tenantId", owner.tenantId(), "ownerJiacn", owner.ownerJiacn(),
                "clientId", owner.clientId(), "conversationId", owner.conversationId(),
                "conversationGeneration", Long.toString(owner.conversationGeneration()), "taskId", parent.taskId(),
                "assignmentRevision", Long.toString(parent.assignmentRevision()), "requestId", requestId,
                "requestRevision", "1", "targetAgentId", turn.getTargetAgentId()))) throw unavailable();
        var selected = action.validated().interactionOutcome().action().sourceRefIds();
        var materialized = ChatActionOutcomeContract.facts(inspection.get("discussionFacts")).availableSources().stream()
                .map(ChatActionOutcomeContract.Source::sourceRefId).toList();
        if (!new LinkedHashSet<>(selected).equals(new LinkedHashSet<>(materialized))) throw unavailable();
        var capability = action.validated().dispatchFacts().availableActions().stream().filter(a -> a.actionId().equals(
                action.validated().interactionOutcome().action().actionId())).findFirst().orElseThrow(this::unavailable);
        if (!"INSPECT_INPUTS".equals(capability.kind())) throw unavailable();
        var lineage = Map.<String, Object>of("schemaVersion", 3, "origin", "AGENT_ACTION",
                "actionRequestId", actionId, "parentOutcomeId", parent.outcomeId(), "parentRequestId", parent.requestId(),
                "parentTurnId", parent.turnId(), "parentFinalDigest", parent.finalDigest(),
                "originalUserMessageId", Long.toString(user.getId()),
                "instruction", action.validated().interactionOutcome().action().instruction());
        ChatMessageDTO input = new ChatMessageDTO(); input.setRequestId(requestId); input.setRequestRevision(1L);
        input.setContent(requireContent(user.getContent())); input.setConversationId(owner.conversationId());
        var sender = new ServerResolvedSender("user", user.getSenderName(), owner.ownerJiacn(), owner.clientId(), DisplayNameSource.FALLBACK);
        return admitTrusted(owner.tenantId(), sender, owner.conversationId(), owner.conversationGeneration(), scope,
                InteractionRoute.INSPECT, input, null, null, null, inspection, user, lineage);
    }

    static String inspectionContinuationRequestId(String actionId) {
        return "action-inspect_" + sha256(actionId).substring(0, 40);
    }

    private Admission admitTrusted(String tenantId, ServerResolvedSender sender, String conversationId,
            long expectedGeneration, JuyitingConversationScope scope,
            InteractionRoute route, ChatMessageDTO input, Map<String, Object> taskMaterials,
            Map<String, Object> trustedTypedFacts, Map<String, Object> trustedTypedAdmission,
            Map<String, Object> trustedTypedInspection, ChatMessageEntity continuationUser,
            Map<String, Object> continuationFacts) {
        requireIdentity(tenantId, 50);
        ServerResolvedSender trustedSender = requireHumanSender(sender);
        String ownerJiacn = trustedSender.jiacn();
        String clientId = trustedSender.clientId();
        requireIdentity(conversationId, MAX_ID);
        if (expectedGeneration < 1 || scope == null || route == null || route == InteractionRoute.EXECUTE) {
            throw invalid("Invalid chat admission scope");
        }
        String content = requireContent(input == null ? null : input.getContent());
        String requestId = input == null ? null : input.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            input.setRequestId(requestId);
        }
        requestId = requireIdentity(requestId, MAX_ID);
        long revision = input.getRequestRevision() == null ? 1L : input.getRequestRevision();
        if (revision < 1) throw invalid("requestRevision must be positive");
        input.setRequestRevision(revision);

        ChatConversationEntity conversation = requireLockedConversation(
                tenantId, ownerJiacn, clientId, conversationId, expectedGeneration);
        requireConversationScope(conversation, scope);
        List<ChatMessageEntity> existingMessages = messageDao.findOwnedByConversationIdWithLimit(
                ownerJiacn, clientId, conversationId, HISTORY_QUERY_LIMIT);
        if (existingMessages == null) throw unavailable();
        List<Map<String, Object>> inputRefs = authorizeInputRefs(
                input.getInputRefs(), route, conversation, scope, existingMessages,
                trustedTypedInspection != null);
        AgentTaskDTO task = taskFacts(scope, tenantId, clientId);
        Map<String, Object> requestDigestInput = new LinkedHashMap<>();
        requestDigestInput.put("schemaVersion", "1");
        requestDigestInput.put("conversationId", conversationId);
        requestDigestInput.put("conversationGeneration", expectedGeneration);
        requestDigestInput.put("requestRevision", revision);
        requestDigestInput.put("content", content);
        requestDigestInput.put("route", route.name());
        requestDigestInput.put("scopeType", scope.scopeType());
        requestDigestInput.put("scopeKey", scope.scopeKey());
        requestDigestInput.put("targets", scope.targetAgentIds());
        requestDigestInput.put("inputRefs", inputRefs);
        if (trustedTypedFacts != null) requestDigestInput.put("typedDeliberation", trustedTypedFacts);
        if (trustedTypedInspection != null) requestDigestInput.put("typedInspection", trustedTypedInspection);
        if (trustedTypedAdmission != null) requestDigestInput.put("typedDeliberationAdmission", trustedTypedAdmission);
        if (continuationFacts != null) requestDigestInput.put("actionContinuation", continuationFacts);
        String requestDigest = digest(requestDigestInput);

        ChatRequestEntity existing = dao.lockRequest(tenantId, ownerJiacn, clientId, requestId, revision);
        if (existing != null) {
            if (!requestDigest.equals(existing.getRequestDigest())
                    || !conversationId.equals(existing.getConversationId())
                    || expectedGeneration != existing.getConversationGeneration()) {
                throw conflict("requestId/requestRevision conflicts with an existing request");
            }
            return loadAdmission(tenantId, existing, true);
        }
        ChatRequestEntity otherRevision = dao.findRequest(tenantId, ownerJiacn, clientId, requestId);
        if (otherRevision != null) {
            throw conflict("requestId was already admitted under a different revision");
        }

        long now = System.currentTimeMillis();
        ChatMessageEntity userMessage = continuationUser;
        if (userMessage == null) {
            userMessage = new ChatMessageEntity()
                .setConversationId(conversationId)
                .setMessageType("USER")
                .setContent(content)
                .setMetadata(JsonUtil.toJson(trustedUserMetadata(
                        input, scope, requestId, revision, route, trustedSender, taskMaterials)))
                .setJiacn(ownerJiacn)
                .setSyncStatus("PENDING")
                .setConversationType(JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING)
                .setSenderType(trustedSender.type())
                .setSenderName(trustedSender.displayName());
        userMessage.setTenantId(tenantId);
        userMessage.setClientId(clientId);
        userMessage.init4Creation();
        if (messageDao.insertScoped(tenantId, clientId, userMessage) != 1 || userMessage.getId() == null) {
            throw persistence("Unable to persist admitted user message");
        }
        }

        ChatRequestEntity request = new ChatRequestEntity()
                .setTenantId(tenantId).setOwnerJiacn(ownerJiacn).setClientId(clientId)
                .setRequestId(requestId).setRequestRevision(revision).setRequestDigest(requestDigest)
                .setConversationId(conversationId).setConversationGeneration(expectedGeneration)
                .setUserMessageId(userMessage.getId()).setAggregateState(ChatDeliberationStates.RUNNING)
                .setStateVersion(0L).setCreatedAt(now).setUpdatedAt(now);
        if (dao.insertRequest(request) != 1 || request.getId() == null) {
            throw persistence("Unable to persist chat request");
        }

        List<Dispatch> dispatches = new ArrayList<>();
        for (String targetAgentId : scope.targetAgentIds()) {
            String turnId = stableId("turn", tenantId, ownerJiacn, clientId, requestId, targetAgentId);
            String dispatchId = stableId("dispatch", tenantId, ownerJiacn, clientId, requestId, targetAgentId);
            Map<String, Object> authorizedContext = authorizedContext(
                    tenantId, ownerJiacn, clientId, conversationId, targetAgentId,
                    existingMessages, userMessage, inputRefs, taskMaterials);
            Map<String, Object> sourceVector = sourceVector(
                    expectedGeneration, userMessage.getId(), task, authorizedContext);
            Map<String, Object> facts = factsManifest(
                    conversation, scope, targetAgentId, task, taskMaterials, authorizedContext);
            if (trustedTypedFacts != null || trustedTypedAdmission != null || trustedTypedInspection != null || continuationFacts != null) {
                Map<String, Object> typedFacts = new LinkedHashMap<>(facts);
                if (trustedTypedFacts != null) typedFacts.put("typedDeliberation",
                        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(trustedTypedFacts)));
                if (trustedTypedInspection != null) typedFacts.put("typedInspection",
                        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(trustedTypedInspection)));
                if (trustedTypedAdmission != null) typedFacts.put("typedDeliberationAdmission",
                        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(trustedTypedAdmission)));
                if (continuationFacts != null) typedFacts.put("actionContinuation", continuationFacts);
                facts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(typedFacts));
            }
            String sourceJson = CanonicalContextJson.write(sourceVector);
            String factsJson = CanonicalContextJson.write(facts);
            String contextDigest = contextDigest(sourceVector, facts);
            String snapshotId = stableId("ctx", turnId, contextDigest);

            ChatContextSnapshotEntity snapshot = new ChatContextSnapshotEntity()
                    .setSnapshotId(snapshotId).setTenantId(tenantId).setOwnerJiacn(ownerJiacn)
                    .setClientId(clientId).setConversationId(conversationId)
                    .setConversationGeneration(expectedGeneration).setRequestId(requestId)
                    .setRequestRevision(revision).setTargetAgentId(targetAgentId).setRoute(route.name())
                    .setSourceVectorJson(sourceJson).setFactsManifestJson(factsJson)
                    .setContextDigest(contextDigest).setCreatedAt(now);
            ChatTurnEntity turn = new ChatTurnEntity()
                    .setTurnId(turnId).setTenantId(tenantId).setOwnerJiacn(ownerJiacn).setClientId(clientId)
                    .setRequestId(requestId).setRequestRevision(revision).setConversationId(conversationId)
                    .setConversationGeneration(expectedGeneration).setTargetAgentId(targetAgentId)
                    .setSnapshotId(snapshotId).setContextDigest(contextDigest).setDispatchId(dispatchId)
                    .setRoute(route.name()).setState(ChatDeliberationStates.RECEIVED).setStateVersion(0L)
                    .setLastDeltaSeq(0L).setCreatedAt(now).setUpdatedAt(now);
            Map<String, Object> eventPayload = eventPayload(tenantId, ownerJiacn, clientId,
                    conversationId, expectedGeneration, requestId, revision, turnId, dispatchId,
                    targetAgentId, snapshotId, contextDigest, route, content, input, scope,
                    trustedSender, taskMaterials, now, sourceVector, facts);
            ChatDispatchOutboxEntity outbox = new ChatDispatchOutboxEntity()
                    .setEventId(stableId("evt", dispatchId, "DISPATCH"))
                    .setTenantId(tenantId).setOwnerJiacn(ownerJiacn).setClientId(clientId)
                    .setTurnId(turnId).setDispatchId(dispatchId).setEventType("DISPATCH")
                    .setStatus("READY").setPayloadJson(CanonicalContextJson.write(eventPayload))
                    .setVersion(0L).setAvailableAt(now).setAttemptCount(0).setFencingToken(0L)
                    .setCreatedAt(now).setUpdatedAt(now);
            if (dao.insertSnapshot(snapshot) != 1 || dao.insertTurn(turn) != 1 || dao.insertOutbox(outbox) != 1) {
                throw persistence("Unable to persist chat turn admission");
            }
            dispatches.add(toDispatch(turn, snapshot));
        }
        return new Admission(requestId, revision, Long.toString(userMessage.getId()), conversationId,
                expectedGeneration, route, List.copyOf(dispatches), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDispatch(String tenantId, String ownerJiacn, String clientId, String turnId, boolean delivered) {
        ChatTurnEntity candidate = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        requireLockedConversation(tenantId, ownerJiacn, clientId, candidate.getConversationId(),
                candidate.getConversationGeneration());
        ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, turnId);
        if (terminal(turn.getState())) return;
        String next = delivered ? ChatDeliberationStates.DISPATCHED : ChatDeliberationStates.QUEUED;
        if (Objects.equals(next, turn.getState())) return;
        long now = System.currentTimeMillis();
        if (dao.updateTurnState(turn, next, null, now) != 1) {
            throw conflict("Concurrent dispatch state change");
        }
        turn.setState(next).setUpdatedAt(now).setStateVersion(turn.getStateVersion() + 1);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeltaResult acceptDelta(String tenantId, String ownerJiacn, String clientId, String conversationId,
            long conversationGeneration, String agentId, String requestId, String turnId, String dispatchId,
            String snapshotId, String contextDigest, long deltaSeq, String content,
            ServerResolvedAgentSender sender) {
        if (deltaSeq < 1 || content == null || content.isEmpty()) throw invalid("Invalid delta");
        ChatTurnEntity visible = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        requireLockedConversation(tenantId, ownerJiacn, clientId, visible.getConversationId(), visible.getConversationGeneration());
        ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, turnId);
        requireCallbackBinding(turn, tenantId, ownerJiacn, clientId, conversationId, conversationGeneration,
                agentId, requestId, dispatchId, snapshotId, contextDigest);
        ServerResolvedAgentSender trustedSender = requireAgentSender(sender, agentId, ownerJiacn, clientId);
        String deltaDigest = "sha256:" + sha256(content);
        long last = Optional.ofNullable(turn.getLastDeltaSeq()).orElse(0L);
        if (deltaSeq <= last) {
            if (deltaSeq == last) {
                return new DeltaResult(Objects.equals(deltaDigest, turn.getLastDeltaDigest())
                        ? DeltaStatus.DUPLICATE : DeltaStatus.CONFLICTING_DUPLICATE, turn, null);
            }
            return new DeltaResult(DeltaStatus.STALE, turn, null);
        }
        if (terminal(turn.getState())) return new DeltaResult(DeltaStatus.TERMINAL, turn, null);
        if (deltaSeq != last + 1) {
            String reason = "DELTA_GAP_EXPECTED_" + (last + 1) + "_GOT_" + deltaSeq;
            if (!ChatDeliberationStates.RECOVERY_REQUIRED.equals(turn.getState())) {
                long now = System.currentTimeMillis();
                if (dao.updateTurnState(turn, ChatDeliberationStates.RECOVERY_REQUIRED, reason, now) != 1) {
                    throw conflict("Concurrent delta gap state change");
                }
                turn.setState(ChatDeliberationStates.RECOVERY_REQUIRED).setTerminalReason(reason)
                        .setUpdatedAt(now).setStateVersion(turn.getStateVersion() + 1);
                ChatConversationEventEntity event = persistEvent(turn,
                        stableId("evt", dispatchId, "RESYNC_REQUIRED", Long.toString(deltaSeq)),
                        "resync_required", Map.ofEntries(
                                Map.entry("expectedDeltaSeq", Long.toString(last + 1)),
                                Map.entry("receivedDeltaSeq", Long.toString(deltaSeq)),
                                Map.entry("reason", reason),
                                Map.entry("agentId", trustedSender.agentId()),
                                Map.entry("senderType", trustedSender.type()),
                                Map.entry("senderName", trustedSender.displayName())), now);
                return new DeltaResult(DeltaStatus.GAP, turn, event);
            }
            return new DeltaResult(DeltaStatus.GAP, turn, null);
        }
        if (dao.acceptDelta(turn, deltaSeq, deltaDigest, System.currentTimeMillis()) != 1) {
            throw conflict("Concurrent delta state change");
        }
        turn.setLastDeltaSeq(deltaSeq).setLastDeltaDigest(deltaDigest)
                .setState(ChatDeliberationStates.STREAMING).setStateVersion(turn.getStateVersion() + 1);
        ChatConversationEventEntity event = persistEvent(turn,
                stableId("evt", dispatchId, "DELTA", Long.toString(deltaSeq)),
                "agent_message_delta", Map.ofEntries(
                        Map.entry("content", content),
                        Map.entry("deltaSeq", Long.toString(deltaSeq)),
                        Map.entry("agentId", trustedSender.agentId()),
                        Map.entry("senderType", trustedSender.type()),
                        Map.entry("senderName", trustedSender.displayName())),
                System.currentTimeMillis());
        return new DeltaResult(DeltaStatus.ACCEPTED, turn, event);
    }

    @Transactional(rollbackFor = Exception.class)
    public FinalResult persistFinal(String tenantId, String ownerJiacn, String clientId, String conversationId,
            long conversationGeneration, String agentId, String requestId, String turnId, String dispatchId,
            String snapshotId, String contextDigest, String content,
            ServerResolvedAgentSender sender) {
        return persistFinal(tenantId, ownerJiacn, clientId, conversationId, conversationGeneration,
                agentId, requestId, turnId, dispatchId, snapshotId, contextDigest, content,
                null, null, sender);
    }

    @Transactional(rollbackFor = Exception.class)
    public FinalResult persistFinal(String tenantId, String ownerJiacn, String clientId, String conversationId,
            long conversationGeneration, String agentId, String requestId, String turnId, String dispatchId,
            String snapshotId, String contextDigest, String content, Integer outcomeContractVersion,
            String rawInteractionOutcomeJson, ServerResolvedAgentSender sender) {
        return persistFinal(tenantId, ownerJiacn, clientId, conversationId, conversationGeneration, agentId,
                requestId, turnId, dispatchId, snapshotId, contextDigest, content, outcomeContractVersion,
                rawInteractionOutcomeJson, null, sender);
    }

    @Transactional(rollbackFor = Exception.class)
    public FinalResult persistFinal(String tenantId, String ownerJiacn, String clientId, String conversationId,
            long conversationGeneration, String agentId, String requestId, String turnId, String dispatchId,
            String snapshotId, String contextDigest, String content, Integer outcomeContractVersion,
            String rawInteractionOutcomeJson, String rawInspectionInputReceiptJson,
            ServerResolvedAgentSender sender) {
        String safeContent = requireContent(content);
        ChatTurnEntity visible = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        if (InteractionRoute.INSPECT.name().equals(visible.getRoute())) {
            if (inspectionAuthority == null) throw persistence("Inspection authority service is unavailable");
            return inspectionAuthority.withFinalAuthority(visible, () -> persistFinalLocked(tenantId, ownerJiacn,
                    clientId, conversationId, conversationGeneration, agentId, requestId, turnId, dispatchId,
                    snapshotId, contextDigest, safeContent, outcomeContractVersion, rawInteractionOutcomeJson,
                    rawInspectionInputReceiptJson, sender, visible));
        }
        if (rawInspectionInputReceiptJson != null) throw invalid("Inspection receipt is forbidden for CHAT");
        return persistFinalLocked(tenantId, ownerJiacn, clientId, conversationId, conversationGeneration, agentId,
                requestId, turnId, dispatchId, snapshotId, contextDigest, safeContent, outcomeContractVersion,
                rawInteractionOutcomeJson, null, sender, visible);
    }

    private FinalResult persistFinalLocked(String tenantId, String ownerJiacn, String clientId, String conversationId,
            long conversationGeneration, String agentId, String requestId, String turnId, String dispatchId,
            String snapshotId, String contextDigest, String safeContent, Integer outcomeContractVersion,
            String rawInteractionOutcomeJson, String rawInspectionInputReceiptJson,
            ServerResolvedAgentSender sender, ChatTurnEntity visible) {
        ChatConversationEntity conversation = requireLockedConversation(tenantId, ownerJiacn, clientId,
                visible.getConversationId(), visible.getConversationGeneration());
        ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, turnId);
        requireCallbackBinding(turn, tenantId, ownerJiacn, clientId, conversationId, conversationGeneration,
                agentId, requestId, dispatchId, snapshotId, contextDigest);
        ServerResolvedAgentSender trustedSender = requireAgentSender(sender, agentId, ownerJiacn, clientId);
        ChatContextSnapshotEntity finalSnapshot = dao.findSnapshot(tenantId, ownerJiacn, clientId, snapshotId);
        if (finalSnapshot == null) throw unavailable();
        Map<String, Object> finalFacts = parseJsonMap(finalSnapshot.getFactsManifestJson());
        boolean inspectionMarker = finalFacts.containsKey("typedInspection");
        boolean deliberationMarker = finalFacts.containsKey("typedDeliberation");
        if (inspectionMarker && deliberationMarker) throw persistence("Snapshot has conflicting typed contracts");
        ChatTypedDeliberationService.Prepared typedPrepared = null;
        ChatTypedInspectionService.Prepared inspectionPrepared = null;
        ChatActionFinalService.Prepared actionPrepared = null;
        if (ChatActionFinalService.isV3(finalFacts)) {
            if (actionFinals == null) throw persistence("Action final service is unavailable");
            actionPrepared = actionFinals.prepare(turn, finalSnapshot, safeContent, outcomeContractVersion,
                    rawInteractionOutcomeJson, rawInspectionInputReceiptJson);
        } else if (inspectionMarker) {
            if (typedInspection == null) throw persistence("Typed inspection service is unavailable");
            inspectionPrepared = typedInspection.prepare(turn, finalSnapshot, safeContent, outcomeContractVersion,
                    rawInteractionOutcomeJson, rawInspectionInputReceiptJson);
        } else if (typedDeliberation != null) {
            typedPrepared = typedDeliberation.prepare(turn, finalSnapshot, safeContent,
                    outcomeContractVersion, rawInteractionOutcomeJson);
        } else if (outcomeContractVersion != null || rawInteractionOutcomeJson != null || deliberationMarker) {
            throw persistence("Typed deliberation service is unavailable");
        }
        String finalDigest = actionPrepared != null ? actionPrepared.validated().finalDigest() : inspectionPrepared != null ? inspectionPrepared.validated().finalDigest()
                : typedPrepared == null ? "sha256:" + sha256(safeContent) : typedPrepared.validated().finalDigest();
        if ((ChatDeliberationStates.FINAL_PERSISTED.equals(turn.getState())
                || ChatDeliberationStates.PUBLISHED.equals(turn.getState()))
                && turn.getFinalDigest() == null) {
            throw persistence("Terminal final state is incomplete");
        }
        if (turn.getFinalDigest() != null) {
            if (finalDigest.equals(turn.getFinalDigest())) {
                ChatRequestEntity existingRequest = dao.findRequest(
                        tenantId, ownerJiacn, clientId, turn.getRequestId());
                if (existingRequest == null) throw unavailable();
                return new FinalResult(FinalStatus.DUPLICATE, turn.getFinalMessageId(),
                        Long.toString(existingRequest.getUserMessageId()),
                        stableId("evt", dispatchId, "FINAL_PERSISTED"), turn, null);
            }
            throw conflict("Turn final already differs");
        }
        if (ChatDeliberationStates.CANCELLED.equals(turn.getState())
                || ChatDeliberationStates.FAILED.equals(turn.getState())) {
            throw conflict("Turn no longer accepts a final");
        }
        ChatRequestEntity request = dao.findRequest(tenantId, ownerJiacn, clientId, turn.getRequestId());
        if (request == null || !Objects.equals(request.getConversationId(), turn.getConversationId())
                || !Objects.equals(request.getConversationGeneration(), turn.getConversationGeneration())) {
            throw unavailable();
        }
        long now = System.currentTimeMillis();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentId", agentId);
        metadata.put("requestId", requestId);
        metadata.put("turnId", turnId);
        metadata.put("dispatchId", dispatchId);
        metadata.put("contextSnapshotId", snapshotId);
        metadata.put("route", turn.getRoute());
        metadata.put("replyToMessageId", Long.toString(request.getUserMessageId()));
        metadata.put("targetAgentId", turn.getTargetAgentId());
        metadata.put("finalDigest", finalDigest);
        if (actionPrepared != null) metadata.put("outcomeId", actionFinals.outcomeId(actionPrepared));
        if (typedPrepared != null) metadata.put("outcomeId", typedDeliberation.outcomeId(typedPrepared));
        if (inspectionPrepared != null) metadata.put("outcomeId", typedInspection.outcomeId(inspectionPrepared));
        ChatMessageEntity message = new ChatMessageEntity()
                .setConversationId(turn.getConversationId()).setMessageType("ASSISTANT")
                .setContent(safeContent).setMetadata(JsonUtil.toJson(metadata)).setJiacn(ownerJiacn)
                .setSyncStatus("PENDING").setConversationType(conversation.getConversationType())
                .setSenderType(trustedSender.type()).setSenderName(trustedSender.displayName());
        message.setTenantId(tenantId);
        message.setClientId(clientId);
        message.init4Creation();
        if (messageDao.insertScoped(tenantId, clientId, message) != 1 || message.getId() == null) {
            throw persistence("Unable to persist final message");
        }
        ChatTypedDeliberationService.Persisted typedPersisted = typedPrepared == null
                ? null : typedDeliberation.persist(typedPrepared, message.getId(), now);
        ChatTypedInspectionService.Persisted inspectionPersisted = inspectionPrepared == null
                ? null : typedInspection.persist(inspectionPrepared, message.getId(), now);
        Map<String, Object> actionPersisted = actionPrepared == null ? null : actionFinals.persist(actionPrepared, message.getId(), now);
        if (dao.persistFinal(turn, finalDigest, message.getId(), now) != 1) {
            throw conflict("Concurrent final state change");
        }
        String finalEventId = stableId("evt", dispatchId, "FINAL_PERSISTED");
        Map<String, Object> finalPayload = new LinkedHashMap<>();
        finalPayload.put("content", safeContent);
        finalPayload.put("messageId", Long.toString(message.getId()));
        finalPayload.put("agentId", trustedSender.agentId());
        finalPayload.put("senderType", trustedSender.type());
        finalPayload.put("senderName", trustedSender.displayName());
        if (actionPersisted != null) finalPayload.put("typedOutcome", actionPersisted);
        if (typedPersisted != null) finalPayload.put("typedOutcome", typedPersisted.eventView());
        if (inspectionPersisted != null) finalPayload.put("typedOutcome", inspectionPersisted.eventView());
        ChatConversationEventEntity finalEvent = persistEvent(turn, finalEventId, "agent_message",
                finalPayload, now);
        Map<String, Object> outboxPayload = new LinkedHashMap<>();
        outboxPayload.put("requestId", requestId); outboxPayload.put("turnId", turnId);
        outboxPayload.put("dispatchId", dispatchId); outboxPayload.put("targetAgentId", turn.getTargetAgentId());
        outboxPayload.put("contextSnapshotId", snapshotId); outboxPayload.put("messageId", Long.toString(message.getId()));
        outboxPayload.put("conversationId", conversationId);
        outboxPayload.put("conversationGeneration", Long.toString(conversationGeneration));
        outboxPayload.put("content", safeContent); outboxPayload.put("senderType", trustedSender.type());
        outboxPayload.put("senderName", trustedSender.displayName());
        outboxPayload.put("eventSequence", Long.toString(finalEvent.getEventSequence()));
        outboxPayload.put("eventVersion", Long.toString(finalEvent.getEventVersion()));
        outboxPayload.put("finalDigest", finalDigest);
        if (actionPersisted != null) outboxPayload.put("typedOutcome", actionPersisted);
        if (typedPersisted != null) outboxPayload.put("typedOutcome", typedPersisted.eventView());
        if (inspectionPersisted != null) outboxPayload.put("typedOutcome", inspectionPersisted.eventView());
        ChatDispatchOutboxEntity outbox = new ChatDispatchOutboxEntity()
                .setEventId(finalEventId)
                .setTenantId(tenantId).setOwnerJiacn(ownerJiacn).setClientId(clientId)
                .setTurnId(turnId).setDispatchId(dispatchId).setEventType("FINAL_PERSISTED")
                .setStatus("READY").setPayloadJson(CanonicalContextJson.write(outboxPayload)).setVersion(0L).setAvailableAt(now)
                .setAttemptCount(0).setFencingToken(0L).setCreatedAt(now).setUpdatedAt(now);
        if (dao.insertOutbox(outbox) != 1) throw persistence("Unable to persist final event");
        turn.setState(ChatDeliberationStates.FINAL_PERSISTED).setFinalDigest(finalDigest)
                .setFinalMessageId(message.getId()).setUpdatedAt(now)
                .setStateVersion(turn.getStateVersion() + 1);
        refreshAggregate(tenantId, ownerJiacn, clientId, turn.getRequestId(), now);
        return new FinalResult(FinalStatus.PERSISTED, message.getId(),
                Long.toString(request.getUserMessageId()), finalEventId, turn, finalEvent);
    }

    @Transactional(readOnly = true)
    public RequestView getRequest(String tenantId, String ownerJiacn, String clientId, String requestId) {
        requireIdentity(tenantId, 50); requireIdentity(ownerJiacn, 50); requireIdentity(clientId, 50); requireIdentity(requestId, MAX_ID);
        ChatRequestEntity request = dao.findRequest(tenantId, ownerJiacn, clientId, requestId);
        if (request == null || !liveRequest(tenantId, ownerJiacn, clientId, request)) throw unavailable();
        return requestView(request, dao.findTurnsByRequest(tenantId, ownerJiacn, clientId, requestId));
    }

    @Transactional(readOnly = true)
    public TurnView getTurn(String tenantId, String ownerJiacn, String clientId, String turnId) {
        ChatTurnEntity turn = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        return turnView(turn);
    }

    @Transactional(rollbackFor = Exception.class)
    public TurnView cancelTurn(String tenantId, String ownerJiacn, String clientId, String turnId,
            Long expectedStateVersion, String reason) {
        ChatTurnEntity visible = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        requireLockedConversation(tenantId, ownerJiacn, clientId, visible.getConversationId(), visible.getConversationGeneration());
        ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, turnId);
        if (!terminal(turn.getState())) {
            if (expectedStateVersion != null && !expectedStateVersion.equals(turn.getStateVersion())) {
                throw conflict("Turn state version changed");
            }
            long now = System.currentTimeMillis();
            cancelLockedTurn(turn, normalizeCancelReason(reason), now);
            refreshAggregate(tenantId, ownerJiacn, clientId, turn.getRequestId(), now);
        }
        return turnView(turn);
    }

    @Transactional(rollbackFor = Exception.class)
    public RequestView cancelPending(String tenantId, String ownerJiacn, String clientId, String requestId,
            List<String> selectedTurnIds, String reason) {
        requireIdentity(tenantId, 50); requireIdentity(ownerJiacn, 50); requireIdentity(clientId, 50); requireIdentity(requestId, MAX_ID);
        ChatRequestEntity request = dao.findRequest(tenantId, ownerJiacn, clientId, requestId);
        if (request == null) throw unavailable();
        requireLockedConversation(tenantId, ownerJiacn, clientId, request.getConversationId(), request.getConversationGeneration());
        List<ChatTurnEntity> turns = dao.findTurnsByRequest(tenantId, ownerJiacn, clientId, requestId);
        if (turns.isEmpty()) throw unavailable();
        Set<String> selected = normalizeTurnSelection(selectedTurnIds);
        if (!selected.isEmpty()) {
            Set<String> owned = turns.stream().map(ChatTurnEntity::getTurnId)
                    .collect(java.util.stream.Collectors.toSet());
            if (!owned.containsAll(selected)) throw unavailable();
        }
        String safeReason = normalizeCancelReason(reason);
        long now = System.currentTimeMillis();
        for (ChatTurnEntity candidate : turns) {
            if (!selected.isEmpty() && !selected.contains(candidate.getTurnId())) continue;
            ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, candidate.getTurnId());
            if (!terminal(turn.getState())) cancelLockedTurn(turn, safeReason, now);
        }
        refreshAggregate(tenantId, ownerJiacn, clientId, requestId, now);
        ChatRequestEntity refreshed = dao.findRequest(tenantId, ownerJiacn, clientId, requestId);
        return requestView(refreshed, dao.findTurnsByRequest(tenantId, ownerJiacn, clientId, requestId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markPublished(String tenantId, String ownerJiacn, String clientId, String turnId) {
        ChatTurnEntity visible = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        requireLockedConversation(tenantId, ownerJiacn, clientId, visible.getConversationId(), visible.getConversationGeneration());
        ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, turnId);
        if (ChatDeliberationStates.PUBLISHED.equals(turn.getState())) return;
        if (!ChatDeliberationStates.FINAL_PERSISTED.equals(turn.getState())) {
            throw conflict("Only a persisted final can be published");
        }
        long now = System.currentTimeMillis();
        if (dao.publishFinal(turn, now) != 1) {
            throw conflict("Concurrent final publication state change");
        }
        turn.setState(ChatDeliberationStates.PUBLISHED).setUpdatedAt(now)
                .setStateVersion(turn.getStateVersion() + 1);
    }

    @Transactional(rollbackFor = Exception.class)
    public void failBuiltinRecovery(String tenantId, String ownerJiacn, String clientId, String turnId) {
        ChatTurnEntity visible = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        requireLockedConversation(tenantId, ownerJiacn, clientId, visible.getConversationId(),
                visible.getConversationGeneration());
        ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, turnId);
        if (terminal(turn.getState())) return;
        long now = System.currentTimeMillis();
        String reason = "BUILTIN_WORKER_RESTART_DURING_GENERATION";
        if (dao.updateTurnState(turn, ChatDeliberationStates.FAILED, reason, now) != 1) {
            throw conflict("Concurrent builtin recovery state change");
        }
        turn.setState(ChatDeliberationStates.FAILED).setTerminalReason(reason)
                .setStateVersion(turn.getStateVersion() + 1).setUpdatedAt(now);
        persistEvent(turn, stableId("evt", turn.getDispatchId(), "BUILTIN_RECOVERY_REQUIRED"),
                "resync_required", Map.of("reason", reason), now);
        refreshAggregate(tenantId, ownerJiacn, clientId, turn.getRequestId(), now);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEventEntity failTargetCapability(String tenantId, String ownerJiacn,
            String clientId, String turnId, String reason, Map<String, Object> negotiatedProfile) {
        requireIdentity(tenantId, 50); requireIdentity(ownerJiacn, 50);
        requireIdentity(clientId, 50); requireIdentity(turnId, MAX_ID);
        if (!CAPABILITY_FAILURE_REASONS.contains(reason)) throw invalid("Invalid capability failure reason");
        ChatTurnEntity visible = requireVisibleTurn(tenantId, ownerJiacn, clientId, turnId);
        requireLockedConversation(tenantId, ownerJiacn, clientId, visible.getConversationId(),
                visible.getConversationGeneration());
        ChatTurnEntity turn = requireLockedTurn(tenantId, ownerJiacn, clientId, turnId);
        if (terminal(turn.getState())) return null;
        long now = System.currentTimeMillis();
        if (dao.updateTurnState(turn, ChatDeliberationStates.FAILED, reason, now) != 1) {
            throw conflict("Concurrent target capability state change");
        }
        turn.setState(ChatDeliberationStates.FAILED).setTerminalReason(reason)
                .setStateVersion(turn.getStateVersion() + 1).setUpdatedAt(now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("targetCapability", capabilityEventView(negotiatedProfile));
        ChatConversationEventEntity event = persistEvent(turn,
                stableId("evt", turn.getDispatchId(), "TARGET_CAPABILITY_UNAVAILABLE"),
                "target_capability_unavailable", payload, now);
        refreshAggregate(tenantId, ownerJiacn, clientId, turn.getRequestId(), now);
        return event;
    }

    private Map<String, Object> capabilityEventView(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        copyCapabilityScalar(input, result, "decision", String.class);
        copyCapabilityScalar(input, result, "profile", String.class);
        copyCapabilityScalar(input, result, "capabilityContractVersion", Number.class);
        Object policyValue = input.get("policy");
        if (policyValue instanceof Map<?, ?> policy) {
            Map<String, Object> safePolicy = new LinkedHashMap<>();
            copyCapabilityScalar(policy, safePolicy, "supported", Boolean.class);
            copyCapabilityScalar(policy, safePolicy, "enabled", Boolean.class);
            copyCapabilityScalar(policy, safePolicy, "toolPolicy", String.class);
            copyCapabilityScalar(policy, safePolicy, "manifestPolicy", String.class);
            copyCapabilityScalar(policy, safePolicy, "strictNoToolsVerified", Boolean.class);
            if (!safePolicy.isEmpty()) result.put("policy", Map.copyOf(safePolicy));
        }
        return Map.copyOf(result);
    }

    private void copyCapabilityScalar(Map<?, ?> source, Map<String, Object> target,
            String key, Class<?> type) {
        Object value = source.get(key);
        if (type.isInstance(value)) target.put(key, value);
    }

    private void cancelLockedTurn(ChatTurnEntity turn, String reason, long now) {
        if (InteractionRoute.EXECUTE.name().equals(turn.getRoute())) throw unavailable();
        if (dao.updateTurnState(turn, ChatDeliberationStates.CANCELLED, reason, now) != 1) {
            throw conflict("Concurrent cancellation");
        }
        ChatDispatchOutboxEntity cancel = new ChatDispatchOutboxEntity()
                .setEventId(stableId("evt", turn.getDispatchId(), "CANCEL_REQUESTED"))
                .setTenantId(turn.getTenantId()).setOwnerJiacn(turn.getOwnerJiacn()).setClientId(turn.getClientId())
                .setTurnId(turn.getTurnId()).setDispatchId(turn.getDispatchId()).setEventType("CANCEL_REQUESTED")
                .setStatus("READY").setPayloadJson(CanonicalContextJson.write(Map.ofEntries(
                        Map.entry("tenantId", turn.getTenantId()), Map.entry("ownerJiacn", turn.getOwnerJiacn()),
                        Map.entry("clientId", turn.getClientId()), Map.entry("requestId", turn.getRequestId()),
                        Map.entry("turnId", turn.getTurnId()), Map.entry("dispatchId", turn.getDispatchId()),
                        Map.entry("targetAgentId", turn.getTargetAgentId()),
                        Map.entry("conversationId", turn.getConversationId()),
                        Map.entry("conversationGeneration", Long.toString(turn.getConversationGeneration())),
                        Map.entry("reason", reason))))
                .setVersion(0L).setAvailableAt(now).setAttemptCount(0).setFencingToken(0L)
                .setCreatedAt(now).setUpdatedAt(now);
        persistEvent(turn, stableId("evt", turn.getDispatchId(), "CANCEL_REQUESTED"),
                "cancel_requested", Map.of("reason", reason), now);
        if (dao.insertOutbox(cancel) != 1) throw persistence("Unable to persist cancellation event");
        turn.setState(ChatDeliberationStates.CANCELLED).setTerminalReason(reason)
                .setUpdatedAt(now).setStateVersion(turn.getStateVersion() + 1);
    }

    private ChatConversationEventEntity persistEvent(ChatTurnEntity turn, String eventId,
            String eventType, Map<String, Object> payload, long now) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", eventType);
        wire.put("requestId", turn.getRequestId());
        wire.put("requestRevision", Long.toString(turn.getRequestRevision()));
        wire.put("turnId", turn.getTurnId());
        wire.put("dispatchId", turn.getDispatchId());
        wire.put("targetAgentId", turn.getTargetAgentId());
        wire.put("contextSnapshotId", turn.getSnapshotId());
        wire.put("route", turn.getRoute());
        wire.putAll(payload);
        ChatConversationEventEntity event = new ChatConversationEventEntity()
                .setEventId(eventId).setTenantId(turn.getTenantId()).setOwnerJiacn(turn.getOwnerJiacn())
                .setClientId(turn.getClientId()).setConversationId(turn.getConversationId())
                .setConversationGeneration(turn.getConversationGeneration()).setRequestId(turn.getRequestId())
                .setTurnId(turn.getTurnId()).setDispatchId(turn.getDispatchId()).setEventType(eventType)
                .setEventVersion(0L).setPayloadJson(CanonicalContextJson.write(wire)).setOccurredAt(now);
        if (dao.insertEvent(event) != 1 || event.getEventSequence() == null
                || dao.assignEventVersion(event.getEventSequence()) != 1) {
            throw persistence("Unable to persist conversation event");
        }
        event.setEventVersion(event.getEventSequence());
        return event;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public ChatConversationEventEntity persistActionStarted(ChatActionFinalService.BoundAction action,
            String childRequestId, String route, long now) {
        var parent = action.outcome(); var scope = parent.scope();
        var turn = requireLockedTurn(scope.tenantId(), scope.ownerJiacn(), scope.clientId(), parent.turnId());
        if (!parent.finalDigest().equals(turn.getFinalDigest())) throw conflict("Action parent changed");
        String actionId = ChatActionFinalValidator.actionEventId(action.validated());
        return persistEvent(turn, stableId("evt", actionId, "ACTION_STARTED"), "action_started", Map.of(
                "actionRequestId", actionId, "parentOutcomeId", parent.outcomeId(),
                "childRequestId", childRequestId, "childRoute", route), now);
    }

    public ChatConversationEventEntity persistTypedQuestionAnswered(ChatTurnEntity turn,
            String pendingQuestionId, long stateVersion, String replyRequestId,
            String parentOutcomeId, long now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pendingQuestionId", pendingQuestionId);
        payload.put("state", "ANSWERED");
        payload.put("stateVersion", Long.toString(stateVersion));
        payload.put("replyRequestId", replyRequestId);
        payload.put("parentOutcomeId", parentOutcomeId);
        return persistEvent(turn, stableId("evt", pendingQuestionId, "ANSWERED"),
                "typed_question_answered", payload, now);
    }

    @Transactional(readOnly = true)
    public long eventHighWatermark(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long generation) {
        requireIdentity(tenantId, 50); requireIdentity(ownerJiacn, 50); requireIdentity(clientId, 50);
        requireIdentity(conversationId, MAX_ID);
        if (generation < 1) throw invalid("Invalid event cursor");
        requireReadableConversation(tenantId, ownerJiacn, clientId, conversationId, generation);
        return dao.eventHighWatermark(tenantId, ownerJiacn, clientId, conversationId, generation);
    }

    @Transactional(readOnly = true)
    public List<ChatConversationEventEntity> replayEvents(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long generation, long afterSequence, int limit) {
        long highWatermark = eventHighWatermark(tenantId, ownerJiacn, clientId, conversationId, generation);
        return replayEventsThrough(tenantId, ownerJiacn, clientId, conversationId, generation,
                afterSequence, highWatermark, limit);
    }

    @Transactional(readOnly = true)
    public List<ChatConversationEventEntity> replayEventsThrough(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long generation, long afterSequence, long throughSequence, int limit) {
        requireIdentity(tenantId, 50); requireIdentity(ownerJiacn, 50); requireIdentity(clientId, 50);
        requireIdentity(conversationId, MAX_ID);
        if (generation < 1 || afterSequence < 0 || throughSequence < afterSequence
                || limit < 1 || limit > 500) throw invalid("Invalid event cursor");
        requireReadableConversation(tenantId, ownerJiacn, clientId, conversationId, generation);
        return dao.replayEvents(tenantId, ownerJiacn, clientId, conversationId, generation,
                afterSequence, throughSequence, limit);
    }

    private Set<String> normalizeTurnSelection(List<String> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) return Set.of();
        if (turnIds.size() > 32) throw invalid("Too many turnIds");
        Set<String> result = new LinkedHashSet<>();
        for (String turnId : turnIds) result.add(requireIdentity(turnId, MAX_ID));
        return Set.copyOf(result);
    }

    private String normalizeCancelReason(String reason) {
        if (reason == null || reason.isBlank()) return "USER_REQUESTED";
        String safe = reason.strip();
        if (safe.length() > 500 || safe.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Invalid cancellation reason");
        }
        return safe;
    }

    private Admission loadAdmission(String tenantId, ChatRequestEntity request, boolean replay) {
        List<Dispatch> dispatches = new ArrayList<>();
        for (ChatTurnEntity turn : dao.findTurnsByRequest(
                tenantId, request.getOwnerJiacn(), request.getClientId(), request.getRequestId())) {
            ChatContextSnapshotEntity snapshot = dao.findSnapshot(
                    tenantId, request.getOwnerJiacn(), request.getClientId(), turn.getSnapshotId());
            if (snapshot == null) throw persistence("Existing turn snapshot is unavailable");
            dispatches.add(toDispatch(turn, snapshot));
        }
        if (dispatches.isEmpty()) throw persistence("Existing request has no turns");
        return new Admission(request.getRequestId(), request.getRequestRevision(),
                Long.toString(request.getUserMessageId()), request.getConversationId(),
                request.getConversationGeneration(), InteractionRoute.valueOf(dispatches.getFirst().route()),
                List.copyOf(dispatches), replay);
    }

    private Dispatch toDispatch(ChatTurnEntity turn, ChatContextSnapshotEntity snapshot) {
        return new Dispatch(turn.getRequestId(), turn.getTurnId(), turn.getDispatchId(),
                stableId("evt", turn.getDispatchId(), "DISPATCH"), turn.getTargetAgentId(),
                turn.getRoute(), turn.getState(), snapshot.getSnapshotId(), snapshot.getContextDigest(),
                parseJsonMap(snapshot.getSourceVectorJson()), parseJsonMap(snapshot.getFactsManifestJson()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        try {
            Object value = JsonUtil.getMapper().readValue(json, Map.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception e) {
            throw persistence("Stored context JSON is invalid");
        }
    }

    Map<String, Object> eventPayload(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long conversationGeneration, String requestId, long requestRevision,
            String turnId, String dispatchId, String targetAgentId, String snapshotId,
            String contextDigest, InteractionRoute route, String content, ChatMessageDTO input,
            JuyitingConversationScope scope, ServerResolvedSender sender,
            Map<String, Object> taskMaterials, long occurredAt,
            Map<String, Object> sourceVector, Map<String, Object> facts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("ownerJiacn", ownerJiacn);
        payload.put("clientId", clientId);
        payload.put("conversationId", conversationId);
        payload.put("conversationGeneration", Long.toString(conversationGeneration));
        payload.put("requestId", requestId);
        payload.put("requestRevision", Long.toString(requestRevision));
        payload.put("turnId", turnId);
        payload.put("dispatchId", dispatchId);
        payload.put("targetAgentId", targetAgentId);
        payload.put("contextSnapshotId", snapshotId);
        payload.put("contextHash", contextDigest);
        payload.put("route", route.name());
        payload.put("content", content);
        payload.put("conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        payload.put("agentId", targetAgentId);
        payload.put("senderType", sender.type());
        payload.put("senderName", sender.displayName());
        payload.put("metadata", trustedUserMetadata(
                input, scope, requestId, requestRevision, route, sender, taskMaterials));
        payload.put("sentAt", Long.toString(occurredAt));
        payload.put("timestamp", Long.toString(occurredAt));
        payload.put("conversationScopeType", scope.scopeType());
        payload.put("conversationScopeKey", scope.scopeKey());
        payload.put("taskId", scope.taskId());
        payload.put("sourceVector", sourceVector);
        payload.put("factsManifest", facts);
        if (taskMaterials != null) payload.put("taskMaterials", taskMaterials);
        return payload;
    }

    static String contextDigest(Map<String, Object> sourceVector, Map<String, Object> facts) {
        return digest(Map.of("sourceVector", sourceVector, "facts", facts));
    }

    private Map<String, Object> trustedUserMetadata(ChatMessageDTO input, JuyitingConversationScope scope,
            String requestId, long revision, InteractionRoute route,
            ServerResolvedSender sender, Map<String, Object> taskMaterials) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                ConversationMetadataPolicy.copyAllowed(input.getMetadata()));
        metadata.put("requestId", requestId);
        metadata.put("requestRevision", revision);
        metadata.put("route", route.name());
        metadata.put("conversationScopeType", scope.scopeType());
        metadata.put("conversationScopeKey", scope.scopeKey());
        metadata.put("targetAgentIds", scope.targetAgentIds());
        if (!scope.authoritativeAgentIds().isEmpty()) {
            metadata.put("participantAgentIds", scope.authoritativeAgentIds());
        }
        if (scope.taskId() != null) metadata.put("selectedTaskId", scope.taskId());
        if (taskMaterials != null) metadata.put("taskMaterials", taskMaterials);
        // Authenticated identity is appended after the client allowlist and always wins.
        metadata.put("senderType", sender.type());
        metadata.put("senderName", sender.displayName());
        metadata.put("jiacn", sender.jiacn());
        return metadata;
    }

    private AgentTaskDTO taskFacts(JuyitingConversationScope scope, String tenantId, String clientId) {
        if (scope.taskId() == null) return null;
        try {
            AgentTaskDTO task = agentService.getTask(scope.taskId());
            if (task == null || !scope.taskId().equals(task.getId())
                    || !tenantId.equals(task.getTenantId()) || !clientId.equals(task.getClientId())) {
                throw unavailable();
            }
            return task;
        } catch (ChatDeliberationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    private List<Map<String, Object>> authorizeInputRefs(List<Map<String, Object>> candidates,
            InteractionRoute route, ChatConversationEntity conversation, JuyitingConversationScope scope,
            List<ChatMessageEntity> messages, boolean trustedInspection) {
        if (candidates == null || candidates.isEmpty()) {
            if (route == InteractionRoute.INSPECT && !trustedInspection) throw invalid("INSPECT requires authorized inputRefs");
            return List.of();
        }
        Set<String> messageIds = new LinkedHashSet<>();
        for (ChatMessageEntity message : messages) {
            if (message != null && message.getId() != null) messageIds.add(Long.toString(message.getId()));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> ref : candidates) {
            if (ref == null || ref.size() != 2) throw unavailable();
            String type = ref.get("type") instanceof String value ? value : null;
            String id = ref.get("id") instanceof String value ? value : null;
            requireIdentity(type, 20); requireIdentity(id, MAX_ID);
            boolean allowed = switch (type) {
                case "conversation" -> Long.toString(conversation.getId()).equals(id);
                case "task" -> scope.taskId() != null && scope.taskId().equals(id);
                case "message" -> messageIds.contains(id);
                default -> false;
            };
            if (!allowed) throw unavailable();
            if (seen.add(type + "\0" + id)) result.add(Map.of("type", type, "id", id));
        }
        if (route == InteractionRoute.INSPECT && result.isEmpty() && !trustedInspection)
            throw invalid("INSPECT requires authorized inputRefs");
        return List.copyOf(result);
    }

    private Map<String, Object> sourceVector(long generation, long messageId, AgentTaskDTO task,
            Map<String, Object> authorizedContext) {
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("conversationGeneration", Long.toString(generation));
        vector.put("messageHighWatermark", Long.toString(messageId));
        vector.put("taskRevision", task == null || task.getTaskVersion() == null
                ? null : String.valueOf(task.getTaskVersion()));
        vector.put("executionRevision", null);
        vector.put("bindingVersion", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = authorizedContext.get("summary") instanceof Map<?, ?> value
                ? (Map<String, Object>) value : Map.of();
        // Existing durable wire treats summaryRevision as a canonical decimal Long.
        // Content-level changes are independently bound by authorizedHistoryDigest.
        Object ids = summary.get("sourceMessageIds");
        List<?> summaryIds = ids instanceof List<?> values ? values : List.of();
        vector.put("summaryRevision", summaryIds.isEmpty() ? "0"
                : String.valueOf(summaryIds.get(summaryIds.size() - 1)));
        vector.put("authorizedHistoryDigest", authorizedContext.get("historyDigest"));
        vector.put("sourceMessageIds", authorizedContext.get("sourceMessageIds"));
        vector.put("workspaceTreeSha", null);
        return vector;
    }

    private Map<String, Object> factsManifest(ChatConversationEntity conversation,
            JuyitingConversationScope scope, String targetAgentId, AgentTaskDTO task,
            Map<String, Object> taskMaterials, Map<String, Object> authorizedContext) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", "2");
        facts.put("conversation", Map.of(
                "id", Long.toString(conversation.getId()), "generation", Long.toString(conversation.getLifecycleGeneration()),
                "scopeType", scope.scopeType(), "scopeKey", scope.scopeKey()));
        facts.put("targetAgentId", targetAgentId);
        facts.put("participantAgentIds", scope.authoritativeAgentIds().isEmpty()
                ? scope.targetAgentIds() : scope.authoritativeAgentIds());
        facts.put("task", task == null ? null : taskManifest(task));
        facts.put("taskMaterials", sanitizeTaskMaterials(taskMaterials));
        facts.put("authorizedContext", authorizedContext);
        facts.put("responsePolicy", Map.of(
                "toolPolicy", "read-only-constrained",
                "strictNoToolsVerified", false,
                "mustNotClaimUnmaterializedReferenceRead", true,
                "mustStateMissingFacts", true));
        return facts;
    }

    private Map<String, Object> authorizedContext(String tenantId, String ownerJiacn, String clientId,
            String conversationId, String targetAgentId, List<ChatMessageEntity> existingMessages,
            ChatMessageEntity currentUserMessage, List<Map<String, Object>> inputRefs,
            Map<String, Object> taskMaterials) {
        List<Map<String, Object>> authorized = new ArrayList<>();
        for (ChatMessageEntity message : existingMessages) {
            if (message != null && Objects.equals(message.getId(), currentUserMessage.getId())) continue;
            Map<String, Object> safe = authorizedHistoryMessage(
                    tenantId, ownerJiacn, clientId, conversationId, targetAgentId, message);
            if (safe != null) authorized.add(safe);
        }
        int recentFrom = Math.max(0, authorized.size() - RECENT_HISTORY_LIMIT);
        List<Map<String, Object>> older = List.copyOf(authorized.subList(0, recentFrom));
        List<Map<String, Object>> recent = List.copyOf(authorized.subList(recentFrom, authorized.size()));
        Map<String, Object> summary = extractiveSummary(older);
        Map<String, Object> current = messageView(currentUserMessage, "USER");
        List<String> sourceIds = authorized.stream().map(value -> String.valueOf(value.get("messageId"))).toList();
        List<Map<String, Object>> digestVector = new ArrayList<>();
        for (Map<String, Object> message : authorized) {
            digestVector.add(Map.of(
                    "messageId", message.get("messageId"),
                    "role", message.get("role"),
                    "contentHash", message.get("contentHash")));
        }
        digestVector.add(Map.of(
                "messageId", current.get("messageId"),
                "role", current.get("role"),
                "contentHash", current.get("contentHash")));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("summary", summary);
        context.put("recentMessages", recent);
        context.put("currentUserMessage", current);
        context.put("sourceMessageIds", sourceIds);
        context.put("historyDigest", digest(digestVector));
        context.put("availableRefs", availableRefs(inputRefs, taskMaterials));
        context.put("materializedRefs", List.of());
        if (factBytes(context) <= CLIENT_FACT_VALUE_BYTES) return Map.copyOf(context);
        return boundContext(context, older, recent, sourceIds);
    }

    private int factBytes(Object value) {
        return CanonicalContextJson.write(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private String utf8Prefix(String text, int maxBytes) {
        int used = 0;
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            int next = Character.charCount(codePoint);
            int length = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + length > maxBytes) break;
            used += length;
            offset += next;
        }
        return text.substring(0, offset);
    }

    private Map<String, Object> boundContext(Map<String, Object> full,
            List<Map<String, Object>> older, List<Map<String, Object>> recent, List<String> sourceIds) {
        Map<String, Object> bounded = new LinkedHashMap<>();
        Map<String, Object> originalCurrent = castContextMap(full.get("currentUserMessage"));
        bounded.put("currentUserMessage", Map.of(
                "messageId", originalCurrent.get("messageId"), "role", "USER",
                "contentHash", originalCurrent.get("contentHash"), "contentSource", "dispatch.content"));
        bounded.put("historyDigest", full.get("historyDigest"));
        bounded.put("coverage", "BOUNDED_EXTRACTIVE_NOT_COMPLETE");
        bounded.put("sourceMessageIds", sourceIds.size() <= 2 ? sourceIds
                : List.of(sourceIds.getFirst(), sourceIds.getLast()));
        bounded.put("sourceMessageCount", sourceIds.size());
        bounded.put("sourceMessageIdsDigest", digest(sourceIds));
        bounded.put("materializedRefs", List.of());
        if (older.isEmpty()) {
            bounded.put("summary", full.get("summary"));
        } else {
            Map<String, Object> last = older.getLast();
            String excerpt = utf8Prefix((String) last.get("content"), CLIENT_FACT_VALUE_BYTES / 8);
            bounded.put("summary", Map.of(
                    "kind", "bounded-extractive-v1", "content", excerpt,
                    "contentHash", "sha256:" + sha256(excerpt),
                    "sourceMessageIds", List.of(last.get("messageId")),
                    "sources", List.of(Map.of("messageId", last.get("messageId"),
                            "contentHash", last.get("contentHash"))),
                    "sourceCount", older.size(), "omittedSourceCount", older.size() - 1,
                    "sourceDigest", digest(older), "truncated", true));
        }
        List<Map<String, Object>> boundedRecent = new ArrayList<>();
        for (Map<String, Object> message : recent) {
            boundedRecent.add(new LinkedHashMap<>(Map.of(
                    "messageId", message.get("messageId"), "role", message.get("role"),
                    "contentHash", message.get("contentHash"), "contentTruncated", true)));
        }
        bounded.put("recentMessages", boundedRecent);
        bounded.put("recentOmittedCount", 0);
        bounded.put("availableRefs", new ArrayList<Map<String, Object>>());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) full.get("availableRefs");
        bounded.put("availableRefsOmittedCount", refs.size());
        // History and references remain bound by hashes/IDs even when the wire displays excerpts.
        while (factBytes(bounded) > CLIENT_FACT_VALUE_BYTES && !boundedRecent.isEmpty()) {
            boundedRecent.removeFirst();
            bounded.put("recentOmittedCount", (int) bounded.get("recentOmittedCount") + 1);
        }
        if (factBytes(bounded) > CLIENT_FACT_VALUE_BYTES) {
            Map<String, Object> summary = castContextMap(bounded.get("summary"));
            bounded.put("summary", Map.of("kind", "bounded-extractive-v1", "content", "",
                    "contentHash", "sha256:" + sha256(""), "sourceCount", older.size(),
                    "omittedSourceCount", older.size(), "sourceDigest", digest(older),
                    "truncated", true));
        }
        if (factBytes(bounded) > CLIENT_FACT_VALUE_BYTES) {
            throw persistence("Bounded context exceeds the negotiated Client wire format");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keptRefs = (List<Map<String, Object>>) bounded.get("availableRefs");
        for (Map<String, Object> ref : refs) {
            keptRefs.add(ref);
            bounded.put("availableRefsOmittedCount", refs.size() - keptRefs.size());
            if (factBytes(bounded) <= CLIENT_FACT_VALUE_BYTES) continue;
            keptRefs.removeLast();
            bounded.put("availableRefsOmittedCount", refs.size() - keptRefs.size());
            break;
        }
        // Spend only the remaining protocol space on the most recent *data* first. Old message
        // text never becomes a system instruction and full source hashes remain recoverable.
        for (int index = boundedRecent.size() - 1; index >= 0; index--) {
            Map<String, Object> item = boundedRecent.get(index);
            int originalIndex = recent.size() - boundedRecent.size() + index;
            String original = (String) recent.get(originalIndex).get("content");
            int room = Math.max(0, CLIENT_FACT_VALUE_BYTES - factBytes(bounded) - 128);
            String excerpt = utf8Prefix(original, room);
            if (excerpt.isEmpty()) break;
            item.put("content", excerpt);
            item.put("contentTruncated", excerpt.length() != original.length());
            if (factBytes(bounded) > CLIENT_FACT_VALUE_BYTES) {
                item.remove("content");
                item.put("contentTruncated", true);
                break;
            }
        }
        return Map.copyOf(bounded);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castContextMap(Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> authorizedHistoryMessage(String tenantId, String ownerJiacn,
            String clientId, String conversationId, String targetAgentId, ChatMessageEntity message) {
        if (message == null || message.getId() == null
                || !Objects.equals(tenantId, message.getTenantId())
                || !Objects.equals(ownerJiacn, message.getJiacn())
                || !Objects.equals(clientId, message.getClientId())
                || !Objects.equals(conversationId, message.getConversationId())
                || message.getContent() == null || message.getContent().isBlank()
                || message.getContent().length() > MAX_HISTORY_CONTENT) return null;
        if ("USER".equals(message.getMessageType())) return messageView(message, "USER");
        if (!"ASSISTANT".equals(message.getMessageType())
                || !assistantTargets(message.getMetadata(), targetAgentId)) return null;
        return messageView(message, "ASSISTANT");
    }

    private boolean assistantTargets(String metadataJson, String targetAgentId) {
        if (metadataJson == null || metadataJson.isBlank()) return false;
        try {
            Object parsed = JsonUtil.getMapper().readValue(metadataJson, Map.class);
            if (!(parsed instanceof Map<?, ?> metadata)) return false;
            Object target = metadata.get("targetAgentId");
            Object agent = metadata.get("agentId");
            if (target != null && !(target instanceof String)) return false;
            if (agent != null && !(agent instanceof String)) return false;
            if (target instanceof String targetValue && agent instanceof String agentValue
                    && !targetValue.equals(agentValue)) return false;
            String binding = target instanceof String value ? value
                    : agent instanceof String value ? value : null;
            return targetAgentId.equals(binding);
        } catch (Exception malformed) {
            return false;
        }
    }

    private Map<String, Object> messageView(ChatMessageEntity message, String role) {
        String content = message.getContent();
        return Map.of(
                "messageId", Long.toString(message.getId()),
                "role", role,
                "content", content,
                "contentHash", "sha256:" + sha256(content));
    }

    private Map<String, Object> extractiveSummary(List<Map<String, Object>> older) {
        StringBuilder content = new StringBuilder();
        List<String> sourceIds = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> message : older) {
            String body = String.valueOf(message.get("content"));
            String excerpt = body.substring(0, Math.min(body.length(), SUMMARY_EXCERPT_LIMIT));
            String line = "[" + message.get("role") + " " + message.get("messageId") + "] " + excerpt;
            int remaining = SUMMARY_CONTENT_LIMIT - content.length();
            if (remaining <= 0) break;
            if (!content.isEmpty()) {
                if (remaining == 1) break;
                content.append('\n');
                remaining--;
            }
            if (line.length() > remaining) line = line.substring(0, remaining);
            content.append(line);
            String id = String.valueOf(message.get("messageId"));
            sourceIds.add(id);
            sources.add(Map.of("messageId", id, "contentHash", message.get("contentHash")));
            if (content.length() >= SUMMARY_CONTENT_LIMIT) break;
        }
        String summaryContent = content.toString();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", "extractive-v1");
        summary.put("content", summaryContent);
        summary.put("sourceMessageIds", List.copyOf(sourceIds));
        summary.put("sources", List.copyOf(sources));
        summary.put("contentHash", "sha256:" + sha256(summaryContent));
        return Map.copyOf(summary);
    }

    private List<Map<String, Object>> availableRefs(List<Map<String, Object>> inputRefs,
            Map<String, Object> taskMaterials) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (inputRefs != null) refs.addAll(inputRefs);
        Map<String, Object> materials = sanitizeTaskMaterials(taskMaterials);
        Object itemsValue = materials.get("items");
        if (itemsValue instanceof List<?> items) {
            for (Object itemValue : items) {
                if (!(itemValue instanceof Map<?, ?> item)) continue;
                refs.add(Map.of(
                        "type", "taskMaterial",
                        "fileId", item.get("fileId"),
                        "version", item.get("version"),
                        "role", item.get("role")));
            }
        }
        return List.copyOf(refs);
    }

    private Map<String, Object> sanitizeTaskMaterials(Map<String, Object> taskMaterials) {
        if (taskMaterials == null) return Map.of("status", "UNAVAILABLE", "complete", false, "items", List.of());
        Object statusValue = taskMaterials.get("status");
        String status = statusValue instanceof String value
                && ("AVAILABLE".equals(value) || "UNAVAILABLE".equals(value)) ? value : "UNAVAILABLE";
        boolean complete = taskMaterials.get("complete") instanceof Boolean value && value;
        List<Map<String, Object>> items = new ArrayList<>();
        Object rawItems = taskMaterials.get("items");
        if (rawItems instanceof List<?> list) {
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> item)) continue;
                Object fileId = item.get("fileId");
                Object version = item.get("version");
                Object role = item.get("role");
                if (!(fileId instanceof String id) || id.isBlank() || !id.equals(id.strip())
                        || id.length() > MAX_ID || id.chars().anyMatch(Character::isISOControl)
                        || !(version instanceof Number number) || number.longValue() < 1
                        || !(role instanceof String roleValue)
                        || !("INPUT".equals(roleValue) || "REFERENCE".equals(roleValue))) continue;
                items.add(Map.of("fileId", id, "version", number.longValue(), "role", roleValue));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("complete", complete);
        result.put("items", items);
        if (factBytes(result) > CLIENT_FACT_VALUE_BYTES) {
            int originalCount = items.size();
            result.put("complete", false);
            result.put("omittedCount", 0);
            while (!items.isEmpty() && factBytes(result) > CLIENT_FACT_VALUE_BYTES) {
                items.removeLast();
                result.put("omittedCount", originalCount - items.size());
            }
        }
        result.put("items", List.copyOf(items));
        return Map.copyOf(result);
    }

    private Map<String, Object> taskManifest(AgentTaskDTO task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", task.getId());
        value.put("title", task.getTitle());
        value.put("status", task.getStatus());
        value.put("assignedAgentId", task.getAssignedAgentId());
        value.put("assignedAgentIds", Optional.ofNullable(task.getAssignedAgentIds()).orElse(List.of()));
        value.put("revision", task.getTaskVersion() == null ? null : String.valueOf(task.getTaskVersion()));
        return value;
    }

    private void requireCallbackBinding(ChatTurnEntity turn, String tenantId, String ownerJiacn,
            String clientId, String conversationId, long conversationGeneration, String agentId,
            String requestId, String dispatchId, String snapshotId, String contextDigest) {
        requireIdentity(tenantId, 50); requireIdentity(ownerJiacn, 50); requireIdentity(clientId, 50);
        if (conversationGeneration < 1
                || !Objects.equals(turn.getTenantId(), tenantId)
                || !Objects.equals(turn.getOwnerJiacn(), ownerJiacn)
                || !Objects.equals(turn.getClientId(), clientId)
                || !Objects.equals(turn.getConversationId(), requireIdentity(conversationId, MAX_ID))
                || !Objects.equals(turn.getConversationGeneration(), conversationGeneration)
                || !Objects.equals(turn.getTargetAgentId(), requireIdentity(agentId, MAX_ID))
                || !Objects.equals(turn.getRequestId(), requireIdentity(requestId, MAX_ID))
                || !Objects.equals(turn.getDispatchId(), requireIdentity(dispatchId, MAX_ID))
                || !Objects.equals(turn.getSnapshotId(), requireIdentity(snapshotId, MAX_ID))
                || !Objects.equals(turn.getContextDigest(), requireIdentity(contextDigest, 100))) {
            throw unavailable();
        }
        ChatContextSnapshotEntity snapshot = dao.findSnapshot(tenantId, ownerJiacn, clientId, snapshotId);
        if (snapshot == null || !Objects.equals(snapshot.getConversationId(), conversationId)
                || !Objects.equals(snapshot.getConversationGeneration(), conversationGeneration)
                || !Objects.equals(snapshot.getRequestId(), requestId)
                || !Objects.equals(snapshot.getRequestRevision(), turn.getRequestRevision())
                || !Objects.equals(snapshot.getTargetAgentId(), agentId)
                || !Objects.equals(snapshot.getContextDigest(), contextDigest)) {
            throw unavailable();
        }
    }

    /** Event replay is read-only: SELECT FOR UPDATE is rejected by MySQL read-only transactions.
     * Keep the same exact owner/tenant/lifecycle fence without acquiring a mutation lock.
     * Admission, cancellation and result mutations continue to use requireLockedConversation.
     */
    private ChatConversationEntity requireReadableConversation(String tenantId, String owner, String client,
            String conversationId, long generation) {
        ChatConversationEntity conversation;
        try {
            conversation = conversationDao.findScopedById(owner, client, conversationId);
        } catch (RuntimeException e) {
            throw unavailable();
        }
        return requireConversationGeneration(conversation, tenantId, generation);
    }

    private ChatConversationEntity requireLockedConversation(String tenantId, String owner, String client,
            String conversationId, long generation) {
        ChatConversationEntity conversation;
        try {
            conversation = conversationDao.lockScopedById(owner, client, conversationId);
        } catch (RuntimeException e) {
            throw unavailable();
        }
        return requireConversationGeneration(conversation, tenantId, generation);
    }

    private ChatConversationEntity requireConversationGeneration(ChatConversationEntity conversation,
            String tenantId, long generation) {
        if (conversation == null || conversation.getDeletedAt() != null
                || !tenantId.equals(conversation.getTenantId())
                || conversation.getLifecycleGeneration() == null
                || conversation.getLifecycleGeneration() != generation) {
            throw unavailable();
        }
        return conversation;
    }

    private void requireConversationScope(ChatConversationEntity conversation,
            JuyitingConversationScope scope) {
        if (!JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(conversation.getConversationType())
                || !Objects.equals(conversation.getConversationScopeType(), scope.scopeType())
                || !Objects.equals(conversation.getConversationScopeKey(), scope.scopeKey())
                || !Objects.equals(conversation.getTaskId(), scope.taskId())) throw unavailable();
        List<String> persisted;
        try {
            Object parsed = JsonUtil.getMapper().readValue(conversation.getTargetAgentIds(), List.class);
            persisted = parsed instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        } catch (Exception e) {
            throw unavailable();
        }
        if (scope.targetAgentIds().isEmpty() || !persisted.containsAll(scope.targetAgentIds())) throw unavailable();
    }

    private ChatTurnEntity requireVisibleTurn(String tenantId, String owner, String client, String turnId) {
        requireIdentity(tenantId, 50); requireIdentity(owner, 50); requireIdentity(client, 50); requireIdentity(turnId, MAX_ID);
        ChatTurnEntity turn = dao.findTurn(tenantId, owner, client, turnId);
        if (turn == null || !liveTurn(tenantId, owner, client, turn)) throw unavailable();
        return turn;
    }

    private ChatTurnEntity requireLockedTurn(String tenantId, String owner, String client, String turnId) {
        ChatTurnEntity turn = dao.lockTurn(tenantId, owner, client, turnId);
        if (turn == null) throw unavailable();
        return turn;
    }

    private boolean liveTurn(String tenantId, String owner, String client, ChatTurnEntity turn) {
        try {
            ChatConversationEntity conversation = conversationDao.findScopedById(
                    owner, client, turn.getConversationId());
            return conversation != null && conversation.getDeletedAt() == null
                    && Objects.equals(tenantId, conversation.getTenantId())
                    && Objects.equals(conversation.getLifecycleGeneration(), turn.getConversationGeneration());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean liveRequest(String tenantId, String owner, String client, ChatRequestEntity request) {
        try {
            ChatConversationEntity conversation = conversationDao.findScopedById(
                    owner, client, request.getConversationId());
            return conversation != null && conversation.getDeletedAt() == null
                    && Objects.equals(tenantId, conversation.getTenantId())
                    && Objects.equals(conversation.getLifecycleGeneration(), request.getConversationGeneration());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void refreshAggregate(String tenantId, String owner, String client, String requestId, long now) {
        ChatRequestEntity request = dao.findRequest(tenantId, owner, client, requestId);
        if (request == null) throw unavailable();
        List<ChatTurnEntity> turns = dao.findTurnsByRequest(tenantId, owner, client, requestId);
        String state = aggregate(turns);
        if (!Objects.equals(state, request.getAggregateState())
                && dao.updateRequestState(request, state, now) != 1) {
            throw conflict("Concurrent request aggregate change");
        }
    }

    static String aggregate(List<ChatTurnEntity> turns) {
        if (turns == null || turns.isEmpty()) return ChatDeliberationStates.FAILED;
        long completed = turns.stream().filter(t -> ChatDeliberationStates.FINAL_PERSISTED.equals(t.getState())
                || ChatDeliberationStates.PUBLISHED.equals(t.getState())).count();
        long cancelled = turns.stream().filter(t -> ChatDeliberationStates.CANCELLED.equals(t.getState())).count();
        long failed = turns.stream().filter(t -> ChatDeliberationStates.FAILED.equals(t.getState())).count();
        if (completed == turns.size()) return ChatDeliberationStates.COMPLETED;
        if (cancelled == turns.size()) return ChatDeliberationStates.CANCELLED;
        if (failed == turns.size()) return ChatDeliberationStates.FAILED;
        if (completed > 0 || cancelled > 0 || failed > 0) return ChatDeliberationStates.PARTIAL;
        return ChatDeliberationStates.RUNNING;
    }

    private RequestView requestView(ChatRequestEntity request, List<ChatTurnEntity> turns) {
        List<StepView> scopedSteps = interactionStepViews(request);
        return new RequestView(request.getRequestId(), Long.toString(request.getRequestRevision()), request.getConversationId(),
                Long.toString(request.getConversationGeneration()), Long.toString(request.getUserMessageId()),
                request.getAggregateState(), Long.toString(request.getStateVersion()),
                turns.stream().map(this::turnView).toList(), scopedSteps);
    }

    private List<StepView> interactionStepViews(ChatRequestEntity request) {
        if (interactionSteps == null) {
            if ("PLANNING".equals(request.getAggregateState())) throw persistence("Interaction steps unavailable");
            return List.of(); // Old CHAT requests and existing manual service clients.
        }
        List<ChatInteractionStepStore.Step> candidates = interactionSteps.findSteps(
                request.getTenantId(), request.getOwnerJiacn(), request.getClientId(),
                request.getRequestId(), request.getRequestRevision());
        if (candidates == null || candidates.isEmpty()) {
            if ("PLANNING".equals(request.getAggregateState())) throw persistence("Interaction steps unavailable");
            return List.of();
        }
        List<StepView> result = new ArrayList<>(candidates.size());
        for (ChatInteractionStepStore.Step step : candidates) {
            if (step == null || !Objects.equals(request.getConversationId(), step.conversationId())
                    || !Objects.equals(request.getConversationGeneration(), step.conversationGeneration())
                    || !Objects.equals(request.getRequestId(), step.requestId())
                    || !Objects.equals(request.getRequestRevision(), step.requestRevision())
                    || !Objects.equals(request.getTenantId(), step.tenantId())
                    || !Objects.equals(request.getOwnerJiacn(), step.ownerJiacn())
                    || !Objects.equals(request.getClientId(), step.clientId())
                    || !Set.of("EXECUTE", "INSPECT", "CHAT").contains(step.kind())) {
                throw persistence("Interaction step scope mismatch");
            }
            ChatInteractionStepStore.ExecutionLink link = "EXECUTE".equals(step.kind())
                    ? interactionSteps.findLink(request.getTenantId(), request.getOwnerJiacn(),
                            request.getClientId(), step.stepId()) : null;
            if ("EXECUTE".equals(step.kind()) && (link == null
                    || !Objects.equals(step.stepId(), link.stepId())
                    || !Objects.equals(request.getTenantId(), link.tenantId())
                    || !Objects.equals(request.getOwnerJiacn(), link.ownerJiacn())
                    || !Objects.equals(request.getClientId(), link.clientId()))) {
                throw persistence("Interaction execution link unavailable");
            }
            result.add(new StepView(step.stepId(), Long.toString(step.stepNumber()), step.taskId(),
                    Long.toString(step.assignmentRevision()), step.targetAgentId(),
                    step.kind(), step.state(), Long.toString(step.stateVersion()),
                    link == null ? null : link.executionIntentId(),
                    link == null ? null : link.executionId(),
                    link == null ? null : link.state()));
        }
        return List.copyOf(result);
    }

    private TurnView turnView(ChatTurnEntity turn) {
        return new TurnView(turn.getTurnId(), turn.getRequestId(), Long.toString(turn.getRequestRevision()),
                turn.getConversationId(), Long.toString(turn.getConversationGeneration()), turn.getTargetAgentId(),
                turn.getSnapshotId(), turn.getDispatchId(), turn.getRoute(), turn.getState(),
                Long.toString(turn.getStateVersion()), Long.toString(turn.getLastDeltaSeq()), turn.getTerminalReason(),
                turn.getFinalMessageId() == null ? null : Long.toString(turn.getFinalMessageId()),
                Long.toString(turn.getCreatedAt()), Long.toString(turn.getUpdatedAt()));
    }

    private boolean terminal(String state) {
        return ChatDeliberationStates.FINAL_PERSISTED.equals(state)
                || ChatDeliberationStates.PUBLISHED.equals(state)
                || ChatDeliberationStates.FAILED.equals(state)
                || ChatDeliberationStates.CANCELLED.equals(state);
    }

    private ServerResolvedSender requireHumanSender(ServerResolvedSender sender) {
        if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())) throw unavailable();
        requireIdentity(sender.jiacn(), 50);
        requireIdentity(sender.clientId(), 50);
        requireDisplayName(sender.displayName());
        return sender;
    }

    private ServerResolvedAgentSender requireAgentSender(ServerResolvedAgentSender sender,
            String agentId, String ownerJiacn, String clientId) {
        if (sender == null || !ServerResolvedAgentSender.AGENT_TYPE.equals(sender.type())
                || !Objects.equals(agentId, sender.agentId())
                || !Objects.equals(ownerJiacn, sender.jiacn())
                || !Objects.equals(clientId, sender.clientId())) {
            throw unavailable();
        }
        requireIdentity(sender.agentId(), MAX_ID);
        requireDisplayName(sender.displayName());
        return sender;
    }

    private void requireDisplayName(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > 100
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw unavailable();
        }
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank() || content.length() > 200_000) {
            throw invalid("Chat content is required and bounded");
        }
        return content;
    }

    private String requireIdentity(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) throw unavailable();
        return value;
    }

    private String stableId(String prefix, String... parts) {
        return prefix + "_" + sha256(String.join("\u0000", parts)).substring(0, 40);
    }

    public static String digest(Object value) {
        return "sha256:" + sha256(CanonicalContextJson.write(value));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ChatDeliberationException invalid(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST, message);
    }
    private ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Chat request is unavailable");
    }
    private ChatDeliberationException conflict(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, message);
    }
    private ChatDeliberationException persistence(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR, message);
    }

    public record Admission(String requestId, long requestRevision, String userMessageId,
            String conversationId, long conversationGeneration, InteractionRoute route,
            List<Dispatch> dispatches, boolean replay) { }
    public record Dispatch(String requestId, String turnId, String dispatchId, String dispatchEventId,
            String targetAgentId, String route,
            String state, String contextSnapshotId, String contextHash,
            Map<String, Object> sourceVector, Map<String, Object> factsManifest) { }
    public enum DeltaStatus { ACCEPTED, DUPLICATE, CONFLICTING_DUPLICATE, STALE, GAP, TERMINAL }
    public record DeltaResult(DeltaStatus status, ChatTurnEntity turn, ChatConversationEventEntity event) { }
    public enum FinalStatus { PERSISTED, DUPLICATE }
    public record FinalResult(FinalStatus status, Long messageId, String replyToMessageId,
            String eventId, ChatTurnEntity turn, ChatConversationEventEntity event) { }
    public record RequestView(String requestId, String requestRevision, String conversationId,
            String conversationGeneration, String userMessageId, String state, String stateVersion,
            List<TurnView> turns, List<StepView> steps) { }
    public record StepView(String stepId, String stepNumber, String taskId,
            String assignmentRevision, String targetAgentId, String kind, String state,
            String stateVersion, String executionIntentId, String executionId, String executionState) { }
    public record TurnView(String turnId, String requestId, String requestRevision, String conversationId,
            String conversationGeneration, String targetAgentId, String contextSnapshotId,
            String dispatchId, String route, String state, String stateVersion, String lastDeltaSeq,
            String terminalReason, String finalMessageId, String createdAt, String updatedAt) { }
}
