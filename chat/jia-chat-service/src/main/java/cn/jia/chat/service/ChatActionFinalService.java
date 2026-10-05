package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One v3 final store for CHAT/INSPECT. Action events contain references, not execution authority. */
@Service
public class ChatActionFinalService {
    public static final String ACTION_EVENT = "ACTION_REQUESTED";
    public record Prepared(ChatActionFinalValidator.ValidatedFinal validated,
            ChatTypedDeliberationStore.Scope scope, ChatTypedDeliberationStore.Admission admission,
            ChatTypedDeliberationStore.Outcome existing) { }

    /** Immutable stored parent; the action itself is not execution authority. */
    public record BoundAction(ChatTypedDeliberationStore.Outcome outcome,
            ChatTypedDeliberationStore.Admission admission, ChatActionFinalValidator.ValidatedFinal validated) { }

    private final ChatTypedDeliberationStore store;
    private final ChatDeliberationDao dao;
    private final TypedInspectionSessionRegistry sessions;

    public ChatActionFinalService(ChatTypedDeliberationStore store, ChatDeliberationDao dao,
            TypedInspectionSessionRegistry sessions) {
        this.store = Objects.requireNonNull(store); this.dao = Objects.requireNonNull(dao);
        this.sessions = Objects.requireNonNull(sessions);
    }

    static boolean isV3(Map<String, Object> facts) {
        if (facts.containsKey("typedDeliberation") && facts.containsKey("typedInspection"))
            throw persistence("Snapshot has conflicting typed contracts");
        Object value = facts.get("typedDeliberation");
        if (facts.containsKey("typedInspection")) value = map(facts.get("typedInspection")).get("discussionFacts");
        return value instanceof Map<?, ?> typed && Integer.valueOf(3).equals(typed.get("schemaVersion"));
    }

    public Prepared prepare(ChatTurnEntity turn, ChatContextSnapshotEntity snapshot, String content,
            Integer version, String rawOutcome, String rawReceipt) {
        verifySnapshot(turn, snapshot);
        Map<String, Object> facts = parse(snapshot.getFactsManifestJson());
        if (!isV3(facts)) throw invalid("Action final requires v3 snapshot facts");
        var scope = scope(turn);
        var admission = requireAdmission(scope, turn.getRequestId(), turn.getTurnId(), turn.getRequestRevision());
        String taskId = taskId(facts);
        if (!taskId.equals(admission.taskId())) throw persistence("Action admission task differs from snapshot");
        var typed = discussion(facts, turn.getRoute());
        Object authority = null;
        if ("INSPECT".equals(turn.getRoute())) {
            Map<String, Object> inspection = inspection(facts, admission);
            Map<String, Object> profile = map(map(inspection.get("manifest")).get("profile"));
            try {
                var live = sessions.requireSingleReady(new TypedInspectionSessionRegistry.Scope(
                        turn.getTenantId(), turn.getOwnerJiacn(), turn.getClientId()), turn.getTargetAgentId());
                if (!canonical(profile).equals(canonical(live.manifestProfile()))) throw unavailable();
            } catch (IllegalStateException stale) { throw unavailable(); }
            authority = authority(inspection);
        }
        ChatActionFinalValidator.ValidatedFinal validated;
        try {
            validated = ChatActionFinalValidator.validateJson(binding(turn, taskId), typed, authority,
                    content, version, rawOutcome, rawReceipt);
        } catch (IllegalArgumentException bad) { throw invalid(bad.getMessage()); }
        if ("CHAT".equals(turn.getRoute())) verifyCatalog(scope, admission.sourceCatalogJson(), validated.dispatchFacts());
        verifyDeliveryRelation(scope,admission,facts,validated);
        var existing = store.findOutcomeByTurn(scope, turn.getTurnId(), true);
        if ((turn.getFinalDigest() == null) != (existing == null)) throw persistence("Action final transaction is incomplete");
        if (existing != null) {
            if (!validated.finalDigest().equals(existing.finalDigest()) || !validated.finalDigest().equals(turn.getFinalDigest()))
                throw conflict("Action final already differs");
            if (!scope.equals(existing.scope()) || !turn.getRequestId().equals(existing.requestId())
                    || !Objects.equals(turn.getFinalMessageId(), existing.assistantMessageId()))
                throw persistence("Action final identity differs");
        }
        return new Prepared(validated, scope, admission, existing);
    }

    public String outcomeId(Prepared prepared) {
        return prepared.existing() == null ? stable("action-outcome", prepared.scope().tenantId(),
                prepared.scope().ownerJiacn(), prepared.scope().clientId(),
                (String) prepared.validated().binding().get("turnId")) : prepared.existing().outcomeId();
    }

