package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-only first image execution lane. A model answer or browser proposal cannot enter here:
 * only a committed, owner-scoped step and the exact persisted grant can authorize CREATE.
 * Re-reads both before side effects. A single database transaction binds the durable link;
 * retries use the immutable execution intent, never a new chargeable intent.
 */
@Service
public class ChatBountyExecutionCoordinator {
    private final JdbcTemplate jdbc;
    private final ChatInteractionStepStore steps;
    private final ChatDeliberationDao requests;
    private final ChatConversationDao conversations;
    private final AgentTaskExecutionGrantService grants;
    private final AgentTaskRequirementSnapshotService requirements;
    private final PersonalWorkspaceExecutionService executions;
    private final ObjectMapper json;

    public ChatBountyExecutionCoordinator(JdbcTemplate jdbc, ChatInteractionStepStore steps,
            ChatDeliberationDao requests, ChatConversationDao conversations,
            AgentTaskExecutionGrantService grants, AgentTaskRequirementSnapshotService requirements,
            PersonalWorkspaceExecutionService executions, ObjectMapper json) {
        this.jdbc=Objects.requireNonNull(jdbc); this.steps=Objects.requireNonNull(steps);
        this.requests=Objects.requireNonNull(requests); this.conversations=Objects.requireNonNull(conversations);
        this.grants=Objects.requireNonNull(grants); this.requirements=Objects.requireNonNull(requirements);
        this.executions=Objects.requireNonNull(executions); this.json=Objects.requireNonNull(json);
    }

    public record Pending(String tenantId, String ownerJiacn, String clientId,
            String requestId, String stepId) { }

    /** Read-only candidate scan; do NOT lock a Chat row before the task-root/grant lock. */
    public List<Pending> pending(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid candidate limit");
        return jdbc.query("""
                SELECT s.tenant_id,s.owner_jiacn,s.client_id,s.request_id,s.step_id
                FROM chat_interaction_step s
                JOIN chat_step_execution_link l ON l.step_id=s.step_id
                  AND l.tenant_id=s.tenant_id AND l.owner_jiacn=s.owner_jiacn AND l.client_id=s.client_id
                WHERE s.kind='EXECUTE' AND s.state='ADMITTED'
                  AND l.state='WAITING_ADMISSION' AND l.execution_id IS NULL
                ORDER BY s.created_at,s.step_id LIMIT ?
                """, (rs, row) -> new Pending(rs.getString(1),rs.getString(2),
                        rs.getString(3),rs.getString(4),rs.getString(5)), limit);
    }

