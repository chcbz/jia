package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskStateDTO;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentSelectedOutputFinalizationException;
import cn.jia.agent.service.AgentSelectedOutputFinalizationService;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static cn.jia.agent.service.AgentSelectedOutputFinalizationException.Reason;

/** No-Provider adapter from verified conversation bytes to the existing formal delivery chain. */
@Named
@ConditionalOnProperty(prefix="jia.agent.selected-output-finalization",name="enabled",havingValue="true")
public final class AgentSelectedOutputFinalizationServiceImpl implements AgentSelectedOutputFinalizationService {
    private static final long LEASE_MILLIS=900_000L;
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private final JdbcTemplate jdbc;
    private final AgentTaskMutationTransaction transactions;
    private final AgentTaskExecutionGrantService grants;
    private final AgentWorkItemLeaseService leases;
    private final AgentTaskStateService taskStates;
    private final AgentTaskArtifactService artifacts;
    private final AgentTaskFormalDeliveryService submissions;
    private final AgentTaskFormalDeliveryReadService reads;
    private final AgentTaskFormalDeliveryDecisionService decisions;
    private final ObjectMapper json;

    @Inject
    public AgentSelectedOutputFinalizationServiceImpl(JdbcTemplate jdbc,
            AgentTaskMutationTransaction transactions, AgentTaskExecutionGrantService grants,
            AgentWorkItemLeaseService leases, AgentTaskStateService taskStates,
            AgentTaskArtifactService artifacts, AgentTaskFormalDeliveryService submissions,
            AgentTaskFormalDeliveryReadService reads,
            AgentTaskFormalDeliveryDecisionService decisions, ObjectMapper json) {
        this.jdbc=Objects.requireNonNull(jdbc); this.transactions=Objects.requireNonNull(transactions);
        this.grants=Objects.requireNonNull(grants); this.leases=Objects.requireNonNull(leases);
        this.taskStates=Objects.requireNonNull(taskStates); this.artifacts=Objects.requireNonNull(artifacts);
        this.submissions=Objects.requireNonNull(submissions); this.reads=Objects.requireNonNull(reads);
        this.decisions=Objects.requireNonNull(decisions); this.json=Objects.requireNonNull(json);
    }

    @Override
    public PromotionView prepare(Scope scope, PrepareCommand command) {
        Valid valid=validate(scope,command);
        PromotionView committed=committedView(scope,valid.taskId(),valid.operationId(),valid.immutableDigest());
        if (committed!=null) return committed;
        Authority existing=find(scope,valid.taskId(),valid.operationId(),false);
        if (existing!=null) {
            requireReplay(existing,valid);
            if (phaseAtLeast(existing.phase(),"READY"))
                return reconcile(scope,valid.taskId(),valid.operationId(),valid.immutableDigest());
        }
        LeaseFacts lease=reserveLease(scope,valid);
        Authority reserved=requireAuthority(scope,valid.taskId(),valid.operationId(),valid.immutableDigest(),false);
        if (phaseAtLeast(reserved.phase(),"READY"))
            return reconcile(scope,valid.taskId(),valid.operationId(),valid.immutableDigest());
        List<ArtifactFact> published=publishAll(scope,valid,lease.workItemId());
        persistReady(scope,valid,published);
        return reconcile(scope,valid.taskId(),valid.operationId(),valid.immutableDigest());
    }