    /** The caller owns message/turn/event writes; these writes must join that same transaction. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Map<String, Object> persist(Prepared p, long messageId, long now) {
        if (p.existing() != null) return projection(p.existing(), p.validated());
        var v = p.validated(); var union = v.interactionOutcome(); String id = outcomeId(p);
        var stored = new LinkedHashMap<String, Object>(); stored.put("schemaVersion", 3);
        stored.put("interactionOutcome", ChatActionFinalValidator.outcomeMap(union));
        stored.put("inspectionInputReceipt", v.inspectionInputReceipt() == null ? null
                : TypedInspectionFinalValidator.receiptMap(v.inspectionInputReceipt()));
        var row = new ChatTypedDeliberationStore.Outcome(id, p.scope(), p.admission().requestId(),
                p.admission().requestRevision(), (String) v.binding().get("turnId"), p.admission().taskId(),
                p.admission().assignmentRevision(), messageId, v.finalDigest(), union.kind(), union.text(),
                canonical(v.binding()), canonical(ChatActionFinalValidator.factsMap(v.dispatchFacts())),
                canonical(stored), p.admission().sourceCatalogJson(), now);
        if (store.insertOutcome(row) != 1) throw persistence("Unable to persist action outcome");
        if ("CLARIFY".equals(union.kind())) {
            var c = union.clarification();
            if (store.insertPending(new ChatTypedDeliberationStore.PendingQuestion(stable("typed-question", id),
                    id, p.scope(), "OPEN", 0, c.question(), canonical(c.requiredFacts()), null, null, null, now, now)) != 1)
                throw persistence("Unable to persist action clarification");
        }
        if ("ACTION_REQUEST".equals(union.kind())) persistActionEvent(row, v, now);
        return projection(row, v);
    }

    private static Map<String, Object> actionPayload(ChatTypedDeliberationStore.Outcome row,
            ChatActionFinalValidator.ValidatedFinal v) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 3); payload.put("conversationId", row.scope().conversationId());
        payload.put("conversationGeneration", Long.toString(row.scope().conversationGeneration()));
        payload.put("requestId", row.requestId()); payload.put("requestRevision", Long.toString(row.requestRevision()));
        payload.put("turnId", row.turnId()); payload.put("outcomeId", row.outcomeId());
        payload.put("finalDigest", row.finalDigest()); payload.put("taskId", row.taskId());
        payload.put("targetAgentId", v.binding().get("targetAgentId")); payload.put("actionId", v.interactionOutcome().action().actionId());
        return Collections.unmodifiableMap(payload);
    }

    private void persistActionEvent(ChatTypedDeliberationStore.Outcome row, ChatActionFinalValidator.ValidatedFinal v, long now) {
        var event = new ChatDispatchOutboxEntity().setEventId(ChatActionFinalValidator.actionEventId(v))
                .setTenantId(row.scope().tenantId()).setOwnerJiacn(row.scope().ownerJiacn()).setClientId(row.scope().clientId())
                .setTurnId(row.turnId()).setDispatchId((String)v.binding().get("dispatchId"))
                .setEventType(ACTION_EVENT).setStatus("READY").setPayloadJson(canonical(actionPayload(row, v)))
                .setVersion(0L).setAvailableAt(now).setAttemptCount(0).setFencingToken(0L).setCreatedAt(now).setUpdatedAt(now);
        if (dao.insertOutbox(event) != 1) throw persistence("Unable to persist action event");
    }

    /** Validate the durable event against the actual immutable final, not copied event instructions. */
    @Transactional(readOnly = true)
    public BoundAction loadAction(ChatDispatchOutboxEntity event) {
        if (event == null || !ACTION_EVENT.equals(event.getEventType())) throw unavailable();
        var turn = dao.findTurn(event.getTenantId(), event.getOwnerJiacn(), event.getClientId(), event.getTurnId());
        if (turn == null || !Objects.equals(event.getDispatchId(), turn.getDispatchId())
                || !Objects.equals(event.getTenantId(), turn.getTenantId())
                || !Objects.equals(event.getOwnerJiacn(), turn.getOwnerJiacn())
                || !Objects.equals(event.getClientId(), turn.getClientId())
                || !Objects.equals(event.getTurnId(), turn.getTurnId())) throw unavailable();
        var scope = scope(turn);
        var view = read(scope, turn.getRequestId(), turn.getTurnId(), turn.getRequestRevision(), turn.getRoute(), false);
        if (view == null || !"READY".equals(view.get("state"))
                || !"ACTION_REQUEST".equals(map(view.get("outcome")).get("kind"))) throw unavailable();
        var row = store.findOutcomeByRequest(scope, turn.getRequestId());
        var admission = requireAdmission(scope, turn.getRequestId(), turn.getTurnId(), turn.getRequestRevision());
        var stored = parse(row.outcomeJson());
        Map<String, Object> inputAuthority = null;
        if ("INSPECT".equals(turn.getRoute())) inputAuthority = authority(
                ChatTypedInspectionContextService.inspection(admission.sourceCatalogJson()));
        var validated = ChatActionFinalValidator.validateJson(parse(row.bindingJson()), parse(row.factsJson()),
                inputAuthority, row.text(), 3, canonical(stored.get("interactionOutcome")),
                stored.get("inspectionInputReceipt") == null ? null : canonical(stored.get("inspectionInputReceipt")));
        if (!ChatActionFinalValidator.actionEventId(validated).equals(event.getEventId())
                || !canonical(actionPayload(row, validated)).equals(canonical(parse(event.getPayloadJson()))))
            throw persistence("Action event differs from immutable final");
        return new BoundAction(row, admission, validated);
    }