    /** Returns an owner-safe fact; absence of a paid authorization is not an execution. */
    @Transactional(rollbackFor = Exception.class)
    public String coordinate(Pending candidate) {
        if (candidate == null) throw new IllegalArgumentException("Missing candidate");
        ChatInteractionStepStore.Step step=steps.findStep(candidate.tenantId(), candidate.ownerJiacn(),
                candidate.clientId(), candidate.requestId(),1,1);
        if (step == null || !Objects.equals(step.stepId(),candidate.stepId())
                || !Objects.equals(step.tenantId(),candidate.tenantId())
                || !Objects.equals(step.ownerJiacn(),candidate.ownerJiacn())
                || !Objects.equals(step.clientId(),candidate.clientId())
                || !"EXECUTE".equals(step.kind()) || !"ADMITTED".equals(step.state())) return "NOT_PENDING";
        var scope=new AgentTaskExecutionGrantService.Scope(step.tenantId(),step.clientId(),step.ownerJiacn());
        // Re-read persisted input and recompute its canonical digest, not just the browser's hint.
        ChatRequestEntity request=requests.findRequest(step.tenantId(),step.ownerJiacn(),step.clientId(),step.requestId());
        if (request == null || !Objects.equals(request.getConversationId(),step.conversationId())
                || !Objects.equals(request.getConversationGeneration(),step.conversationGeneration())
                || !Objects.equals(request.getRequestRevision(),step.requestRevision())
                || !"PLANNING".equals(request.getAggregateState()) || request.getUserMessageId()==null)
            throw new IllegalStateException("Bounty request state is inconsistent");
        List<Input> inputs=jdbc.query("""
                SELECT m.content,m.metadata FROM chat_message m WHERE m.id=?
                AND m.tenant_id=? AND BINARY m.tenant_id=BINARY ?
                AND m.client_id=? AND BINARY m.client_id=BINARY ?
                AND m.jiacn=? AND BINARY m.jiacn=BINARY ?
                AND m.conversation_id=? AND BINARY m.conversation_id=BINARY ?
                AND m.sender_type='user' AND m.message_type='USER'
                """,(rs,n)->new Input(rs.getString(1),rs.getString(2)),request.getUserMessageId(),
                step.tenantId(),step.tenantId(),step.clientId(),step.clientId(),step.ownerJiacn(),
                step.ownerJiacn(),step.conversationId(),step.conversationId());
        if (inputs.size()!=1 || inputs.getFirst().content()==null || inputs.getFirst().metadata()==null)
            throw new IllegalStateException("Bounty request input is unavailable");
        JsonNode metadata;
        try { metadata=json.readTree(inputs.getFirst().metadata()); }
        catch (Exception invalid) { throw new IllegalStateException("Bounty input metadata is invalid",invalid); }
        String operation=text(metadata,"permittedOperation");
        if (!"GENERATE_IMAGE".equals(operation)) return waitFor(step,request,"WAITING_CAPABILITY");
        if (!Objects.equals(text(metadata,"requestId"),step.requestId())
                || !Objects.equals(text(metadata,"taskId"),step.taskId())
                || !Objects.equals(text(metadata,"targetAgentId"),step.targetAgentId()))
            throw new IllegalStateException("Bounty input scope changed");
        // The bootstrap metadata is a durable catalogue, not authority to read files. Recheck
        // its exact fixed versions against the live, server-issued grant after admission.
        String inputDigest;
        if (metadata.has("sourceBusinessActionId")) {
            if (!metadata.has("referenceSummaries") || !metadata.get("referenceSummaries").isArray())
                throw new IllegalStateException("Bounty reference snapshot is unavailable");
            long revision=number(metadata,"requirementRevision");
            var exact=requirements.read(scope,step.taskId(),revision);
            if (exact == null || !Objects.equals(exact.sha256(),text(metadata,"requirementHash"))
                    || !Objects.equals(exact.tenantId(),step.tenantId())
                    || !Objects.equals(exact.clientId(),step.clientId())
                    || !Objects.equals(exact.ownerJiacn(),step.ownerJiacn())
                    || !Objects.equals(exact.taskId(),step.taskId())
                    || !Objects.equals(inputs.getFirst().content(),exact.title()+
                            (exact.description()==null || exact.description().isEmpty()?"":"\n\n"+exact.description())))
                throw new IllegalStateException("Exact requirement snapshot changed");
            inputDigest=sha(CanonicalContextJson.write(Map.of("requirementHash",exact.sha256(),
                    "referenceHash",text(metadata,"referenceSummarySha256"),"taskId",step.taskId(),
                    "assignmentRevision",step.assignmentRevision(),"grantId",step.grantId(),
                    "grantVersion",step.grantVersion(),"targetAgentId",step.targetAgentId(),
                    "operation",operation)));
        } else {
            inputDigest=sha(CanonicalContextJson.write(Map.of("taskId",step.taskId(),
                    "assignmentRevision",step.assignmentRevision(),"grantId",step.grantId(),
                    "grantVersion",step.grantVersion(),"targetAgentId",step.targetAgentId(),
                    "operation",operation,"content",inputs.getFirst().content())));
        }
        if (!Objects.equals(inputDigest,step.inputSnapshotDigest()))
            throw new IllegalStateException("Bounty input digest changed");
        var observedLink=steps.findLink(step.tenantId(),step.ownerJiacn(),step.clientId(),step.stepId());
        if (observedLink==null || !Objects.equals(observedLink.stepId(),step.stepId()))
            throw new IllegalStateException("Bounty execution link is missing");
        if (observedLink.executionId()!=null) return "ALREADY_BOUND";
        if (!"WAITING_ADMISSION".equals(observedLink.state())) return "NOT_PENDING";
        // Root -> target/files -> grant -> consent is established before any Chat row lock.
        AgentTaskExecutionGrantService.Admission admitted;
        try {
            admitted=grants.admitControlledV3(scope,step.taskId(),step.grantId(),step.grantVersion(),
                    step.assignmentRevision(),step.targetAgentId(),operation,"NEW_EXECUTION",
                    null,null,null);
            if (admitted==null) return waitFor(step,request,"WAITING_AUTHORIZATION");
            if (!admitted.paidExecutionAuthorized()) {
                boolean persistedCostAuthority=admitted.costAuthorizationRef()!=null
                        && admitted.costAuthorizationVersion()!=null;
                if ((admitted.costAuthorizationRef()==null)!=(admitted.costAuthorizationVersion()==null))
                    throw new IllegalStateException("Bounty authorization wait result is inconsistent");
                // Keep the durable ADMITTED candidate eligible while the authenticated runtime
                // reconnects. No execution/provider side effect has occurred at this point.
                return persistedCostAuthority?"WAITING_CAPABILITY":waitFor(step,request,"WAITING_AUTHORIZATION");
            }
            if (admitted.costAuthorizationRef()==null || admitted.costAuthorizationVersion()==null)
                return waitFor(step,request,"WAITING_AUTHORIZATION");
        } catch (AgentTaskExecutionGrantException denied) {
            if (denied.reason()==AgentTaskExecutionGrantException.Reason.PAID_EXECUTION_NOT_AUTHORIZED)
                return waitFor(step,request,"WAITING_AUTHORIZATION");
            throw denied;
        }
        List<PersonalWorkspaceExecutionService.ReferenceSelection> references =
                authorizedReferences(metadata, admitted.inputs());
        if (references == null) return waitFor(step,request,"WAITING_INPUT_RESOLVER");
        // Agent creation and RESERVED happen first in this REQUIRED transaction. A later Chat
        // validation/write failure rolls them back; there is no callback into Agent after Chat locks.
        var execution=executions.createConversation(new PersonalWorkspaceExecutionService.OwnerScope(
                step.tenantId(),step.clientId(),step.ownerJiacn()),
                new PersonalWorkspaceExecutionService.ConversationCreate(step.conversationId(),
                step.taskId(),step.targetAgentId(),observedLink.executionIntentId(),step.grantId(),
                step.grantVersion(),step.assignmentRevision(),operation,inputs.getFirst().content(),
                "image/png",references,true,3));
        if (execution==null || !"CONVERSATION".equals(execution.executionMode())
                || !Objects.equals(execution.taskId(),step.taskId())
                || !Objects.equals(execution.conversationId(),step.conversationId())
                || !Objects.equals(execution.targetAgentId(),step.targetAgentId()))
            throw new IllegalStateException("Controlled bounty execution identity is invalid");
        ChatConversationEntity discussion=conversations.lockScopedById(step.ownerJiacn(),
                step.clientId(),step.conversationId());
        if (discussion == null || discussion.getDeletedAt()!=null
                || !Objects.equals(discussion.getLifecycleGeneration(),step.conversationGeneration())
                || !Objects.equals(discussion.getTaskId(),step.taskId())
                || !"bounty".equals(discussion.getConversationScopeType())
                || !Objects.equals(discussion.getConversationScopeKey(),"task:"+step.taskId()))
            throw new IllegalStateException("Bounty discussion was revoked");
        var link=steps.findLink(step.tenantId(),step.ownerJiacn(),step.clientId(),step.stepId());
        if (link==null || !Objects.equals(link.stepId(),step.stepId()))
            throw new IllegalStateException("Bounty execution link is missing");
        if (link.executionId()!=null) {
            if (!Objects.equals(link.executionId(),execution.executionId()))
                throw new IllegalStateException("Bounty execution link changed");
            return "ALREADY_BOUND";
        }
        if (!"WAITING_ADMISSION".equals(link.state()))
            throw new IllegalStateException("Bounty execution link changed");
        if (steps.bindExecution(link,execution.executionId(),System.currentTimeMillis())!=1
                || steps.updateStepState(step,"RUNNING",System.currentTimeMillis())!=1
                || requests.updateRequestState(request,"RUNNING",System.currentTimeMillis())!=1)
            throw new IllegalStateException("Bounty execution link was not durably bound");
        return "RUNNING";
    }

