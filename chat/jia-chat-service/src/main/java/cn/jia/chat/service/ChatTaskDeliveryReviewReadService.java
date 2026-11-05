package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** Owner-authorized repeatable-read projection. Source links never grant access to content. */
@Service
public class ChatTaskDeliveryReviewReadService {
    private final JdbcTemplate jdbc;
    private final AgentTaskFormalDeliveryReadService deliveries;
    private final ChatSelectedOutputFinalizationStore submissions;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Item(String artifactId, String artifactVersion, String contentHash, String purpose,
            String displayRole, Object source, String unavailableReason) { }
    public record Batch(String deliveryId, String deliveryVersion, String state, String batchSha256,
            String summary, long submittedAt, List<Item> items, List<String> allowedActions) { }
    public record Pending(String operationId, String kind, String state, String stateVersion, String errorCode, boolean retryable) { }
    public record Context(int schemaVersion, String taskId, String taskVersion, String taskState,
            String assignmentRevision, Batch currentDelivery, List<Batch> history, Pending pendingOperation) { }
    public ChatTaskDeliveryReviewReadService(JdbcTemplate jdbc, AgentTaskFormalDeliveryReadService deliveries,
            ChatSelectedOutputFinalizationStore submissions) {
        this.jdbc = Objects.requireNonNull(jdbc); this.deliveries = Objects.requireNonNull(deliveries);
        this.submissions = Objects.requireNonNull(submissions);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Context read(Scope s, String task) {
        ChatDeliveryReviewContract.id(task, 100);
        // Read authorization before any associated operation/source data.
        var root = submissions.task(s.tenantId(), s.ownerJiacn(), s.clientId(), task);
        var current = deliveries.currentForTaskOwner(s.tenantId(), s.clientId(), s.ownerJiacn(), task,
                List.of("completed", "archived").contains(root.state()));
        var revisions = jdbc.query("""
            SELECT assignment_revision FROM agent_task_execution_grant WHERE BINARY tenant_id=BINARY ?
             AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY task_id=BINARY ? AND state='ACTIVE'
            """, (r,n) -> r.getLong(1), s.tenantId(), s.clientId(), s.ownerJiacn(), task);
        if (revisions.size() > 1) throw new Conflict();
        String revision = revisions.isEmpty() ? "0" : revisions.getFirst().toString();
        Pending pending = pending(s, task);
        if (("reviewing".equals(root.state()) && current == null)
                || (current != null && "submitted".equals(current.getState()) && !"reviewing".equals(root.state()))) throw new Conflict();
        Batch currentBatch = null;
        if (current != null) {
            var items = sourceItems(s, task, current);
            List<String> actions = new ArrayList<>(List.of("VIEW"));
            if ("reviewing".equals(root.state()) && "submitted".equals(current.getState())
                    && revisions.size() == 1 && pending == null && sourcesComplete(items)) {
                actions.add("ACCEPT"); actions.add("REQUEST_CHANGES");
            }
            if (pending != null) actions.add("CHECK_OPERATION");
            currentBatch = batch(task, current, items, actions);
        }
        // v2 is current-only. Historical pages never participate in acceptance eligibility.
        return new Context(2, task, Long.toString(root.version()), root.state().toUpperCase(Locale.ROOT),
                revision, currentBatch, List.of(), pending);
    }
    public record HistoryPage(List<Batch> items, String nextCursor, boolean hasMore) { }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public HistoryPage history(Scope s, String task, Long beforeRevision) {
        ChatDeliveryReviewContract.id(task, 100);
        submissions.task(s.tenantId(), s.ownerJiacn(), s.clientId(), task);
        var page = deliveries.historyForTaskOwner(s.tenantId(), s.clientId(), s.ownerJiacn(), task, beforeRevision);
        var items = page.items().stream().map(delivery -> batch(task, delivery, sourceItems(s, task, delivery), List.of("VIEW"))).toList();
        return new HistoryPage(items, page.nextCursor(), page.hasMore());
    }
    private static Batch batch(String task, AgentTaskFormalDeliveryViewDTO delivery, List<Item> items, List<String> actions) {
        return new Batch(delivery.getDeliveryId(), delivery.getDeliveryVersion().toString(),
                delivery.getState().toUpperCase(Locale.ROOT), ChatDeliveryReviewContract.batchDigest(task, delivery),
                delivery.getSummary(), delivery.getSubmittedAt(), items, List.copyOf(actions));
    }
    private Pending pending(Scope s, String task) {
        List<Pending> rows = jdbc.query("""
            SELECT operation_id,'REVIEW',state,state_version,error_code FROM
             (SELECT operation_id,state,state_version,JSON_UNQUOTE(JSON_EXTRACT(receipt_json,'$.errorCode')) error_code
              FROM chat_task_delivery_review_operation WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ?
               AND BINARY client_id=BINARY ? AND BINARY task_id=BINARY ? AND state='PROCESSING') review
            UNION ALL SELECT operation_id,'SUBMISSION',UPPER(state),state_version,error_code
             FROM chat_selected_output_finalization WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ?
              AND BINARY client_id=BINARY ? AND BINARY task_id=BINARY ? AND state='pending' LIMIT 2
            """, (r,n) -> new Pending(r.getString(1),r.getString(2),r.getString(3),Long.toString(r.getLong(4)),r.getString(5),false),
                s.tenantId(),s.ownerJiacn(),s.clientId(),task,s.tenantId(),s.ownerJiacn(),s.clientId(),task);
        if (rows.size() > 1) throw new Conflict(); return rows.isEmpty() ? null : rows.getFirst();
    }
    /** Used again inside the root-locked decision transaction: hiding a button is not authorization. */
    public List<Item> sourceItems(Scope s, String task, AgentTaskFormalDeliveryViewDTO delivery) {
        record Promotion(String operation, String manifest, String facts) { }
        List<Promotion> promoted = jdbc.query("""
            SELECT operation_id,manifest_artifact_id,artifact_facts_json FROM agent_selected_output_finalization
             WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ?
              AND BINARY task_id=BINARY ? AND BINARY delivery_id=BINARY ? AND phase IN ('SUBMITTED','ACCEPTED')
            """, (r,n) -> new Promotion(r.getString(1),r.getString(2),r.getString(3)),
                s.tenantId(),s.ownerJiacn(),s.clientId(),task,delivery.getDeliveryId());
        Map<String,Object> mapped = new HashMap<>();
        if (promoted.size() > 1) throw new Conflict();
        if (promoted.size() == 1) {
            var p = promoted.getFirst(); var operation = submissions.findByOperation(s.tenantId(),s.ownerJiacn(),s.clientId(),task,p.operation());
            if(operation!=null)ChatSelectedOutputFinalizationService.requirePersistedReceipt(operation);
            if (operation != null && delivery.getDeliveryId().equals(operation.deliveryId()) && p.manifest().equals(delivery.getManifestArtifactId())) {
                JsonNode facts;
                try { facts = JSON.readTree(p.facts()); } catch (Exception failure) { throw new Storage(failure); }
                if (!facts.isArray() || facts.size() != operation.selections().size() + 1 || facts.size() != delivery.getItems().size()) throw new Conflict();
                Set<String> seen = new HashSet<>();
                for (int i=0;i<facts.size();i++) {
                    var fact = facts.get(i);
                    if (!fact.path("id").isTextual() || !fact.path("version").isIntegralNumber() || !fact.path("version").canConvertToInt()
                            || !fact.path("hash").isTextual() || !fact.path("purpose").isTextual()) throw new Conflict();
                    String id=fact.path("id").textValue(); int version=fact.path("version").intValue();
                    var item=delivery.getItems().stream().filter(v -> id.equals(v.getArtifactId()) && Integer.valueOf(version).equals(v.getArtifactVersion())
                            && fact.path("hash").textValue().equals(v.getContentHash()) && fact.path("purpose").textValue().equals(v.getPurpose())).findFirst().orElseThrow(Conflict::new);
                    String key=id+":"+version; if (!seen.add(key)) throw new Conflict();
                    if (i == facts.size()-1) { if (!id.equals(delivery.getManifestArtifactId()) || !Integer.valueOf(version).equals(delivery.getManifestArtifactVersion())) throw new Conflict(); continue; }
                    var source=operation.selections().get(i);
                    if (!source.sha256().equals(item.getContentHash()) || !source.purpose().equals(item.getPurpose())) throw new Conflict();
                    Object value;
                    if (source.formalArtifactSource()!=null) {
                        var f=source.formalArtifactSource();
                        value=Map.of("kind","FORMAL_ARTIFACT","sourceDeliveryId",f.sourceDeliveryId(),
                                "sourceArtifactId",f.sourceArtifactId(),"sourceArtifactVersion",Integer.toString(f.sourceArtifactVersion()));
                    } else if (source.messageSource()!=null) {
                        var m=source.messageSource();
                        if (m.finalDigest()==null || !m.finalDigest().matches("sha256:[0-9a-f]{64}")) throw new Conflict();
                        value=Map.of("kind","COMPLETED_MESSAGE","requestId",source.requestId(),"messageSource",Map.of(
                                "turnId",m.turnId(),"messageId",m.messageId(),"snapshotId",m.snapshotId(),"finalSha256",m.finalDigest().substring(7)));
                    } else value=Map.of("kind","CONVERSATION_OUTPUT","requestId",source.requestId(),"stepId",source.stepId(),"outputId",source.outputId());
                    mapped.put(key,value);
                }
            }
        }
        List<Item> result=new ArrayList<>();
        for(var item:delivery.getItems()) {
            boolean manifest=item.getArtifactId().equals(delivery.getManifestArtifactId()) && item.getArtifactVersion().equals(delivery.getManifestArtifactVersion());
            Object source=mapped.get(item.getArtifactId()+":"+item.getArtifactVersion());
            if (!manifest && source==null) source=workspaceSource(s,task,delivery.getDeliveryId(),item.getArtifactId(),item.getArtifactVersion(),item.getContentHash());
            result.add(new Item(item.getArtifactId(),item.getArtifactVersion().toString(),item.getContentHash(),item.getPurpose(),
                    manifest?"MANIFEST":"RESULT",source,manifest||source!=null?null:"SOURCE_UNAVAILABLE"));
        }
        return List.copyOf(result);
    }
    private Object workspaceSource(Scope s,String task,String delivery,String artifact,int version,String hash) {
        List<Map<String,Object>> rows=jdbc.query("""
            SELECT o.workspace_file_id,o.workspace_file_version FROM agent_personal_workspace_execution_output o
             JOIN agent_personal_workspace_execution e ON BINARY e.tenant_id=BINARY o.tenant_id
              AND BINARY e.client_id=BINARY o.client_id AND BINARY e.owner_jiacn=BINARY o.owner_jiacn
              AND BINARY e.execution_id=BINARY o.execution_id
            WHERE BINARY o.tenant_id=BINARY ? AND BINARY o.client_id=BINARY ? AND BINARY o.owner_jiacn=BINARY ?
             AND BINARY e.task_id=BINARY ? AND BINARY o.formal_delivery_id=BINARY ? AND BINARY o.artifact_id=BINARY ?
             AND o.artifact_version=? AND BINARY o.content_hash=BINARY ? AND o.output_state='COMMITTED'
             AND o.publication_state='PUBLISHED' LIMIT 2
            """, (r,n)->Map.<String,Object>of("kind","WORKSPACE_FILE","fileId",r.getString(1),"version",Integer.toString(r.getInt(2))),
                s.tenantId(),s.clientId(),s.ownerJiacn(),task,delivery,artifact,version,hash);
        if(rows.size()>1) throw new Conflict(); return rows.isEmpty()?null:rows.getFirst();
    }
    public static boolean sourcesComplete(List<Item> items) {
        return items.stream().anyMatch(i->"RESULT".equals(i.displayRole())) && items.stream().allMatch(i->i.unavailableReason()==null);
    }
}