    /** Returns null only for old immutable contracts; no legacy proposal is promoted to an action. */
    @Transactional(readOnly = true)
    public Map<String, Object> readIfV3(ChatTypedDeliberationStore.Scope scope, String requestId,
            String turnId, long revision, String expectedRoute) {
        return read(scope, requestId, turnId, revision, expectedRoute, true);
    }

    private Map<String, Object> read(ChatTypedDeliberationStore.Scope scope, String requestId,
            String turnId, long revision, String expectedRoute, boolean includeProgress) {
        return read(scope,requestId,turnId,revision,expectedRoute,includeProgress,true);
    }

    // Chain replay validates each immutable final once, then validates retained targets iteratively.
    private Map<String,Object> read(ChatTypedDeliberationStore.Scope scope,String requestId,
            String turnId,long revision,String expectedRoute,boolean includeProgress,boolean verifyTargets) {
        var turn = dao.findTurn(scope.tenantId(), scope.ownerJiacn(), scope.clientId(), turnId);
        if (turn == null || !scope.equals(scope(turn)) || !requestId.equals(turn.getRequestId())
                || !Objects.equals(revision, turn.getRequestRevision()) || !expectedRoute.equals(turn.getRoute())) throw unavailable();
        var snapshot = dao.findSnapshot(scope.tenantId(), scope.ownerJiacn(), scope.clientId(), turn.getSnapshotId());
        verifySnapshot(turn, snapshot);
        Map<String, Object> facts = parse(snapshot.getFactsManifestJson());
        if (!isV3(facts)) return null;
        var admission = requireAdmission(scope, requestId, turnId, revision);
        if (!admission.taskId().equals(taskId(facts))) throw persistence("Action admission task differs from snapshot");
        var typed = discussion(facts, expectedRoute);
        Map<String, Object> inspection = "INSPECT".equals(expectedRoute) ? inspection(facts, admission) : null;
        if (inspection == null) verifyCatalog(scope, admission.sourceCatalogJson(), ChatActionOutcomeContract.facts(typed));
        var row = store.findOutcomeByRequest(scope, requestId);
        Map<String, Object> outcome = null;
        ChatActionFinalValidator.ValidatedFinal validated = null;
        if (row != null) {
            if (!scope.equals(row.scope()) || !requestId.equals(row.requestId()) || !turnId.equals(row.turnId())
                    || revision != row.requestRevision() || !Objects.equals(row.assistantMessageId(), turn.getFinalMessageId())
                    || !row.finalDigest().equals(turn.getFinalDigest()) || !admission.taskId().equals(row.taskId())
                    || admission.assignmentRevision() != row.assignmentRevision()) throw persistence("Stored action identity differs");
            var stored = parse(row.outcomeJson());
            if (!stored.keySet().equals(Set.of("schemaVersion", "interactionOutcome", "inspectionInputReceipt"))
                    || !Integer.valueOf(3).equals(stored.get("schemaVersion"))) throw persistence("Stored action final is invalid");
            try {
                validated = ChatActionFinalValidator.validateJson(binding(turn, admission.taskId()), typed,
                        inspection == null ? null : authority(inspection), row.text(), 3,
                        canonical(stored.get("interactionOutcome")), stored.get("inspectionInputReceipt") == null
                                ? null : canonical(stored.get("inspectionInputReceipt")));
            } catch (IllegalArgumentException invalid) { throw persistence("Stored action final is invalid"); }
            if (!row.finalDigest().equals(validated.finalDigest()) || !row.kind().equals(validated.interactionOutcome().kind())
                    || !row.bindingJson().equals(canonical(validated.binding()))
                    || !row.factsJson().equals(canonical(ChatActionFinalValidator.factsMap(validated.dispatchFacts())))
                    || !row.sourceCatalogJson().equals(admission.sourceCatalogJson())) throw persistence("Stored action final differs");
            verifyDeliveryRelation(scope,admission,facts,validated,verifyTargets);
            outcome = projection(row, validated);
        } else if (turn.getFinalDigest() != null) throw persistence("Action final transaction is incomplete");
        var view = new LinkedHashMap<String, Object>(); view.put("schemaVersion", 3); view.put("route", expectedRoute);
        view.put("conversationId", scope.conversationId()); view.put("conversationGeneration", Long.toString(scope.conversationGeneration()));
        view.put("requestId", requestId); view.put("requestRevision", Long.toString(revision)); view.put("turnId", turnId);
        view.put("state", row == null ? "PENDING" : "READY"); view.put("outcome", outcome);
        view.put("inspection", inspection == null ? null : inspectionView(inspection, validated));
        view.put("actionProgress", includeProgress && row != null && "ACTION_REQUEST".equals(row.kind())
                ? actionProgress(row, validated, admission) : null);
        return Collections.unmodifiableMap(view);
    }