    private String waitFor(ChatInteractionStepStore.Step step, ChatRequestEntity request, String state) {
        long now=System.currentTimeMillis();
        if (steps.updateStepState(step,state,now)!=1 || requests.updateRequestState(request,state,now)!=1)
            throw new IllegalStateException("Bounty waiting state was not committed");
        return state;
    }

    /** Null means an unsupported material format; inconsistent snapshots fail closed. */
    private static List<PersonalWorkspaceExecutionService.ReferenceSelection> authorizedReferences(
            JsonNode metadata, List<AgentTaskExecutionGrantService.AuthorizedInput> granted) {
        if (granted == null || granted.size() > 16)
            throw new IllegalStateException("Bounty grant input list is unavailable");
        JsonNode catalogue = metadata.get("referenceSummaries");
        if (catalogue == null && !metadata.has("sourceBusinessActionId")) {
            if (!granted.isEmpty()) throw new IllegalStateException("Legacy input has unrecorded references");
            return List.of();
        }
        if (catalogue == null || !catalogue.isArray() || catalogue.size() != granted.size())
            throw new IllegalStateException("Bounty references differ from current grant");
        var result = new java.util.ArrayList<PersonalWorkspaceExecutionService.ReferenceSelection>();
        for (int i=0; i<granted.size(); i++) {
            var stored=catalogue.get(i); var expected=granted.get(i);
            if (stored==null || !stored.isObject() || expected==null
                    || !Objects.equals(text(stored,"fileId"),expected.fileId())
                    || number(stored,"version")!=expected.version()
                    || !Objects.equals(text(stored,"purpose"),expected.purpose())
                    || !Objects.equals(text(stored,"mimeType"),expected.contentMimeType())
                    || number(stored,"byteLength")!=expected.byteLength()
                    || !Objects.equals(text(stored,"contentHash"),expected.contentHash()))
                throw new IllegalStateException("Bounty references differ from current grant");
            if (!List.of("image/png","image/jpeg").contains(expected.contentMimeType())) return null;
            result.add(new PersonalWorkspaceExecutionService.ReferenceSelection(expected.fileId(),
                    expected.version(),expected.purpose(),expected.contentMimeType(),
                    expected.byteLength(),expected.contentHash()));
        }
        return List.copyOf(result);
    }

    private record Input(String content,String metadata) { }
    private static String text(JsonNode node,String key) {
        JsonNode value=node.get(key);
        if (value==null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalStateException("Bounty metadata field unavailable: "+key);
        return value.asText();
    }
    private static long number(JsonNode node,String key) {
        JsonNode value=node.get(key);
        if (value==null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalStateException("Bounty metadata revision unavailable");
        return value.longValue();
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
