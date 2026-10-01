package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskGrantInputDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentDTO;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentIssueDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static cn.jia.agent.service.AgentTaskProviderCostConsentService.Reason;

/** No-Provider consent core. Production paid admission remains intentionally disconnected. */
@Named
public class AgentTaskProviderCostConsentServiceImpl
        implements AgentTaskProviderCostConsentService {
    static final String ACKNOWLEDGEMENT =
            "UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final List<String> IMAGE_MIMES = List.of("image/jpeg", "image/png");

    private final AgentTaskProviderCostConsentDao consents;
    private final AgentTaskExecutionGrantService grants;
    private final AgentTaskMutationTransaction transactions;
    private final ControlledImageProviderOperatorPolicy policies;
    private final ObjectMapper json;

    @Inject
    public AgentTaskProviderCostConsentServiceImpl(AgentTaskProviderCostConsentDao consents,
            AgentTaskExecutionGrantService grants, AgentTaskMutationTransaction transactions,
            ControlledImageProviderOperatorPolicy policies, ObjectMapper json) {
        this.consents = Objects.requireNonNull(consents, "consents");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Result issue(Scope scope, String taskId, String idempotencyKey,
            AgentTaskProviderCostConsentIssueDTO request) {
        validateScope(scope); exact(taskId, "taskId", 100);
        exact(idempotencyKey, "Idempotency-Key", 100);
        ValidIssue valid = validateIssue(request);
        String requestDigest = requestDigest(taskId, valid);
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), taskId, root -> {
                        AgentTaskProviderCostConsentEntity replay =
                                consents.findByIdempotencyKeyForUpdate(scope.tenantId(),
                                        scope.clientId(), scope.ownerJiacn(), taskId,
                                        idempotencyKey);
                        if (replay != null) {
                            validatePersistedScope(replay, scope, taskId);
                            if (!constantTimeEquals(requestDigest, replay.getRequestDigest())) {
                                throw failure(Reason.CONFLICT);
                            }
                            return new Result(view(replay, System.currentTimeMillis()), true);
                        }
                        AgentTaskExecutionGrantService.AssignmentPreview preview =
                                grants.previewAssignmentWithinLockedTask(grantScope(scope), taskId,
                                        root.getTaskVersion(), valid.assignmentIdempotencyKey(),
                                        valid.assignment());
                        requirePreview(preview);
                        long now = System.currentTimeMillis();
                        ControlledImageProviderOperatorPolicy.Policy policy = policies.requireCurrent(
                                scope, preview.targetAgentId(), valid.bindingId(),
                                valid.bindingEpoch(), now);
                        AgentTaskProviderCostConsentEntity entity = entity(scope, taskId,
                                idempotencyKey, requestDigest, valid, preview, policy, now);
                        consents.insert(entity);
                        return new Result(view(entity, now), false);
                    });
        } catch (Failure failure) {
            throw failure;
        } catch (ControlledImageProviderOperatorPolicy.PolicyFailure failure) {
            throw translate(failure);
        } catch (AgentTaskExecutionGrantException failure) {
            throw translate(failure);
        } catch (AgentTaskCollaborationException failure) {
            throw translate(failure);
        } catch (DataAccessException | TransactionException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE, failure);
        } catch (RuntimeException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE, failure);
        }
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AgentTaskProviderCostConsentDTO get(Scope scope, String taskId, String consentId) {
        validateScope(scope); exact(taskId, "taskId", 100); exact(consentId, "consentId", 100);
        try {
            AgentTaskProviderCostConsentEntity row = consents.findByConsent(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), taskId, consentId);
            if (row == null) throw failure(Reason.NOT_FOUND);
            validatePersistedScope(row, scope, taskId);
            return view(row, System.currentTimeMillis());
        } catch (Failure failure) { throw failure; }
        catch (DataAccessException | TransactionException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE, failure);
        } catch (RuntimeException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE, failure);
        }
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AgentTaskProviderCostConsentDTO getByIdempotencyKey(Scope scope, String taskId,
            String idempotencyKey) {
        validateScope(scope); exact(taskId, "taskId", 100);
        exact(idempotencyKey, "Idempotency-Key", 100);
        try {
            // Dedicated non-locking query: original-key GET never creates or advances state.
            AgentTaskProviderCostConsentEntity row = consents.findByIdempotencyKey(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                    idempotencyKey);
            if (row == null) throw failure(Reason.NOT_FOUND);
            validatePersistedScope(row, scope, taskId);
            return view(row, System.currentTimeMillis());
        } catch (Failure failure) { throw failure; }
        catch (DataAccessException | TransactionException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE, failure);
        } catch (RuntimeException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE, failure);
        }
    }

    @Override
    public AgentTaskProviderCostConsentDTO revoke(Scope scope, String taskId, String consentId,
            String idempotencyKey, long expectedVersion) {
        validateScope(scope); exact(taskId,"taskId",100); exact(consentId,"consentId",100);
        exact(idempotencyKey,"Idempotency-Key",100); version(expectedVersion);
        String digest = sha256("PROVIDER_COST_CONSENT_REVOKE_V1\n" + taskId + "\n"
                + consentId + "\n" + expectedVersion);
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),
                    scope.clientId(),scope.ownerJiacn(),taskId,root -> {
                        AgentTaskProviderCostConsentEntity row = locked(scope,taskId,consentId);
                        if ("REVOKED".equals(row.getState())) {
                            if (constantTimeEquals(idempotencyKey,row.getRevokeIdempotencyKey())
                                    && constantTimeEquals(digest,row.getRevokeRequestDigest())) {
                                return view(row,System.currentTimeMillis());
                            }
                            throw failure(Reason.CONFLICT);
                        }
                        if ("CONSUMED".equals(row.getState())
                                || !Objects.equals(expectedVersion,row.getVersion())) {
                            throw failure(Reason.CONFLICT);
                        }
                        long now=System.currentTimeMillis();
                        row.setRevokeIdempotencyKey(idempotencyKey).setRevokeRequestDigest(digest)
                                .setRevokedAt(now); row.setUpdateTime(now);
                        if (!consents.revoke(row,expectedVersion)) throw failure(Reason.CONFLICT);
                        row.setState("REVOKED").setVersion(expectedVersion+1);
                        return view(row,now);
                    });
        } catch (Failure failure) { throw failure; }
        catch (AgentTaskCollaborationException failure) { throw translate(failure); }
        catch (DataAccessException | TransactionException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE,failure);
        } catch (RuntimeException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE,failure);
        }
    }

    @Override
    public AgentTaskProviderCostConsentDTO bind(Scope scope,String taskId,String consentId,
            BindCommand command) {
        requireBindCommand(command); return transition(scope,taskId,consentId,"ISSUED",command.expectedVersion(),
                command.assignmentBaseHash(),command.inputSnapshotDigest(),command.grantId(),
                command.grantVersion(),command.assignmentRevision(),null,null,null,"BOUND");
    }
    @Override
    public AgentTaskProviderCostConsentDTO reserve(Scope scope,String taskId,String consentId,
            ReserveCommand command) {
        requireReserveCommand(command); return transition(scope,taskId,consentId,"BOUND",command.expectedVersion(),
                command.assignmentBaseHash(),command.inputSnapshotDigest(),command.grantId(),
                command.grantVersion(),command.assignmentRevision(),command.executionId(),
                command.runId(),null,"RESERVED");
    }
    @Override
    public AgentTaskProviderCostConsentDTO consume(Scope scope,String taskId,String consentId,
            ConsumeCommand command) {
        requireConsumeCommand(command); return transition(scope,taskId,consentId,"RESERVED",command.expectedVersion(),
                command.assignmentBaseHash(),command.inputSnapshotDigest(),command.grantId(),
                command.grantVersion(),command.assignmentRevision(),command.executionId(),
                command.runId(),command.leaseId(),"CONSUMED");
    }

    private AgentTaskProviderCostConsentDTO transition(Scope scope,String taskId,String consentId,
            String from,long expectedVersion,String assignmentHash,String inputDigest,String grantId,
            long grantVersion,long assignmentRevision,String executionId,String runId,String leaseId,
            String to) {
        validateScope(scope); exact(taskId,"taskId",100); exact(consentId,"consentId",100);
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,root -> {
                        AgentTaskProviderCostConsentEntity row=locked(scope,taskId,consentId);
                        if (to.equals(row.getState()) && sameTransition(row,to,grantId,grantVersion,
                                assignmentRevision,executionId,runId,leaseId)) {
                            return view(row,System.currentTimeMillis());
                        }
                        if (!from.equals(row.getState()) || !Objects.equals(expectedVersion,row.getVersion())
                                || !constantTimeEquals(assignmentHash,row.getAssignmentBaseHash())
                                || !constantTimeEquals(inputDigest,row.getInputSnapshotDigest())) {
                            throw failure(Reason.CONFLICT);
                        }
                        long now=System.currentTimeMillis();
                        policies.requireCurrent(scope,row.getTargetAgentId(),row.getBindingId(),
                                row.getBindingEpoch(),now);
                        AgentTaskExecutionGrantService.Admission admitted =
                                grants.admitProviderConsentBinding(grantScope(scope),taskId,grantId,
                                        grantVersion,assignmentRevision,row.getTargetAgentId(),
                                        assignmentHash,inputDigest);
                        if (admitted == null || !Objects.equals(grantId,admitted.grantId())
                                || grantVersion!=admitted.grantVersion()
                                || assignmentRevision!=admitted.assignmentRevision()
                                || !"GENERATE_IMAGE".equals(admitted.operation())) {
                            throw failure(Reason.CONFLICT);
                        }
                        row.setBoundGrantId(grantId).setBoundGrantVersion(grantVersion)
                                .setBoundAssignmentRevision(assignmentRevision);
                        if (executionId!=null) row.setReservedExecutionId(executionId)
                                .setReservedRunId(runId);
                        if (leaseId!=null) row.setConsumedLeaseId(leaseId).setConsumedAt(now);
                        row.setUpdateTime(now);
                        boolean changed=switch(to) {
                            case "BOUND" -> consents.bind(row,expectedVersion);
                            case "RESERVED" -> consents.reserve(row,expectedVersion);
                            case "CONSUMED" -> consents.consume(row,expectedVersion);
                            default -> false;
                        };
                        if (!changed) throw failure(Reason.CONFLICT);
                        row.setState(to).setVersion(expectedVersion+1);
                        return view(row,now);
                    });
        } catch (Failure failure) { throw failure; }
        catch (ControlledImageProviderOperatorPolicy.PolicyFailure failure) { throw translate(failure); }
        catch (AgentTaskExecutionGrantException failure) { throw translate(failure); }
        catch (AgentTaskCollaborationException failure) { throw translate(failure); }
        catch (DataAccessException | TransactionException failure) {
            throw failure(Reason.SOURCE_UNAVAILABLE,failure);
        } catch (RuntimeException failure) { throw failure(Reason.SOURCE_UNAVAILABLE,failure); }
    }

    AgentTaskProviderCostConsentEntity lockForBridge(Scope scope,String taskId,String consentId) {
        validateScope(scope);exact(taskId,"taskId",100);exact(consentId,"consentId",100);
        return locked(scope,taskId,consentId);
    }

    AgentTaskProviderCostConsentDTO bindWithinLockedRoot(Scope scope,String taskId,
            AgentTaskProviderCostConsentEntity row,long expectedVersion,
            AgentTaskExecutionGrantEntity grant,ControlledImageGrantAuthority authority) {
        if (row==null || grant==null || authority==null || !"ISSUED".equals(row.getState())
                || !Objects.equals(expectedVersion,row.getVersion())
                || !Objects.equals(row.getAssignmentBaseHash(),grant.getRequestHash())
                || !Objects.equals(row.getTargetAgentId(),grant.getTargetAgentId()))
            throw failure(Reason.CONFLICT);
        long now=System.currentTimeMillis();
        authority.requireBindable(grantScope(scope),grant,row);
        row.setBoundGrantId(grant.getGrantId()).setBoundGrantVersion(grant.getGrantVersion())
                .setBoundAssignmentRevision(grant.getAssignmentRevision()).setUpdateTime(now);
        if (!consents.bind(row,expectedVersion)) throw failure(Reason.CONFLICT);
        row.setState("BOUND").setVersion(expectedVersion+1);
        authority.bindLocator(grantScope(scope),grant,row);
        return view(row,now);
    }

    AgentTaskProviderCostConsentDTO reserveWithinLockedRoot(Scope scope,String taskId,
            AgentTaskProviderCostConsentEntity row,long expectedVersion,String executionId,String runId) {
        if (row==null || !"BOUND".equals(row.getState()) || !Objects.equals(expectedVersion,row.getVersion()))
            throw failure(Reason.CONFLICT);
        exact(executionId,"executionId",100);exact(runId,"runId",100);
        long now=System.currentTimeMillis();row.setReservedExecutionId(executionId).setReservedRunId(runId).setUpdateTime(now);
        if (!consents.reserve(row,expectedVersion)) throw failure(Reason.CONFLICT);
        row.setState("RESERVED").setVersion(expectedVersion+1);return view(row,now);
    }

    AgentTaskProviderCostConsentDTO consumeWithinLockedRoot(Scope scope,String taskId,
            AgentTaskProviderCostConsentEntity row,long expectedVersion,String executionId,String runId,String leaseId) {
        if (row==null || !"RESERVED".equals(row.getState()) || !Objects.equals(expectedVersion,row.getVersion())
                || !Objects.equals(executionId,row.getReservedExecutionId())
                || !Objects.equals(runId,row.getReservedRunId())) throw failure(Reason.CONFLICT);
        exact(leaseId,"leaseId",100);long now=System.currentTimeMillis();
        row.setConsumedLeaseId(leaseId).setConsumedAt(now).setUpdateTime(now);
        if (!consents.consume(row,expectedVersion)) throw failure(Reason.CONFLICT);
        row.setState("CONSUMED").setVersion(expectedVersion+1);return view(row,now);
    }

    private AgentTaskProviderCostConsentEntity locked(Scope scope,String taskId,String consentId) {
        AgentTaskProviderCostConsentEntity row=consents.findByConsentForUpdate(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),taskId,consentId);
        if (row==null) throw failure(Reason.NOT_FOUND);
        validatePersistedScope(row,scope,taskId); return row;
    }

    private AgentTaskProviderCostConsentEntity entity(Scope scope,String taskId,String key,
            String digest,ValidIssue valid,AgentTaskExecutionGrantService.AssignmentPreview preview,
            ControlledImageProviderOperatorPolicy.Policy policy,long now) {
        AgentTaskProviderCostConsentEntity row=new AgentTaskProviderCostConsentEntity()
                .setConsentId("consent_"+UUID.randomUUID().toString().replace("-",""));
        row.setTenantId(scope.tenantId()); row.setClientId(scope.clientId());
        row.setOwnerJiacn(scope.ownerJiacn()).setTaskId(taskId)
                .setTargetAgentId(preview.targetAgentId()).setIdempotencyKey(key)
                .setRequestDigest(digest).setAssignmentIdempotencyKey(valid.assignmentIdempotencyKey())
                .setAssignmentBaseHash(preview.assignmentBaseHash()).setTaskVersion(preview.taskVersion())
                .setRequirementRevision(preview.requirementRevision())
                .setRequirementSha256(preview.requirementSha256())
                .setInputSnapshotDigest(preview.inputSnapshotDigest())
                .setInputSnapshotJson(write(preview.inputs())).setProviderLane(policy.providerLane())
                .setBindingId(policy.bindingId()).setBindingEpoch(policy.bindingEpoch())
                .setModelId(policy.modelId()).setCustody(policy.custody())
                .setOperatorIssuer(policy.issuer()).setOperatorPolicyRevision(policy.policyRevision())
                .setPricingMode(policy.pricingMode())
                .setMaxOutboundRequestAttempts(policy.maxOutboundRequestAttempts())
                .setExpiresAt(policy.expiresAt()).setState("ISSUED").setVersion(1L)
                .setCreatedAt(now);
        return row;
    }

    AgentTaskProviderCostConsentDTO view(AgentTaskProviderCostConsentEntity row,long now) {
        validatePersisted(row);
        String state = now >= row.getExpiresAt() && !"CONSUMED".equals(row.getState())
                && !"REVOKED".equals(row.getState()) ? "EXPIRED" : row.getState();
        return new AgentTaskProviderCostConsentDTO(1,row.getConsentId(),row.getTaskId(),
                row.getTargetAgentId(),state,Long.toString(row.getVersion()),
                row.getAssignmentIdempotencyKey(),row.getAssignmentBaseHash(),
                row.getInputSnapshotDigest(),new AgentTaskProviderCostConsentDTO.ProviderBinding(
                        row.getBindingId(),Long.toString(row.getBindingEpoch())),row.getModelId(),
                row.getCustody(),row.getOperatorPolicyRevision(),row.getPricingMode(),
                row.getMaxOutboundRequestAttempts(),Long.toString(row.getExpiresAt()));
    }

    private ValidIssue validateIssue(AgentTaskProviderCostConsentIssueDTO request) {
        if (request==null || !Objects.equals(1,request.getSchemaVersion())
                || !ACKNOWLEDGEMENT.equals(request.getAcknowledgement())
                || request.getProviderBinding()==null) throw failure(Reason.BAD_REQUEST);
        exact(request.getAssignmentIdempotencyKey(),"assignmentIdempotencyKey",100);
        exact(request.getProviderBinding().bindingId(),"bindingId",100);
        long epoch=canonicalPositiveLong(request.getProviderBinding().bindingEpoch());
        AgentTaskAssignDTO assignment=request.getAssignment();
        if (assignment==null || !Objects.equals(2,assignment.getWorkflowVersion())
                || !"assign_and_start".equals(assignment.getBusinessAction())
                || assignment.getExpectedTaskVersion()==null
                || assignment.getExpectedTaskVersion()<0
                || assignment.getExpectedTaskVersion()>MAX_SAFE_INTEGER
                || assignment.getRequirementRevision()==null
                || assignment.getRequirementRevision()<1
                || assignment.getRequirementRevision()>MAX_SAFE_INTEGER
                || !List.of("GENERATE_IMAGE").equals(assignment.getRequestedOperations())
                || !"GENERATE_IMAGE".equals(assignment.getInitialOperation())
                || assignment.getInputRefs()==null || assignment.getInputRefs().size()>16
                || assignment.getAgentIds()!=null || assignment.getAllowQueue()!=null
                || assignment.getExistingCostAuthorizationRef()!=null
                || assignment.getCostAuthorizationRef()!=null
                || assignment.getPermittedToolPolicyRef()!=null || assignment.getTools()!=null
                || assignment.getAuthorized()!=null
                || assignment.getPaidExecutionAuthorized()!=null) {
            throw failure(Reason.BAD_REQUEST);
        }
        exact(assignment.getAgentId(),"agentId",100);
        for (AgentTaskGrantInputDTO input:assignment.getInputRefs()) {
            if (input==null) throw failure(Reason.BAD_REQUEST);
            exact(input.getFileId(),"fileId",100);
            if (input.getVersion()==null || input.getVersion()<1
                    || !("INPUT".equals(input.getPurpose())
                    || "REFERENCE".equals(input.getPurpose()))) throw failure(Reason.BAD_REQUEST);
        }
        return new ValidIssue(request.getAssignmentIdempotencyKey(),assignment,
                request.getProviderBinding().bindingId(),epoch,request.getAcknowledgement());
    }

    private String requestDigest(String taskId,ValidIssue valid) {
        ObjectNode root=json.createObjectNode(); root.put("schemaVersion",1);
        root.put("taskId",taskId); root.put("assignmentIdempotencyKey",valid.assignmentIdempotencyKey());
        AgentTaskAssignDTO a=valid.assignment(); ObjectNode assignment=root.putObject("assignment");
        assignment.put("workflowVersion",a.getWorkflowVersion());
        assignment.put("businessAction",a.getBusinessAction());
        assignment.put("expectedTaskVersion",a.getExpectedTaskVersion());
        assignment.put("requirementRevision",a.getRequirementRevision());
        assignment.put("agentId",a.getAgentId());
        ArrayNode operations=assignment.putArray("requestedOperations");
        a.getRequestedOperations().forEach(operations::add);
        assignment.put("initialOperation",a.getInitialOperation());
        ArrayNode inputs=assignment.putArray("inputRefs");
        for (AgentTaskGrantInputDTO input:a.getInputRefs()) {
            ObjectNode item=inputs.addObject(); item.put("fileId",input.getFileId());
            item.put("version",input.getVersion()); item.put("purpose",input.getPurpose());
        }
        ObjectNode binding=root.putObject("providerBinding");binding.put("bindingId",valid.bindingId());
        binding.put("bindingEpoch",Long.toString(valid.bindingEpoch()));
        root.put("acknowledgement",valid.acknowledgement());
        return sha256("PROVIDER_COST_CONSENT_ISSUE_V1\n"+write(root));
    }

    private void requirePreview(AgentTaskExecutionGrantService.AssignmentPreview preview) {
        if (preview==null || !valid(preview.targetAgentId(),100)
                || preview.taskVersion()<0 || preview.taskVersion()>MAX_SAFE_INTEGER
                || preview.requirementRevision()<1 || preview.requirementRevision()>MAX_SAFE_INTEGER
                || !hash(preview.requirementSha256()) || !hash(preview.assignmentBaseHash())
                || !hash(preview.inputSnapshotDigest()) || preview.inputs()==null
                || preview.inputs().size()>16) throw failure(Reason.SOURCE_UNAVAILABLE);
        for (AgentTaskExecutionGrantService.AuthorizedInput input:preview.inputs()) {
            if (input==null || !IMAGE_MIMES.contains(input.contentMimeType())
                    || input.byteLength()<0 || !hash(input.contentHash())) {
                throw failure(Reason.BAD_REQUEST);
            }
        }
    }

    private void validatePersistedScope(AgentTaskProviderCostConsentEntity row,Scope scope,
            String taskId) {
        if (!Objects.equals(scope.tenantId(),row.getTenantId())
                || !Objects.equals(scope.clientId(),row.getClientId())
                || !Objects.equals(scope.ownerJiacn(),row.getOwnerJiacn())
                || !Objects.equals(taskId,row.getTaskId())) throw failure(Reason.NOT_FOUND);
    }
    private void validatePersisted(AgentTaskProviderCostConsentEntity row) {
        if (row==null || !valid(row.getConsentId(),100) || !valid(row.getTaskId(),100)
                || !valid(row.getTargetAgentId(),100) || !valid(row.getAssignmentIdempotencyKey(),100)
                || !hash(row.getAssignmentBaseHash()) || !hash(row.getInputSnapshotDigest())
                || row.getBindingEpoch()==null || row.getBindingEpoch()<1
                || row.getVersion()==null || row.getVersion()<1 || row.getExpiresAt()==null
                || row.getExpiresAt()<1 || !ControlledImageProviderOperatorPolicy.PROVIDER_LANE.equals(row.getProviderLane())
                || !ControlledImageProviderOperatorPolicy.PRICING_MODE.equals(row.getPricingMode())
                || !Objects.equals(1,row.getMaxOutboundRequestAttempts())
                || !List.of("ISSUED","BOUND","RESERVED","CONSUMED","REVOKED").contains(row.getState())) {
            throw failure(Reason.SOURCE_UNAVAILABLE);
        }
        try {
            List<AgentTaskExecutionGrantService.AuthorizedInput> inputs=json.readValue(
                    row.getInputSnapshotJson(),new TypeReference<List<AgentTaskExecutionGrantService.AuthorizedInput>>(){});
            if (inputs==null || inputs.size()>16) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw failure(Reason.SOURCE_UNAVAILABLE,invalid); }
    }

    private boolean sameTransition(AgentTaskProviderCostConsentEntity row,String state,String grantId,
            long grantVersion,long assignmentRevision,String executionId,String runId,String leaseId) {
        if (!Objects.equals(grantId,row.getBoundGrantId())
                || !Objects.equals(grantVersion,row.getBoundGrantVersion())
                || !Objects.equals(assignmentRevision,row.getBoundAssignmentRevision())) return false;
        if ("BOUND".equals(state)) return true;
        if (!Objects.equals(executionId,row.getReservedExecutionId())
                || !Objects.equals(runId,row.getReservedRunId())) return false;
        return !"CONSUMED".equals(state) || Objects.equals(leaseId,row.getConsumedLeaseId());
    }

    private static void requireBindCommand(BindCommand value) {
        if (value==null) throw failure(Reason.BAD_REQUEST);
        transitionFields(value.expectedVersion(),value.assignmentBaseHash(),value.inputSnapshotDigest(),
                value.grantId(),value.grantVersion(),value.assignmentRevision());
    }
    private static void requireReserveCommand(ReserveCommand value) {
        if (value==null) throw failure(Reason.BAD_REQUEST);
        transitionFields(value.expectedVersion(),value.assignmentBaseHash(),value.inputSnapshotDigest(),
                value.grantId(),value.grantVersion(),value.assignmentRevision());
        exact(value.executionId(),"executionId",100); exact(value.runId(),"runId",100);
    }
    private static void requireConsumeCommand(ConsumeCommand value) {
        if (value==null) throw failure(Reason.BAD_REQUEST);
        transitionFields(value.expectedVersion(),value.assignmentBaseHash(),value.inputSnapshotDigest(),
                value.grantId(),value.grantVersion(),value.assignmentRevision());
        exact(value.executionId(),"executionId",100); exact(value.runId(),"runId",100);
        exact(value.leaseId(),"leaseId",100);
    }
    private static void transitionFields(long expected,String assignment,String input,String grant,
            long grantVersion,long assignmentRevision) {
        version(expected); if (!hash(assignment)||!hash(input)) throw failure(Reason.BAD_REQUEST);
        exact(grant,"grantId",100); version(grantVersion);
        if (assignmentRevision<0 || assignmentRevision>MAX_SAFE_INTEGER) throw failure(Reason.BAD_REQUEST);
    }
    private static AgentTaskExecutionGrantService.Scope grantScope(Scope scope) {
        return new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
    }
    private static void validateScope(Scope scope) {
        if (scope==null || !"0".equals(scope.tenantId()) || !valid(scope.clientId(),50)
                || !valid(scope.ownerJiacn(),50) || "0".equals(scope.ownerJiacn())) {
            throw failure(Reason.FORBIDDEN);
        }
    }
    private static long canonicalPositiveLong(String value) {
        if (value==null || !value.matches("[1-9][0-9]*")) throw failure(Reason.BAD_REQUEST);
        try { long parsed=Long.parseLong(value); if (parsed<1) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException failure) { throw failure(Reason.BAD_REQUEST); }
    }
    private static void version(long value) {
        if (value<1 || value>MAX_SAFE_INTEGER) throw failure(Reason.BAD_REQUEST);
    }
    private static void exact(String value,String name,int max) {
        if (!valid(value,max)) throw failure(Reason.BAD_REQUEST);
    }
    private static boolean valid(String value,int max) {
        return value!=null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0,value.length())<=max
                && !hasUnpairedSurrogate(value)
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static boolean hasUnpairedSurrogate(String value) {
        for (int i=0;i<value.length();i++) { char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) return true; }
            else if (Character.isLowSurrogate(c)) return true;
        } return false;
    }
    private static boolean hash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw failure(Reason.SOURCE_UNAVAILABLE,failure); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean constantTimeEquals(String left,String right) {
        return left!=null && right!=null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));
    }
    private static Failure translate(ControlledImageProviderOperatorPolicy.PolicyFailure failure) {
        return switch(failure.reason()) {
            case FORBIDDEN -> failure(Reason.FORBIDDEN,failure);
            case CONFLICT -> failure(Reason.CONFLICT,failure);
            case SOURCE_UNAVAILABLE -> failure(Reason.SOURCE_UNAVAILABLE,failure);
        };
    }
    private static Failure translate(AgentTaskExecutionGrantException failure) {
        return switch(failure.reason()) {
            case BAD_REQUEST -> failure(Reason.BAD_REQUEST,failure);
            case NOT_FOUND -> failure(Reason.NOT_FOUND,failure);
            case CONFLICT, IDEMPOTENCY_CONFLICT, FORBIDDEN_OPERATION,
                    PAID_EXECUTION_NOT_AUTHORIZED -> failure(Reason.CONFLICT,failure);
            case UNAUTHENTICATED -> failure(Reason.FORBIDDEN,failure);
            case INVALID_PERSISTED_STATE -> failure(Reason.SOURCE_UNAVAILABLE,failure);
        };
    }
    private static Failure translate(AgentTaskCollaborationException failure) {
        return switch(failure.getReason()) {
            case NOT_FOUND,FORBIDDEN -> failure(Reason.NOT_FOUND,failure);
            case VERSION_CONFLICT,INVALID_TRANSITION,RESERVED_FOR_LEASE_PROTOCOL -> failure(Reason.CONFLICT,failure);
            case INVALID_REQUEST -> failure(Reason.BAD_REQUEST,failure);
            case INVALID_PERSISTED_STATE -> failure(Reason.SOURCE_UNAVAILABLE,failure);
        };
    }
    private static Failure failure(Reason reason) { return new Failure(reason); }
    private static Failure failure(Reason reason,Throwable cause) { return new Failure(reason,cause); }
    private record ValidIssue(String assignmentIdempotencyKey,AgentTaskAssignDTO assignment,
            String bindingId,long bindingEpoch,String acknowledgement) { }
}