    /** Mutable delivery progress is separate from the immutable model final/digest.
     * Reads existing outbox and child facts only; never admits or retries an action. */
    Map<String, Object> actionProgress(ChatTypedDeliberationStore.Outcome parent,
            ChatActionFinalValidator.ValidatedFinal v, ChatTypedDeliberationStore.Admission original) {
        var scope = parent.scope(); String actionId = ChatActionFinalValidator.actionEventId(v);
        var event = dao.findOutboxById(scope.tenantId(), scope.ownerJiacn(), scope.clientId(), actionId);
        if (event == null || !scope.tenantId().equals(event.getTenantId()) || !scope.ownerJiacn().equals(event.getOwnerJiacn())
                || !scope.clientId().equals(event.getClientId()) || !actionId.equals(event.getEventId())
                || !parent.turnId().equals(event.getTurnId()) || !v.binding().get("dispatchId").equals(event.getDispatchId())
                || !ACTION_EVENT.equals(event.getEventType()) || event.getVersion() == null || event.getVersion() < 0
                || !canonical(actionPayload(parent, v)).equals(canonical(parse(event.getPayloadJson()))))
            throw persistence("Action progress identity is inconsistent");
        var childAdmission = store.findAdmissionByKey(scope, actionId, false);
        String state, childId = null, route = null, childVersion = null;
        if (childAdmission == null) {
            state = switch (event.getStatus()) {
                case "READY", "RETRY", "CLAIMED" -> "QUEUED";
                case "DEAD" -> "FAILED";
                default -> throw persistence("Settled action has no child");
            };
        } else {
            var capability = v.dispatchFacts().availableActions().stream().filter(c -> c.actionId().equals(
                    v.interactionOutcome().action().actionId())).findFirst().orElseThrow();
            route = "INSPECT_INPUTS".equals(capability.kind()) ? "INSPECT" : "EXECUTE";
            childId = "INSPECT".equals(route) ? ChatDeliberationService.inspectionContinuationRequestId(actionId)
                    : cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText("action-execute\n" + actionId);
            if (!"SENT".equals(event.getStatus()) || !scope.equals(childAdmission.scope())
                    || !actionId.equals(childAdmission.idempotencyKey()) || !childId.equals(childAdmission.requestId())
                    || !parent.outcomeId().equals(childAdmission.parentOutcomeId()) || !parent.taskId().equals(childAdmission.taskId())
                    || parent.assignmentRevision() != childAdmission.assignmentRevision()
                    || original.userMessageId() != childAdmission.userMessageId() || childAdmission.requestRevision() != 1
                    || !"ADMITTED".equals(childAdmission.state()) || childAdmission.stateVersion() != 0)
                throw persistence("Action progress child lineage is inconsistent");
            var child = dao.findRequest(scope.tenantId(), scope.ownerJiacn(), scope.clientId(), childId);
            if (child == null || !scope.tenantId().equals(child.getTenantId()) || !scope.ownerJiacn().equals(child.getOwnerJiacn())
                    || !scope.clientId().equals(child.getClientId()) || !childId.equals(child.getRequestId())
                    || !scope.conversationId().equals(child.getConversationId())
                    || !Objects.equals(scope.conversationGeneration(), child.getConversationGeneration())
                    || !Objects.equals(1L, child.getRequestRevision()) || !Objects.equals(original.userMessageId(), child.getUserMessageId())
                    || child.getStateVersion() == null || child.getStateVersion() < 0)
                throw persistence("Action progress child scope is inconsistent");
            childVersion = Long.toString(child.getStateVersion());
            state = switch (child.getAggregateState()) {
                case "RUNNING", "RECEIVED", "QUEUED" -> "RUNNING";
                case "COMPLETED" -> {
                    if (!"INSPECT".equals(route)) throw persistence("Execution has no projected output");
                    yield "COMPLETED";
                }
                case "OUTPUT_COMMITTED" -> {
                    if (!"EXECUTE".equals(route)) throw persistence("Inspection has invalid terminal state");
                    yield "COMPLETED";
                }
                case "FAILED" -> "FAILED";
                case "CANCELLED" -> "CANCELLED";
                default -> throw persistence("Action child state is unsupported");
            };
        }
        var result = new LinkedHashMap<String, Object>(); result.put("actionRequestId", actionId);
        result.put("state", state); result.put("dispatchVersion", Long.toString(event.getVersion()));
        result.put("childRequestId", childId); result.put("childRoute", route); result.put("childStateVersion", childVersion);
        return Collections.unmodifiableMap(result);
    }