    @Override
    public PromotionView submit(Scope scope,String taskId,String operationId,String immutableDigest) {
        validateLookup(scope,taskId,operationId,immutableDigest);
        PromotionView committed=committedView(scope,taskId,operationId,immutableDigest);
        if (committed!=null) return committed;
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> submitLocked(scope,taskId,operationId,immutableDigest));
    }

    private PromotionView submitLocked(Scope scope,String taskId,String operationId,String digest) {
        PromotionView committed=committedView(scope,taskId,operationId,digest);
        if (committed!=null) return committed;
        Authority row=requireAuthority(scope,taskId,operationId,digest,true);
        if (!"READY".equals(row.phase())) throw conflict("Promotion is not ready for formal submission");
        admit(scope,row);
        List<ArtifactFact> facts=readFacts(row);
        TaskFact task=task(scope,taskId);
        AgentTaskFormalDeliverySubmitDTO command=new AgentTaskFormalDeliverySubmitDTO();
        command.setDeliveryId(row.deliveryId()); command.setRunId(row.runId());
        command.setWorkItemId(row.workItemId()); command.setLeaseToken(row.leaseToken());
        command.setExpectedTaskVersion(task.version()); command.setExpectedWorkItemVersion(row.leaseVersion());
        command.setSummary(row.summary()); command.setManifestArtifactId(row.manifestArtifactId());
        command.setManifestArtifactVersion(1); command.setItems(facts.stream().map(this::formalItem).toList());
        AgentTaskFormalDeliveryViewDTO result=collaboration(() -> submissions.submit(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,row.targetAgentId(),command));
        requireDelivery(result,row.deliveryId(),taskId,"submitted");
        markSubmitted(scope,row);
        return new PromotionView(operationId,taskId,"SUBMITTED",row.deliveryId(),result.getState(),
                "reviewing",requiredVersion(result.getTaskVersion(),"delivery task version"));
    }

    @Override
    public PromotionView accept(Scope scope,String taskId,String operationId,String immutableDigest) {
        validateLookup(scope,taskId,operationId,immutableDigest);
        PromotionView committed=committedView(scope,taskId,operationId,immutableDigest);
        if (committed!=null && "accepted".equals(committed.deliveryState())) return committed;
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> acceptLocked(scope,taskId,operationId,immutableDigest));
    }

    private PromotionView acceptLocked(Scope scope,String taskId,String operationId,String digest) {
        PromotionView committed=committedView(scope,taskId,operationId,digest);
        if (committed!=null && "accepted".equals(committed.deliveryState())) return committed;
        Authority row=requireAuthority(scope,taskId,operationId,digest,true);
        AgentTaskFormalDeliveryViewDTO delivery=findDelivery(scope,taskId,row.deliveryId());
        if (delivery==null) throw conflict("Formal delivery has not been submitted");
        if ("changes_requested".equals(delivery.getState())) {
            TaskFact task=task(scope,taskId);
            return new PromotionView(operationId,taskId,"SUBMITTED",row.deliveryId(),
                    "changes_requested",task.state(),task.version());
        }
        if (!"accepted".equals(delivery.getState())) {
            if (!"submitted".equals(delivery.getState()))throw conflict("Formal delivery is no longer acceptable");
            AgentTaskFormalDeliveryDecisionDTO command=new AgentTaskFormalDeliveryDecisionDTO();
            command.setDeliveryId(row.deliveryId()); command.setExpectedTaskVersion(delivery.getTaskVersion());
            command.setExpectedDeliveryVersion(delivery.getDeliveryVersion()); command.setDecision("accepted");
            delivery=collaboration(() -> decisions.decide(
                    scope.tenantId(),scope.clientId(),taskId,scope.ownerJiacn(),command));
        }
        requireDelivery(delivery,row.deliveryId(),taskId,"accepted");
        TaskFact task=task(scope,taskId);
        if (!"completed".equals(task.state())) throw persistence("Accepted delivery did not complete task",null);
        markAccepted(scope,row);
        return new PromotionView(operationId,taskId,"TASK_COMPLETED",row.deliveryId(),
                "accepted",task.state(),task.version());
    }

    @Override
    public PromotionView reconcile(Scope scope,String taskId,String operationId,String immutableDigest) {
        validateLookup(scope,taskId,operationId,immutableDigest);
        PromotionView committed=committedView(scope,taskId,operationId,immutableDigest);
        if (committed!=null) return committed;
        Authority row=requireAuthority(scope,taskId,operationId,immutableDigest,false);
        TaskFact task=task(scope,taskId);
        return switch(row.phase()) {
            case "CLAIMING","LEASED" -> new PromotionView(operationId,taskId,"PROMOTING",null,null,
                    task.state(),task.version());
            case "READY" -> new PromotionView(operationId,taskId,"READY_TO_SUBMIT",null,null,
                    task.state(),task.version());
            case "SUBMITTED","ACCEPTED" -> throw persistence("Promotion phase has no formal delivery",null);
            default -> throw persistence("Unknown promotion phase",null);
        };
    }

    /** Reconciles durable formal facts before any now-obsolete task/grant/lease validation. */
    private PromotionView committedView(Scope scope,String taskId,String operationId,String digest) {
        Authority row=find(scope,taskId,operationId,false);
        if (row==null) return null;
        if (!digest.equals(row.digest())) throw conflict("Operation immutable digest conflicts");
        AgentTaskFormalDeliveryViewDTO delivery=findDelivery(scope,taskId,row.deliveryId());
        if (delivery==null) {
            if ("SUBMITTED".equals(row.phase()) || "ACCEPTED".equals(row.phase()))
                throw persistence("Promotion phase has no formal delivery",null);
            return null;
        }
        String state=delivery.getState();
        if (!List.of("submitted","accepted","changes_requested").contains(state))
            throw persistence("Unknown formal delivery state",null);
        TaskFact task=task(scope,taskId);
        if ("accepted".equals(state)) {
            if (!"completed".equals(task.state()))
                throw persistence("Accepted delivery did not complete task",null);
            if ("READY".equals(row.phase())) {
                markSubmitted(scope,row);
                row=requireAuthority(scope,taskId,operationId,digest,false);
            }
            if (!"ACCEPTED".equals(row.phase())) markAccepted(scope,row);
            return new PromotionView(operationId,taskId,"TASK_COMPLETED",row.deliveryId(),
                    "accepted",task.state(),task.version());
        }
        if ("ACCEPTED".equals(row.phase()))
            throw persistence("Accepted promotion authority regressed",null);
        if ("READY".equals(row.phase())) markSubmitted(scope,row);
        return new PromotionView(operationId,taskId,"SUBMITTED",row.deliveryId(),state,
                task.state(),task.version());
    }

    /** Root lock + authority row + real lease claim/start + task start commit atomically. */
    private LeaseFacts reserveLease(Scope scope,Valid valid) {
        return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),valid.taskId(),root -> {
                    Authority row=find(scope,valid.taskId(),valid.operationId(),true);
                    if (row==null) {
                        insertClaiming(scope,valid);
                        row=requireAuthority(scope,valid.taskId(),valid.operationId(),valid.immutableDigest(),true);
                    }
                    requireReplay(row,valid);
                    if (phaseAtLeast(row.phase(),"READY"))
                        return new LeaseFacts(row.workItemId(),row.leaseToken(),requiredLong(row.leaseVersion(),"lease version"));
                    admit(scope,valid);
                    WorkFact work=singleRequiredWork(scope,valid.taskId());
                    if ("LEASED".equals(row.phase())) {
                        if (!Objects.equals(row.workItemId(),work.workItemId())
                                || !Objects.equals(row.leaseToken(),work.token())
                                || !Objects.equals(row.leaseVersion(),work.version())
                                || !valid.targetAgentId().equals(work.agentId())
                                || !"running".equals(work.state()))
                            throw conflict("Persisted promotion lease is no longer current");
                        AgentWorkItemLeaseCommandDTO check=new AgentWorkItemLeaseCommandDTO();
                        check.setAgentId(valid.targetAgentId()); check.setLeaseToken(row.leaseToken());
                        check.setExpectedVersion(row.leaseVersion());
                        AgentWorkItemLeaseDTO verified=collaboration(() -> leases.validateLeaseForResult(
                                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),valid.taskId(),work.workItemId(),check));
                        requireLease(verified,valid,work.workItemId(),row.leaseToken(),row.leaseVersion());
                        ensureTaskRunning(scope,valid.taskId(),valid.expectedTaskVersion());
                        return new LeaseFacts(work.workItemId(),row.leaseToken(),row.leaseVersion());
                    }
                    if (!"CLAIMING".equals(row.phase()) || !"ready".equals(work.state())
                            || (work.agentId()!=null && !valid.targetAgentId().equals(work.agentId())))
                        throw conflict("Required work item is not available for promotion");
                    TaskFact initial=task(scope,valid.taskId());
                    if (initial.version()!=valid.expectedTaskVersion()
                            || !("assigned".equals(initial.state()) || "running".equals(initial.state())))
                        throw conflict("Task is not at the selected version");
                    AgentWorkItemLeaseCommandDTO claim=new AgentWorkItemLeaseCommandDTO();
                    claim.setAgentId(valid.targetAgentId()); claim.setExpectedVersion(work.version());
                    claim.setLeaseDurationMillis(LEASE_MILLIS);
                    AgentWorkItemLeaseDTO lease=collaboration(() -> leases.claim(scope.tenantId(),scope.clientId(),
                            scope.ownerJiacn(),valid.taskId(),work.workItemId(),claim));
                    if (lease!=null && "claimed".equals(lease.getStatus())) {
                        AgentWorkItemLeaseCommandDTO start=new AgentWorkItemLeaseCommandDTO();
                        start.setAgentId(valid.targetAgentId()); start.setLeaseToken(lease.getLeaseToken());
                        start.setExpectedVersion(lease.getVersion());
                        lease=collaboration(() -> leases.start(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                                valid.taskId(),work.workItemId(),start));
                    }
                    requireLease(lease,valid,work.workItemId(),null,null);
                    ensureTaskRunning(scope,valid.taskId(),valid.expectedTaskVersion());
                    persistLease(scope,valid,work.workItemId(),lease);
                    return new LeaseFacts(work.workItemId(),lease.getLeaseToken(),lease.getVersion());
                });
    }

    private void admit(Scope scope,Valid valid) {
        admit(scope,valid.taskId(),valid.grantId(),valid.grantVersion(),
                valid.assignmentRevision(),valid.targetAgentId());
    }
    private void admit(Scope scope,Authority row) {
        admit(scope,row.taskId(),row.grantId(),row.grantVersion(),row.assignmentRevision(),row.targetAgentId());
    }
    private void admit(Scope scope,String taskId,String grantId,long grantVersion,
            long assignmentRevision,String targetAgentId) {
        try {
            var admission=grants.admitSelectedOutputPromotion(new AgentTaskExecutionGrantService.Scope(
                    scope.tenantId(),scope.clientId(),scope.ownerJiacn()),taskId,grantId,grantVersion,
                    assignmentRevision,targetAgentId);
            if (admission==null || !grantId.equals(admission.grantId())
                    || admission.grantVersion()!=grantVersion
                    || admission.assignmentRevision()!=assignmentRevision
                    || !targetAgentId.equals(admission.targetAgentId())
                    || !"FINALIZE_SELECTED_OUTPUTS".equals(admission.operation())
                    || admission.paidExecutionAuthorized()) throw grantChanged();
        } catch (AgentTaskExecutionGrantException denied) {
            throw grantChanged();
        }
    }

    private void ensureTaskRunning(Scope scope,String taskId,long originalExpected) {
        TaskFact task=task(scope,taskId);
        if ("running".equals(task.state())) {
            if (task.version()!=originalExpected && task.version()!=originalExpected+1)
                throw conflict("Task is not at the selected assignment version");
            return;
        }
        if (!"assigned".equals(task.state()) || task.version()!=originalExpected)
            throw conflict("Task is not at the selected assignment version");
        AgentTaskStateTransitionDTO transition=new AgentTaskStateTransitionDTO();
        transition.setTargetStatus("running"); transition.setExpectedVersion(task.version());
        AgentTaskStateDTO changed=collaboration(() -> taskStates.transitionTask(
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,transition));
        if (changed==null || !"running".equals(changed.getStatus())
                || !Objects.equals(originalExpected+1,changed.getVersion()))
            throw persistence("Task start returned an invalid snapshot",null);
    }

    private List<ArtifactFact> publishAll(Scope scope,Valid valid,String workItemId) {
        List<ArtifactFact> result=new ArrayList<>(); int index=0;
        for (SourceOutput output:valid.outputs()) {
            String id=artifactId(valid.operationId(),index++);
            result.add(publish(scope,valid,workItemId,id,"document",output.title(),output.purpose(),
                    output.contentMimeType(),output.sha256(),output.byteLength(),output.bytes()));
        }
        byte[] manifest=manifest(valid).getBytes(StandardCharsets.UTF_8);
        result.add(publish(scope,valid,workItemId,valid.manifestArtifactId(),"summary",
                "Selected output manifest","manifest","application/json",sha256(manifest),manifest.length,manifest));
        return List.copyOf(result);
    }

    private ArtifactFact publish(Scope scope,Valid valid,String workItemId,String artifactId,String type,
            String title,String purpose,String mime,String hash,long length,byte[] bytes) {
        try {
            AgentTaskArtifactViewDTO existing=artifacts.getVersion(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),valid.taskId(),valid.targetAgentId(),artifactId,1);
            if (existing!=null)return exactArtifact(existing,valid,workItemId,artifactId,type,title,hash,length,mime,purpose);
        } catch (AgentTaskCollaborationException absent) {
            if (absent.getReason()!=AgentTaskCollaborationException.Reason.NOT_FOUND) throw translate(absent);
        }
        AgentTaskArtifactPublishDTO command=new AgentTaskArtifactPublishDTO();
        command.setArtifactId(artifactId); command.setWorkItemId(workItemId);
        command.setProducerAgentId(valid.targetAgentId()); command.setArtifactType(type);
        command.setTitle(title); command.setContentBytes(bytes); command.setContentMimeType(mime);
        command.setContentHash(hash); command.setContentByteLength(length);
        command.setArtifactVersion(1); command.setExpectedPreviousVersion(0); command.setVisibility("task_members");
        command.setMetadata(Map.of("source","conversation_selected_output","operationId",valid.operationId(),
                "purpose",purpose));
        AgentTaskArtifactViewDTO value=collaboration(() -> artifacts.publish(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),valid.taskId(),valid.targetAgentId(),command));
        return exactArtifact(value,valid,workItemId,artifactId,type,title,hash,length,mime,purpose);
    }

    private ArtifactFact exactArtifact(AgentTaskArtifactViewDTO value,Valid valid,String workItemId,String id,
            String type,String title,String hash,long length,String mime,String purpose) {
        Map<String,Object> metadata=value==null?null:value.getMetadata();
        if (value==null || !id.equals(value.getArtifactId()) || !valid.taskId().equals(value.getTaskId())
                || !workItemId.equals(value.getWorkItemId()) || !valid.targetAgentId().equals(value.getProducerAgentId())
                || !type.equals(value.getArtifactType()) || !title.equals(value.getTitle())
                || !"task_members".equals(value.getVisibility()) || !Integer.valueOf(1).equals(value.getArtifactVersion())
                || !hash.equals(value.getContentHash()) || !Objects.equals(length,value.getContentByteLength())
                || !mime.equals(value.getContentMimeType()) || !Boolean.TRUE.equals(value.getManagedStorage())
                || value.getContent()!=null || value.getStorageUri()==null || value.getStorageUri().isBlank() || metadata==null
                || !"conversation_selected_output".equals(metadata.get("source"))
                || !valid.operationId().equals(metadata.get("operationId"))
                || !purpose.equals(metadata.get("purpose")))
            throw persistence("Artifact readback mismatch",null);
        return new ArtifactFact(id,1,hash,purpose);
    }

    private String manifest(Valid valid) {
        try {
            Map<String,Object> root=new LinkedHashMap<>(); root.put("schemaVersion",1);
            root.put("operationId",valid.operationId()); root.put("taskId",valid.taskId());
            root.put("conversationId",valid.conversationId()); root.put("conversationGeneration",valid.conversationGeneration());
            root.put("summary",valid.summary());
            List<Map<String,Object>> selected=new ArrayList<>();
            for (SourceOutput output:valid.outputs()) {
                Map<String,Object> item=new LinkedHashMap<>();
                item.put("requestId",output.requestId());
                if (output.messageSource() == null) {
                    item.put("stepId",output.stepId());
                    item.put("executionId",output.executionId()); item.put("runId",output.runId());
                    item.put("outputId",output.outputId());
                } else {
                    item.put("sourceKind","COMPLETED_MESSAGE");
                    item.put("turnId",output.messageSource().turnId());
                    item.put("messageId",output.messageSource().messageId());
                    item.put("snapshotId",output.messageSource().snapshotId());
                    item.put("finalDigest",output.messageSource().finalDigest());
                }
                item.put("sha256",output.sha256());
                item.put("contentMimeType",output.contentMimeType()); item.put("byteLength",output.byteLength());
                item.put("title",output.title()); item.put("purpose",output.purpose()); selected.add(item);
            }
            root.put("selectedOutputs",selected);
            return json.writeValueAsString(root);
        } catch (Exception failure) { throw persistence("Unable to serialize selected-output manifest",failure); }
    }

    private AgentTaskFormalDeliveryItemDTO formalItem(ArtifactFact fact) {
        AgentTaskFormalDeliveryItemDTO item=new AgentTaskFormalDeliveryItemDTO();
        item.setArtifactId(fact.id()); item.setArtifactVersion(fact.version());
        item.setContentHash(fact.hash()); item.setPurpose(fact.purpose()); return item;
    }

    private Valid validate(Scope scope,PrepareCommand command) {
        validScope(scope); if (command==null) throw bad("Command is required");
        id(command.operationId(),100); id(command.taskId(),100); id(command.conversationId(),100);
        id(command.targetAgentId(),100); id(command.grantId(),100);
        if (command.expectedTaskVersion()<0 || command.expectedTaskVersion()>MAX_SAFE_INTEGER
                || command.expectedAssignmentRevision()<0 || command.expectedAssignmentRevision()>MAX_SAFE_INTEGER
                || command.grantVersion()<1 || command.grantVersion()>MAX_SAFE_INTEGER
                || command.conversationGeneration()<1 || command.conversationGeneration()>MAX_SAFE_INTEGER)
            throw bad("Invalid version");
        text(command.summary(),4000);
        if (command.immutableDigest()==null || !command.immutableDigest().matches("[0-9a-f]{64}"))
            throw bad("Invalid digest");
        if (command.outputs()==null || command.outputs().isEmpty() || command.outputs().size()>99)
            throw bad("Invalid selected output count");
        var seen=new java.util.HashSet<String>();
        for (SourceOutput output:command.outputs()) {
            if (output==null) throw bad("Output is required");
            id(output.requestId(),100);
            text(output.title(),255); text(output.purpose(),255);
            String sourceKey;
            if (output.messageSource() == null) {
                id(output.stepId(),100); id(output.executionId(),100); id(output.runId(),100); id(output.outputId(),100);
                sourceKey=output.requestId()+"\0"+output.stepId()+"\0"+output.outputId();
            } else {
                var message=output.messageSource(); id(message.turnId(),100); id(message.snapshotId(),100);
                if (message.messageId()==null || !message.messageId().matches("[1-9][0-9]{0,18}")
                        || new java.math.BigInteger(message.messageId()).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))>0
                        || message.finalDigest()==null || !message.finalDigest().matches("sha256:[0-9a-f]{64}")
                        || output.stepId()!=null || output.executionId()!=null || output.runId()!=null || output.outputId()!=null
                        || !"text/plain".equals(output.contentMimeType()) || output.bytes()==null)
                    throw bad("Invalid completed message source");
                String content=new String(output.bytes(),StandardCharsets.UTF_8);
                if (content.isBlank() || !java.util.Arrays.equals(output.bytes(),content.getBytes(StandardCharsets.UTF_8)))
                    throw bad("Invalid completed message bytes");
                sourceKey="COMPLETED_MESSAGE\0"+output.requestId()+"\0"+output.stepId()+"\0"+message.turnId()+"\0"+message.messageId();
            }
            if (output.sha256()==null || !output.sha256().matches("[0-9a-f]{64}")
                    || output.byteLength()<0 || output.byteLength()>MAX_SAFE_INTEGER || output.bytes()==null
                    || output.bytes().length!=output.byteLength() || !output.sha256().equals(sha256(output.bytes()))
                    || output.contentMimeType()==null
                    || !output.contentMimeType().matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
                    || !seen.add(sourceKey))
                throw bad("Invalid selected output");
        }
        String expectedDigest=SelectedOutputFinalizationDigest.request(command.taskId(),command.expectedTaskVersion(),
                command.expectedAssignmentRevision(),command.conversationId(),command.summary(),command.outputs().stream()
                .map(o->new SelectedOutputFinalizationDigest.Selection(o.requestId(),o.stepId(),o.outputId(),
                        o.sha256(),o.title(),o.purpose(),o.messageSource())).toList());
        if (!expectedDigest.equals(command.immutableDigest())) throw bad("Immutable digest mismatch");
        String sourceDigest=sourceDigest(command.outputs());
        String suffix=sha256((scope.tenantId()+"\n"+scope.clientId()+"\n"+scope.ownerJiacn()+"\n"+command.operationId())
                .getBytes(StandardCharsets.UTF_8));
        return new Valid(command.operationId(),command.taskId(),command.expectedTaskVersion(),
                command.expectedAssignmentRevision(),command.conversationId(),command.conversationGeneration(),
                command.targetAgentId(),command.grantId(),command.grantVersion(),command.summary(),
                command.immutableDigest(),sourceDigest,command.outputs(),"finalization_run_"+suffix.substring(0,64),
                "finalization_delivery_"+suffix.substring(0,64),"finalization_manifest_"+suffix.substring(0,64));
    }

    private void insertClaiming(Scope scope,Valid v) {
        long now=System.currentTimeMillis();
        try {
            jdbc.update("""
                INSERT INTO agent_selected_output_finalization
                (operation_id,tenant_id,client_id,owner_jiacn,task_id,immutable_digest,source_facts_digest,
                 expected_task_version,expected_assignment_revision,conversation_id,conversation_generation,
                 target_agent_id,grant_id,grant_version,run_id,summary,delivery_id,manifest_artifact_id,
                 phase,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CLAIMING',0,?,?)
                """,v.operationId(),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),v.taskId(),v.immutableDigest(),
                v.sourceDigest(),v.expectedTaskVersion(),v.assignmentRevision(),v.conversationId(),v.conversationGeneration(),
                v.targetAgentId(),v.grantId(),v.grantVersion(),v.runId(),v.summary(),v.deliveryId(),v.manifestArtifactId(),now,now);
        } catch (org.springframework.dao.DuplicateKeyException race) { /* exact replay is checked under the root lock */ }
    }

    private void persistLease(Scope scope,Valid v,String workItemId,AgentWorkItemLeaseDTO lease) {
        int n=jdbc.update("""
                UPDATE agent_selected_output_finalization SET work_item_id=?,lease_token=?,lease_work_item_version=?,
                 phase='LEASED',version=version+1,updated_at=? WHERE BINARY tenant_id=BINARY ?
                 AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY operation_id=BINARY ?
                 AND BINARY task_id=BINARY ? AND BINARY immutable_digest=BINARY ? AND phase='CLAIMING'
                """,workItemId,lease.getLeaseToken(),lease.getVersion(),System.currentTimeMillis(),scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),v.operationId(),v.taskId(),v.immutableDigest());
        if (n!=1) throw persistence("Unable to persist private lease authority",null);
    }

    private void persistReady(Scope scope,Valid v,List<ArtifactFact> facts) {
        try {
            String value=json.writeValueAsString(facts);
            int n=jdbc.update("""
                    UPDATE agent_selected_output_finalization SET artifact_facts_json=?,phase='READY',
                     version=version+1,updated_at=? WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ?
                     AND BINARY owner_jiacn=BINARY ? AND BINARY operation_id=BINARY ? AND BINARY task_id=BINARY ?
                     AND BINARY immutable_digest=BINARY ? AND phase IN ('LEASED','READY')
                    """,value,System.currentTimeMillis(),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                    v.operationId(),v.taskId(),v.immutableDigest());
            if (n!=1) throw persistence("Unable to persist promotion facts",null);
        } catch (AgentSelectedOutputFinalizationException e) { throw e; }
        catch (Exception failure) { throw persistence("Unable to persist promotion facts",failure); }
    }

    private void markSubmitted(Scope scope,Authority row) {
        int n=jdbc.update("""
                UPDATE agent_selected_output_finalization SET lease_token=NULL,phase='SUBMITTED',
                 version=version+1,updated_at=? WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ?
                 AND BINARY owner_jiacn=BINARY ? AND BINARY operation_id=BINARY ? AND BINARY task_id=BINARY ?
                 AND BINARY immutable_digest=BINARY ? AND phase IN ('READY','SUBMITTED')
                """,System.currentTimeMillis(),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                row.operationId(),row.taskId(),row.digest());
        if (n!=1) throw persistence("Unable to persist submitted phase",null);
    }
    private void markAccepted(Scope scope,Authority row) {
        int n=jdbc.update("""
                UPDATE agent_selected_output_finalization SET phase='ACCEPTED',version=version+1,updated_at=?
                 WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ?
                 AND BINARY operation_id=BINARY ? AND BINARY task_id=BINARY ? AND BINARY immutable_digest=BINARY ?
                 AND phase IN ('SUBMITTED','ACCEPTED')
                """,System.currentTimeMillis(),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                row.operationId(),row.taskId(),row.digest());
        if (n!=1) throw persistence("Unable to persist accepted phase",null);
    }

    private Authority find(Scope scope,String taskId,String operationId,boolean lock) {
        List<Authority> rows=jdbc.query("""
                SELECT operation_id,task_id,immutable_digest,source_facts_digest,expected_task_version,
                 expected_assignment_revision,conversation_id,conversation_generation,target_agent_id,grant_id,
                 grant_version,work_item_id,run_id,summary,lease_token,lease_work_item_version,delivery_id,
                 manifest_artifact_id,artifact_facts_json,phase,created_at,updated_at
                FROM agent_selected_output_finalization WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ?
                 AND BINARY owner_jiacn=BINARY ? AND BINARY task_id=BINARY ? AND BINARY operation_id=BINARY ?
                """+(lock?" FOR UPDATE":""),(rs,n)->new Authority(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getLong(5),rs.getLong(6),rs.getString(7),rs.getLong(8),rs.getString(9),
                rs.getString(10),rs.getLong(11),rs.getString(12),rs.getString(13),rs.getString(14),
                rs.getString(15),(Long)rs.getObject(16),rs.getString(17),rs.getString(18),rs.getString(19),
                rs.getString(20),rs.getLong(21),rs.getLong(22)),scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,operationId);
        if (rows.size()>1) throw persistence("Ambiguous promotion authority",null);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Authority requireAuthority(Scope scope,String taskId,String operationId,String digest,boolean lock) {
        Authority row=find(scope,taskId,operationId,lock); if (row==null) throw notFound();
        if (!digest.equals(row.digest())) throw conflict("Operation immutable digest conflicts"); return row;
    }

    private WorkFact singleRequiredWork(Scope scope,String taskId) {
        List<WorkFact> rows=jdbc.query("""
                SELECT work_item_id,status,assignee_agent_id,lease_token,lease_until,version,update_time
                FROM agent_task_work_item WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ?
                 AND BINARY owner_jiacn=BINARY ? AND BINARY task_id=BINARY ? AND required_item=1
                ORDER BY BINARY work_item_id LIMIT 2
                """,(rs,n)->new WorkFact(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                (Long)rs.getObject(5),rs.getLong(6),Objects.requireNonNullElse((Long)rs.getObject(7),0L)),
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        if (rows.size()!=1) throw conflict("Finalization requires exactly one required work item");
        return rows.getFirst();
    }
    private TaskFact task(Scope scope,String taskId) {
        List<TaskFact> rows=jdbc.query("""
                SELECT reward_status,task_version FROM agent_task_meta WHERE BINARY tenant_id=BINARY ?
                 AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY task_id=BINARY ?
                """,(rs,n)->new TaskFact(rs.getString(1),rs.getLong(2)),scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId);
        if (rows.size()!=1)throw notFound();
        TaskFact task=rows.getFirst();
        if(task.state()==null||task.state().isBlank()||task.version()<0||task.version()>MAX_SAFE_INTEGER)
            throw persistence("Task snapshot is invalid",null);
        return task;
    }
    private AgentTaskFormalDeliveryViewDTO findDelivery(Scope scope,String taskId,String id) {
        List<AgentTaskFormalDeliveryViewDTO> rows=collaboration(() -> reads.listForTaskOwner(
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId));
        if (rows==null) throw persistence("Delivery catalog unavailable",null);
        return rows.stream().filter(v->v!=null && id.equals(v.getDeliveryId())).findFirst().orElse(null);
    }
    private List<ArtifactFact> readFacts(Authority row) {
        if (row.factsJson()==null) throw persistence("Promotion artifact facts unavailable",null);
        try {
            var type=json.getTypeFactory().constructCollectionType(List.class,ArtifactFact.class);
            List<ArtifactFact> facts=json.readValue(row.factsJson(),type);
            if (facts==null || facts.isEmpty() || facts.size()>100
                    || facts.stream().anyMatch(v->v==null || v.version()!=1 || v.id()==null
                    || !v.id().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                    || v.hash()==null || !v.hash().matches("[0-9a-f]{64}")
                    || v.purpose()==null || v.purpose().isBlank() || v.purpose().length()>255))
                throw new IllegalStateException();
            return List.copyOf(facts);
        } catch (Exception failure) { throw persistence("Promotion artifact facts corrupt",failure); }
    }

    private void requireReplay(Authority row,Valid v) {
        if (row==null || !v.immutableDigest().equals(row.digest()) || !v.sourceDigest().equals(row.sourceDigest())
                || !v.taskId().equals(row.taskId()) || v.expectedTaskVersion()!=row.expectedTaskVersion()
                || v.assignmentRevision()!=row.assignmentRevision() || !v.conversationId().equals(row.conversationId())
                || v.conversationGeneration()!=row.conversationGeneration()
                || !v.targetAgentId().equals(row.targetAgentId()) || !v.grantId().equals(row.grantId())
                || v.grantVersion()!=row.grantVersion() || !v.runId().equals(row.runId())
                || !v.deliveryId().equals(row.deliveryId()) || !v.manifestArtifactId().equals(row.manifestArtifactId())
                || !v.summary().equals(row.summary())) throw conflict("Operation immutable intent conflicts");
    }
    private void requireDelivery(AgentTaskFormalDeliveryViewDTO v,String id,String taskId,String state) {
        if (v==null || !id.equals(v.getDeliveryId()) || !taskId.equals(v.getTaskId()) || !state.equals(v.getState())
                || v.getTaskVersion()==null || v.getTaskVersion()<0 || v.getTaskVersion()>MAX_SAFE_INTEGER)
            throw persistence("Formal delivery readback mismatch",null);
    }
    private void requireLease(AgentWorkItemLeaseDTO value,Valid valid,String workItemId,
            String expectedToken,Long expectedVersion) {
        if (value==null || !valid.taskId().equals(value.getTaskId()) || !workItemId.equals(value.getWorkItemId())
                || !valid.targetAgentId().equals(value.getAgentId()) || !"running".equals(value.getStatus())
                || value.getLeaseToken()==null || value.getVersion()==null || value.getVersion()<0
                || value.getVersion()>MAX_SAFE_INTEGER
                || value.getLeaseUntil()==null || value.getLeaseUntil()<=System.currentTimeMillis()
                || (expectedToken!=null && !expectedToken.equals(value.getLeaseToken()))
                || (expectedVersion!=null && !expectedVersion.equals(value.getVersion())))
            throw persistence("Lease start returned an invalid snapshot",null);
    }
    private static boolean phaseAtLeast(String phase,String wanted) {
        return "READY".equals(wanted) && List.of("READY","SUBMITTED","ACCEPTED").contains(phase);
    }
    private static String artifactId(String operationId,int index) {
        return "finalization_artifact_"+sha256((operationId+"\n"+index).getBytes(StandardCharsets.UTF_8)).substring(0,64);
    }
    private static String sourceDigest(List<SourceOutput> outputs) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update("MMD_SELECTED_OUTPUT_SOURCE_V1".getBytes(StandardCharsets.UTF_8));
            put(digest,outputs.size());
            for (SourceOutput output:outputs) {
                put(digest,output.requestId()); put(digest,output.stepId()); put(digest,output.executionId());
                put(digest,output.runId()); put(digest,output.outputId()); put(digest,output.sha256());
                put(digest,output.contentMimeType()); put(digest,output.byteLength());
                put(digest,output.title()); put(digest,output.purpose());
                if (output.messageSource()!=null) {
                    put(digest,"COMPLETED_MESSAGE"); put(digest,output.messageSource().turnId());
                    put(digest,output.messageSource().messageId()); put(digest,output.messageSource().snapshotId());
                    put(digest,output.messageSource().finalDigest());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void put(MessageDigest digest,long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }
    private static void put(MessageDigest digest,String value) {
        if (value==null) { put(digest,-1L); return; }
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8); put(digest,bytes.length); digest.update(bytes);
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long requiredVersion(Long value,String name) {
        if (value==null || value<0 || value>MAX_SAFE_INTEGER)throw persistence("Invalid "+name,null);
        return value;
    }
    private static long requiredLong(Long value,String name) {
        if (value==null || value<0 || value>MAX_SAFE_INTEGER)throw persistence("Invalid "+name,null);return value;
    }
    private static <T> T collaboration(Supplier<T> call) {
        try{return call.get();}
        catch(AgentTaskCollaborationException failure){throw translate(failure);}
    }
    private static AgentSelectedOutputFinalizationException translate(AgentTaskCollaborationException failure) {
        return switch(failure.getReason()) {
            case FORBIDDEN, RESERVED_FOR_LEASE_PROTOCOL -> new AgentSelectedOutputFinalizationException(
                    Reason.GRANT_CHANGED,"Promotion authority changed",failure);
            case NOT_FOUND, INVALID_TRANSITION, VERSION_CONFLICT -> new AgentSelectedOutputFinalizationException(
                    Reason.CONFLICT,"Promotion prerequisite changed",failure);
            case INVALID_REQUEST, INVALID_PERSISTED_STATE -> persistence(
                    "Trusted finalization dependency rejected persisted authority",failure);
        };
    }
    private static void validateLookup(Scope scope,String taskId,String operationId,String digest) {
        validScope(scope);id(taskId,100);id(operationId,100);
        if(digest==null||!digest.matches("[0-9a-f]{64}"))throw bad("Invalid digest");
    }
    private static void validScope(Scope s) {
        if(s==null||!"0".equals(s.tenantId()))throw notFound();
        id(s.clientId(),50);id(s.ownerJiacn(),50);if("0".equals(s.ownerJiacn()))throw notFound();
    }
    private static void id(String v,int max) {
        if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}"))throw bad("Invalid identifier");
    }
    private static void text(String v,int max) {
        if(v==null||v.isBlank()||v.length()>max||!v.equals(v.strip())||v.indexOf('\0')>=0
                || v.codePoints().anyMatch(Character::isISOControl))throw bad("Invalid text");
    }
    private static AgentSelectedOutputFinalizationException bad(String m){return new AgentSelectedOutputFinalizationException(Reason.BAD_REQUEST,m);}
    private static AgentSelectedOutputFinalizationException notFound(){return new AgentSelectedOutputFinalizationException(Reason.NOT_FOUND,"Finalization is unavailable");}
    private static AgentSelectedOutputFinalizationException conflict(String m){return new AgentSelectedOutputFinalizationException(Reason.CONFLICT,m);}
    private static AgentSelectedOutputFinalizationException grantChanged(){return new AgentSelectedOutputFinalizationException(Reason.GRANT_CHANGED,"Assignment or grant changed");}
    private static AgentSelectedOutputFinalizationException persistence(String m,Throwable c){return new AgentSelectedOutputFinalizationException(Reason.PERSISTENCE_ERROR,m,c);}

    private record Valid(String operationId,String taskId,long expectedTaskVersion,long assignmentRevision,
            String conversationId,long conversationGeneration,String targetAgentId,String grantId,long grantVersion,
            String summary,String immutableDigest,String sourceDigest,List<SourceOutput> outputs,String runId,
            String deliveryId,String manifestArtifactId) { }
    public record ArtifactFact(String id,int version,String hash,String purpose) { }
    private record Authority(String operationId,String taskId,String digest,String sourceDigest,
            long expectedTaskVersion,long assignmentRevision,String conversationId,long conversationGeneration,
            String targetAgentId,String grantId,long grantVersion,String workItemId,String runId,String summary,
            String leaseToken,Long leaseVersion,String deliveryId,String manifestArtifactId,String factsJson,
            String phase,long createdAt,long updatedAt) { }
    private record WorkFact(String workItemId,String state,String agentId,String token,Long leaseUntil,long version,long updatedAt) { }
    private record TaskFact(String state,long version) { }
    private record LeaseFacts(String workItemId,String token,long version) { }
}