    /** Follow only durable clarification admissions, not conversation order or the latest answer.
     * Every question is re-read through its actual v3 final and every answered edge is scoped CAS. */
    Map<String,Object> clarificationDeliveryParent(ChatTypedDeliberationStore.Scope scope,
            ChatTypedDeliberationStore.Outcome clarification) {
        var current=clarification;
        Map<String,Object> basis=null;
        var visited=new java.util.HashSet<String>();
        while(true) {
            if(current==null||!scope.equals(current.scope())||!"CLARIFY".equals(current.kind())
                    ||!clarification.taskId().equals(current.taskId())
                    ||clarification.assignmentRevision()!=current.assignmentRevision()
                    ||!visited.add(current.outcomeId()))throw unavailable();
            var turn=dao.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),current.turnId());
            if(turn==null)throw unavailable();
            var view=read(scope,current.requestId(),current.turnId(),current.requestRevision(),turn.getRoute(),false);
            if(view==null) {if(basis!=null)throw unavailable();return null;}
            if(!"READY".equals(view.get("state"))
                    ||!current.outcomeId().equals(map(view.get("outcome")).get("outcomeId"))
                    ||!current.finalDigest().equals(map(view.get("outcome")).get("finalDigest")))throw unavailable();
            if(!("FINAL_PERSISTED".equals(turn.getState())||"PUBLISHED".equals(turn.getState())))throw unavailable();
            var snapshot=dao.findSnapshot(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),turn.getSnapshotId());
            var snapshotFacts=parse(snapshot.getFactsManifestJson());
            var facts=snapshotFacts.containsKey("typedDeliberationAdmission")
                    ?map(snapshotFacts.get("typedDeliberationAdmission")):Map.<String,Object>of();
            if(!facts.containsKey("deliveryParent")) {
                if(basis!=null)throw unavailable();
                return null; // Original unlinked clarification never acquires a delivery basis later.
            }
            if(!"CHAT".equals(turn.getRoute()))throw unavailable();
            var inherited=map(facts.get("deliveryParent"));
            if(!inherited.keySet().equals(Set.of("outcomeId","finalDigest"))
                    ||!(inherited.get("outcomeId") instanceof String id)||id.isBlank()
                    ||!(inherited.get("finalDigest") instanceof String digest)||!digest.matches("sha256:[0-9a-f]{64}")
                    ||visited.contains(id)||basis!=null&&!basis.equals(inherited))throw unavailable();
            basis=Map.copyOf(inherited);
            var original=requireAdmission(scope,current.requestId(),current.turnId(),current.requestRevision());
            if(!original.intent().equals(facts.get("intent"))
                    ||!Objects.equals(original.parentOutcomeId(),facts.get("parentOutcomeId")))throw unavailable();
            if("DISCUSSION".equals(original.intent())) {
                if(!basis.get("outcomeId").equals(original.parentOutcomeId()))throw unavailable();
                return basis;
            }
            if(!"CLARIFICATION_REPLY".equals(original.intent())
                    ||!Objects.equals(original.pendingQuestionId(),facts.get("pendingQuestionId")))throw unavailable();
            current=clarificationReplyParent(scope,original);
        }
    }

    private ChatTypedDeliberationStore.Outcome clarificationReplyParent(ChatTypedDeliberationStore.Scope scope,
            ChatTypedDeliberationStore.Admission admission) {
        var parent=admission.parentOutcomeId()==null?null:store.findOutcome(scope,admission.parentOutcomeId(),false);
        if(parent==null||!scope.equals(parent.scope())||!admission.taskId().equals(parent.taskId())
                ||admission.assignmentRevision()!=parent.assignmentRevision()||!"CLARIFY".equals(parent.kind())
                ||admission.requestId().equals(parent.requestId()))throw unavailable();
        var pending=store.findPendingByOutcome(scope,parent.outcomeId(),false);
        if(pending==null||!scope.equals(pending.scope())||!parent.outcomeId().equals(pending.outcomeId())
                ||!Objects.equals(admission.pendingQuestionId(),pending.pendingQuestionId())
                ||!"ANSWERED".equals(pending.state())||pending.stateVersion()!=1
                ||!admission.requestId().equals(pending.replyRequestId()))throw unavailable();
        return parent;
    }

    private void verifyDeliveryRelation(ChatTypedDeliberationStore.Scope scope,
            ChatTypedDeliberationStore.Admission admission, Map<String,Object> snapshotFacts,
            ChatActionFinalValidator.ValidatedFinal validated) {
        verifyDeliveryRelation(scope,admission,snapshotFacts,validated,true);
    }

    private void verifyDeliveryRelation(ChatTypedDeliberationStore.Scope scope,
            ChatTypedDeliberationStore.Admission admission,Map<String,Object> snapshotFacts,
            ChatActionFinalValidator.ValidatedFinal validated,boolean verifyTargets) {
        var relation=validated.interactionOutcome().deliveryRelation(); if(relation==null)return;
        var facts=map(snapshotFacts.get("typedDeliberationAdmission"));
        var basis=map(facts.get("deliveryParent"));
        if(!admission.intent().equals(facts.get("intent"))
                ||!Objects.equals(admission.parentOutcomeId(),facts.get("parentOutcomeId"))
                ||!basis.keySet().equals(Set.of("outcomeId","finalDigest"))
                ||!relation.parentOutcomeId().equals(basis.get("outcomeId"))
                ||!relation.parentFinalDigest().equals(basis.get("finalDigest")))throw invalid("ACTION_DELIVERY_PARENT_INVALID");
        if("DISCUSSION".equals(admission.intent())) {
            if(!relation.parentOutcomeId().equals(admission.parentOutcomeId()))throw invalid("ACTION_DELIVERY_PARENT_INVALID");
        } else if("CLARIFICATION_REPLY".equals(admission.intent())) {
            var clarification=clarificationReplyParent(scope,admission);
            if(!basis.equals(clarificationDeliveryParent(scope,clarification)))throw invalid("ACTION_DELIVERY_PARENT_INVALID");
            if(!Objects.equals(admission.pendingQuestionId(),facts.get("pendingQuestionId")))throw invalid("ACTION_DELIVERY_PARENT_INVALID");
        } else throw invalid("ACTION_DELIVERY_PARENT_INVALID");
        var parent=store.findOutcome(scope,relation.parentOutcomeId(),false);
        if(parent==null||!scope.equals(parent.scope())||!admission.taskId().equals(parent.taskId())
                ||parent.assignmentRevision()!=admission.assignmentRevision()||!"ANSWER".equals(parent.kind())
                ||!relation.parentFinalDigest().equals(parent.finalDigest())
                ||admission.requestId().equals(parent.requestId()))throw unavailable();
        var stored=parse(parent.outcomeJson());
        if(!Boolean.TRUE.equals(map(stored.get("interactionOutcome")).get("deliverable")))throw unavailable();
        if(verifyTargets&&relation.targetOutcomeId()!=null) {
            var retained=deliveryTargets(scope,basis);
            verifyTargetAdvertisement(facts,relation,retained);
        }
    }

    /** Original causal parent remains the CAS basis; only retained exact texts are replacement targets.
     * No history query, latest-answer inference or independent delivery-set state is introduced. */
    List<Map<String,Object>> deliveryTargets(ChatTypedDeliberationStore.Scope scope,Map<String,Object> basis) {
        if(basis==null||!basis.keySet().equals(Set.of("outcomeId","finalDigest")))throw unavailable();
        String id=(String)basis.get("outcomeId"),digest=(String)basis.get("finalDigest");
        var chain=new java.util.ArrayList<Map<String,Object>>();
        var visited=new java.util.HashSet<String>();
        String task=null;long assignment=-1;
        while(true) {
            if(!visited.add(id))throw unavailable();
            var row=store.findOutcome(scope,id,false);
            if(row==null||!scope.equals(row.scope())||!digest.equals(row.finalDigest())||!"ANSWER".equals(row.kind()))throw unavailable();
            if(task==null){task=row.taskId();assignment=row.assignmentRevision();}
            if(!task.equals(row.taskId())||assignment!=row.assignmentRevision())throw unavailable();
            var turn=dao.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.turnId());
            if(turn==null||!"CHAT".equals(turn.getRoute())||!("FINAL_PERSISTED".equals(turn.getState())||"PUBLISHED".equals(turn.getState())))throw unavailable();
            var view=read(scope,row.requestId(),row.turnId(),row.requestRevision(),"CHAT",false,false);
            var text=map(view.get("outcome"));
            if(!"READY".equals(view.get("state"))||!Boolean.TRUE.equals(text.get("deliverable")))throw unavailable();
            chain.add(text);
            if(!text.containsKey("deliveryRelation"))break;
            var relation=map(text.get("deliveryRelation"));
            id=(String)relation.get("parentOutcomeId");digest=(String)relation.get("parentFinalDigest");
        }
        java.util.Collections.reverse(chain);
        var retained=new java.util.ArrayList<Map<String,Object>>();
        for(var text:chain) {
            var item=Map.<String,Object>of("outcomeId",text.get("outcomeId"),"finalDigest",text.get("finalDigest"),"text",text.get("text"));
            if(!text.containsKey("deliveryRelation")){retained.add(item);continue;}
            var relation=map(text.get("deliveryRelation"));
            switch((String)relation.get("mode")) {
                case "APPEND" -> retained.add(item);
                case "RESET" -> {retained.clear();retained.add(item);}
                case "REPLACE" -> {
                    String target=(String)relation.getOrDefault("targetOutcomeId",relation.get("parentOutcomeId"));
                    String targetDigest=(String)relation.getOrDefault("targetFinalDigest",relation.get("parentFinalDigest"));
                    int index=-1;
                    for(int n=0;n<retained.size();n++)if(target.equals(retained.get(n).get("outcomeId"))&&targetDigest.equals(retained.get(n).get("finalDigest")))index=n;
                    if(index<0)throw unavailable();
                    if(relation.containsKey("targetOutcomeId")) {
                        var row=store.findOutcome(scope,(String)text.get("outcomeId"),false);
                        var turn=dao.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.turnId());
                        var snapshot=dao.findSnapshot(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),turn.getSnapshotId());
                        verifyTargetAdvertisement(map(parse(snapshot.getFactsManifestJson()).get("typedDeliberationAdmission")),
                                new ChatActionOutcomeContract.DeliveryRelation("REPLACE",(String)relation.get("parentOutcomeId"),(String)relation.get("parentFinalDigest"),target,targetDigest),retained);
                    }
                    retained.set(index,item);
                }
                default -> throw unavailable();
            }
        }
        return List.copyOf(retained);
    }

    private static void verifyTargetAdvertisement(Map<String,Object> facts,
            ChatActionOutcomeContract.DeliveryRelation relation,List<Map<String,Object>> retained) {
        if(!canonical(retained).equals(canonical(facts.get("deliveryTargets")))
                ||retained.stream().noneMatch(item->relation.targetOutcomeId().equals(item.get("outcomeId"))
                    &&relation.targetFinalDigest().equals(item.get("finalDigest"))))throw invalid("ACTION_DELIVERY_TARGET_INVALID");
    }

    private Map<String, Object> projection(ChatTypedDeliberationStore.Outcome row, ChatActionFinalValidator.ValidatedFinal v) {
        var view = new LinkedHashMap<String, Object>(); view.put("outcomeContractVersion", 3);
        view.put("outcomeId", row.outcomeId()); view.put("taskId", row.taskId());
        view.put("assignmentRevision", Long.toString(row.assignmentRevision()));
        view.put("assistantMessageId", Long.toString(row.assistantMessageId())); view.put("finalDigest", row.finalDigest());
        view.put("kind", row.kind()); view.put("text", row.text());
        var deliverable = v.interactionOutcome().deliverable();
        if (deliverable != null) view.put("deliverable", deliverable);
        if (Boolean.TRUE.equals(deliverable)) view.put("messageSource", Map.of(
                "turnId", row.turnId(), "messageId", Long.toString(row.assistantMessageId()),
                "snapshotId", v.binding().get("snapshotId"), "finalDigest", row.finalDigest()));
        if (v.interactionOutcome().deliveryRelation() != null)
            view.put("deliveryRelation",ChatActionFinalValidator.relationMap(v.interactionOutcome().deliveryRelation()));
        Map<String, Object> clarification = null, action = null;
        if ("CLARIFY".equals(row.kind())) {
            var pending = store.findPendingByOutcome(row.scope(), row.outcomeId(), false);
            var c = v.interactionOutcome().clarification();
            if (pending == null || !row.scope().equals(pending.scope()) || !row.outcomeId().equals(pending.outcomeId())
                    || !stable("typed-question", row.outcomeId()).equals(pending.pendingQuestionId())
                    || !c.question().equals(pending.question()) || !canonical(c.requiredFacts()).equals(pending.requiredFactsJson())
                    || !("OPEN".equals(pending.state()) && pending.stateVersion() == 0 && pending.replyRequestId() == null
                    || "ANSWERED".equals(pending.state()) && pending.stateVersion() == 1 && pending.replyRequestId() != null))
                throw persistence("Action clarification is inconsistent");
            clarification = new LinkedHashMap<>(); clarification.put("pendingQuestionId", pending.pendingQuestionId());
            clarification.put("state", pending.state()); clarification.put("stateVersion", Long.toString(pending.stateVersion()));
            clarification.put("question", pending.question()); clarification.put("requiredFacts", c.requiredFacts());
            clarification.put("replyRequestId", pending.replyRequestId());
        }
        if ("ACTION_REQUEST".equals(row.kind())) {
            var a = v.interactionOutcome().action(); action = Map.of("actionRequestId", ChatActionFinalValidator.actionEventId(v),
                    "actionId", a.actionId(), "instruction", a.instruction(), "sourceRefIds", a.sourceRefIds());
        }
        view.put("clarification", clarification == null ? null : Collections.unmodifiableMap(clarification)); view.put("action", action);
        return Collections.unmodifiableMap(view);
    }

    private static Map<String, Object> inspectionView(Map<String, Object> typed, ChatActionFinalValidator.ValidatedFinal v) {
        var result = new LinkedHashMap<String, Object>(); result.put("authorizationId", typed.get("authorizationId"));
        result.put("manifestDigest", typed.get("manifestDigest"));
        result.put("sourceRefIds", ChatActionOutcomeContract.facts(typed.get("discussionFacts")).availableSources().stream()
                .map(ChatActionOutcomeContract.Source::sourceRefId).toList());
        result.put("inputSummary", v == null ? null : Map.of("inputDigest", v.inspectionInputReceipt().inputDigest(),
                "sources", TypedInspectionFinalValidator.receiptMap(v.inspectionInputReceipt()).get("sources")));
        return Collections.unmodifiableMap(result);
    }

    private ChatTypedDeliberationStore.Admission requireAdmission(ChatTypedDeliberationStore.Scope scope,
            String requestId, String turnId, long revision) {
        var admission = store.findAdmissionByRequest(scope, requestId);
        if (admission == null) throw unavailable();
        if (!scope.equals(admission.scope()) || !requestId.equals(admission.requestId()) || revision != admission.requestRevision()
                || !List.of(turnId).equals(parseList(admission.turnIdsJson()))) throw persistence("Action admission differs from request");
        return admission;
    }

    private static Map<String, Object> inspection(Map<String, Object> facts, ChatTypedDeliberationStore.Admission admission) {
        Map<String, Object> typed;
        try { typed = ChatTypedInspectionContextService.validateTypedInspection(facts.get("typedInspection")); }
        catch (IllegalArgumentException invalid) { throw persistence("Action inspection snapshot is invalid"); }
        if (!canonical(typed).equals(canonical(ChatTypedInspectionContextService.inspection(admission.sourceCatalogJson()))))
            throw persistence("Action inspection admission differs from snapshot");
        return typed;
    }

    private static Map<String, Object> discussion(Map<String, Object> facts, String route) {
        if ("CHAT".equals(route) && facts.containsKey("typedDeliberation") && !facts.containsKey("typedInspection"))
            return map(facts.get("typedDeliberation"));
        if ("INSPECT".equals(route) && facts.containsKey("typedInspection") && !facts.containsKey("typedDeliberation"))
            return map(map(facts.get("typedInspection")).get("discussionFacts"));
        throw persistence("Action route differs from snapshot");
    }

    private static Map<String, Object> authority(Map<String, Object> typed) {
        return Map.of("authorizationId", typed.get("authorizationId"), "manifestDigest", typed.get("manifestDigest"),
                "sources", map(typed.get("manifest")).get("sources"));
    }

    private static void verifyCatalog(ChatTypedDeliberationStore.Scope scope, String json, ChatActionOutcomeContract.Facts facts) {
        var catalog = ChatTypedDeliberationContextService.parseCatalog(json);
        if (catalog.size() != facts.availableSources().size()) throw persistence("Action source catalogue differs");
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        var sourceScope = new ChatTypedDeliberationContextService.Scope(scope.tenantId(), scope.ownerJiacn(), scope.clientId(),
                scope.conversationId(), scope.conversationGeneration());
        for (var item : catalog) {
            Object id = item.get("sourceRefId");
            if (!(id instanceof String sourceId) || !sourceId.equals(ChatTypedDeliberationContextService.sourceRefId(sourceScope, item))
                    || byId.put(sourceId, item) != null) throw persistence("Action source catalogue is ambiguous");
        }
        for (var source : facts.availableSources()) {
            var item = byId.remove(source.sourceRefId());
            if (item == null || !source.kind().equals(item.get("sourceKind")) || !source.mediaType().equals(item.get("mediaType")))
                throw persistence("Action source catalogue differs");
        }
    }

    private static void verifySnapshot(ChatTurnEntity turn, ChatContextSnapshotEntity snapshot) {
        if (snapshot == null || !Objects.equals(turn.getSnapshotId(), snapshot.getSnapshotId())
                || !Objects.equals(turn.getTenantId(), snapshot.getTenantId()) || !Objects.equals(turn.getOwnerJiacn(), snapshot.getOwnerJiacn())
                || !Objects.equals(turn.getClientId(), snapshot.getClientId()) || !Objects.equals(turn.getRequestId(), snapshot.getRequestId())
                || !Objects.equals(turn.getRequestRevision(), snapshot.getRequestRevision())
                || !Objects.equals(turn.getConversationId(), snapshot.getConversationId())
                || !Objects.equals(turn.getConversationGeneration(), snapshot.getConversationGeneration())
                || !Objects.equals(turn.getTargetAgentId(), snapshot.getTargetAgentId()) || !Objects.equals(turn.getRoute(), snapshot.getRoute())
                || !Objects.equals(turn.getContextDigest(), snapshot.getContextDigest())) throw persistence("Action snapshot binding differs");
    }
    private static ChatTypedDeliberationStore.Scope scope(ChatTurnEntity turn) {
        return new ChatTypedDeliberationStore.Scope(turn.getTenantId(), turn.getOwnerJiacn(), turn.getClientId(),
                turn.getConversationId(), turn.getConversationGeneration());
    }
    private static Map<String, Object> binding(ChatTurnEntity turn, String taskId) {
        var b = new LinkedHashMap<String, Object>(); b.put("tenantId", turn.getTenantId()); b.put("ownerJiacn", turn.getOwnerJiacn());
        b.put("clientId", turn.getClientId()); b.put("conversationId", turn.getConversationId());
        b.put("conversationGeneration", Long.toString(turn.getConversationGeneration())); b.put("requestId", turn.getRequestId());
        b.put("requestRevision", Long.toString(turn.getRequestRevision())); b.put("turnId", turn.getTurnId());
        b.put("dispatchId", turn.getDispatchId()); b.put("snapshotId", turn.getSnapshotId()); b.put("contextDigest", turn.getContextDigest());
        b.put("targetAgentId", turn.getTargetAgentId()); b.put("route", turn.getRoute()); b.put("taskId", taskId); return b;
    }
    private static String taskId(Map<String, Object> facts) {
        Object task = facts.get("task");
        if (task instanceof Map<?, ?> m && m.get("id") instanceof String id && !id.isBlank()) return id;
        throw persistence("Action task is missing");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> parse(String json) {
        try { return JsonUtil.getMapper().readValue(json, Map.class); }
        catch (RuntimeException malformed) { throw persistence("Stored action object is invalid"); }
    }
    @SuppressWarnings("unchecked") private static List<Object> parseList(String json) {
        try { return JsonUtil.getMapper().readValue(json, List.class); }
        catch (RuntimeException malformed) { throw persistence("Stored action list is invalid"); }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object raw) {
        if (raw instanceof Map<?, ?> value) return (Map<String, Object>)value;
        throw persistence("Stored action object is invalid");
    }
    private static String canonical(Object value) { return CanonicalContextJson.write(value); }
    private static String stable(String prefix, String... parts) {
        // Same immutable question-ID convention as the existing clarification CAS path.
        try { return prefix + "_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(String.join("\0", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 40); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ChatDeliberationException invalid(String message) { return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST, message); }
    private static ChatDeliberationException persistence(String message) { return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR, message); }
    private static ChatDeliberationException conflict(String message) { return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, message); }
    private static ChatDeliberationException unavailable() { return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, "Action outcome is unavailable"); }
}
