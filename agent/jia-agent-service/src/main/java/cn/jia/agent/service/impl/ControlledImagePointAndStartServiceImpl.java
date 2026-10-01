package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.dao.ControlledImageBridgeOperationDao;
import cn.jia.agent.entity.AgentTaskExecutionGrantDTO;
import cn.jia.agent.entity.AgentTaskProviderCostConsentDTO;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
import cn.jia.agent.entity.ControlledImagePointAndStartDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import cn.jia.agent.service.ControlledImagePointAndStartService;
import jakarta.inject.Named;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Named
public class ControlledImagePointAndStartServiceImpl implements ControlledImagePointAndStartService {
    private static final long MAX_SAFE=9_007_199_254_740_991L;
    private final ControlledImageBridgeOperationDao operations;
    private final AgentTaskExecutionGrantDao grantRows;
    private final AgentTaskProviderCostConsentDao consentRows;
    private final AgentTaskExecutionGrantServiceImpl grants;
    private final AgentTaskProviderCostConsentServiceImpl consents;
    private final ControlledImageGrantAuthority authority;
    private final AgentTaskMutationTransaction transactions;
    private final ObjectMapper json;

    public ControlledImagePointAndStartServiceImpl(ControlledImageBridgeOperationDao operations,
            AgentTaskExecutionGrantDao grantRows,AgentTaskProviderCostConsentDao consentRows,
            AgentTaskExecutionGrantServiceImpl grants,AgentTaskProviderCostConsentServiceImpl consents,
            ControlledImageGrantAuthority authority,AgentTaskMutationTransaction transactions,ObjectMapper json) {
        this.operations=Objects.requireNonNull(operations);this.grantRows=Objects.requireNonNull(grantRows);
        this.consentRows=Objects.requireNonNull(consentRows);this.grants=Objects.requireNonNull(grants);
        this.consents=Objects.requireNonNull(consents);this.authority=Objects.requireNonNull(authority);
        this.transactions=Objects.requireNonNull(transactions);this.json=Objects.requireNonNull(json);
    }

    @Override
    public Result submit(AgentTaskExecutionGrantService.Scope scope,String taskId,String key,
            ControlledImagePointAndStartDTO.Request request) {
        validateScope(scope);id(taskId,100);key(key);Valid valid=validate(request);
        String digest=digest(request);
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,root -> {
                var replay=operations.lock(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,key);
                if (replay!=null) {
                    if (!same(digest,replay.getWrapperDigest())) throw failure(Reason.CONFLICT);
                    return new Result(receipt(scope,taskId,replay),true);
                }
                AgentTaskExecutionGrantDTO grantView=grants.assignControlledWithinLockedTask(scope,taskId,key,
                        request.assignment(),root);
                var grant=authority.lockGrant(scope,taskId,grantView.getGrantId());
                var consentScope=consentScope(scope);
                var consent=consents.lockForBridge(consentScope,taskId,valid.consentId());
                if (!"ISSUED".equals(consent.getState()) || consent.getVersion()!=valid.expectedVersion()
                        || !Objects.equals(key,consent.getAssignmentIdempotencyKey())
                        || !Objects.equals(grant.getRequestHash(),consent.getAssignmentBaseHash())
                        || !Objects.equals(grant.getTargetAgentId(),consent.getTargetAgentId()))
                    throw failure(Reason.CONFLICT);
                grants.requireControlledBindingSnapshot(grant,consent.getRequirementRevision(),
                        consent.getInputSnapshotDigest());
                AgentTaskProviderCostConsentDTO consentView=consents.bindWithinLockedRoot(consentScope,taskId,
                        consent,valid.expectedVersion(),grant,authority);
                var row=new ControlledImageBridgeOperationEntity();row.setTenantId(scope.tenantId());
                row.setClientId(scope.clientId());row.setOwnerJiacn(scope.ownerJiacn()).setTaskId(taskId)
                        .setAssignmentIdempotencyKey(key).setWrapperDigest(digest)
                        .setConsentId(valid.consentId()).setExpectedConsentVersion(valid.expectedVersion())
                        .setGrantId(grant.getGrantId()).setGrantVersion(grant.getGrantVersion())
                        .setAssignmentRevision(grant.getAssignmentRevision())
                        .setAuthorityLocator(ControlledImageGrantAuthority.LOCATOR_PREFIX+valid.consentId())
                        .setCreatedAt(System.currentTimeMillis());operations.insert(row);
                return new Result(new ControlledImagePointAndStartDTO.Receipt(1,grants.view(grant),consentView),false);
            });
        } catch (Failure f){throw f;} catch (AgentTaskExecutionGrantException f){throw translate(f);}
        catch (AgentTaskProviderCostConsentService.Failure f){throw translate(f);}
        catch (org.springframework.dao.DuplicateKeyException f){throw failure(Reason.CONFLICT,f);}
        catch (DataAccessException|TransactionException f){throw failure(Reason.SOURCE_UNAVAILABLE,f);}
        catch (RuntimeException f){throw failure(Reason.SOURCE_UNAVAILABLE,f);}
    }

    @Override @Transactional(readOnly=true,rollbackFor=Exception.class)
    public ControlledImagePointAndStartDTO.Receipt get(AgentTaskExecutionGrantService.Scope scope,
            String taskId,String key) {
        validateScope(scope);id(taskId,100);key(key);
        try {
            var row=operations.find(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,key);
            if(row==null)throw failure(Reason.NOT_FOUND);return receipt(scope,taskId,row);
        } catch(Failure f){throw f;}catch(RuntimeException f){throw failure(Reason.SOURCE_UNAVAILABLE,f);}
    }

    private ControlledImagePointAndStartDTO.Receipt receipt(AgentTaskExecutionGrantService.Scope scope,
            String taskId,ControlledImageBridgeOperationEntity operation) {
        var grant=grantRows.findByGrant(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,operation.getGrantId());
        var consent=consentRows.findByConsent(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,operation.getConsentId());
        if(grant==null||consent==null
                ||!Objects.equals(operation.getGrantId(),grant.getGrantId())
                ||grant.getGrantVersion()==null
                ||grant.getGrantVersion()<operation.getGrantVersion()
                ||!Objects.equals(operation.getAssignmentRevision(),grant.getAssignmentRevision())
                ||!Objects.equals(operation.getConsentId(),consent.getConsentId())
                ||!Objects.equals(operation.getAuthorityLocator(),grant.getCostAuthorizationRef())
                ||consent.getVersion()==null||consent.getVersion()<operation.getExpectedConsentVersion()+1
                ||!Objects.equals(operation.getGrantId(),consent.getBoundGrantId())
                ||!Objects.equals(operation.getGrantVersion(),consent.getBoundGrantVersion())
                ||!Objects.equals(operation.getAssignmentRevision(),consent.getBoundAssignmentRevision())
                ||!Objects.equals(grant.getRequestHash(),consent.getAssignmentBaseHash())
                ||!Objects.equals(grant.getTargetAgentId(),consent.getTargetAgentId()))
            throw failure(Reason.SOURCE_UNAVAILABLE);
        return new ControlledImagePointAndStartDTO.Receipt(1,grants.view(grant),
                consents.view(consent,System.currentTimeMillis()));
    }
    private Valid validate(ControlledImagePointAndStartDTO.Request request) {
        if(request==null||!Objects.equals(1,request.schemaVersion())||request.assignment()==null
                ||request.providerConsent()==null||request.providerConsent().consentId()==null
                ||!request.providerConsent().consentId().matches("consent_[0-9a-f]{32}"))
            throw failure(Reason.BAD_REQUEST);
        var assignment=request.assignment();
        if(!Objects.equals(2,assignment.getWorkflowVersion())
                ||!Objects.equals("assign_and_start",assignment.getBusinessAction())
                ||assignment.getExpectedTaskVersion()==null||assignment.getExpectedTaskVersion()<1
                ||assignment.getExpectedTaskVersion()>MAX_SAFE
                ||assignment.getRequirementRevision()==null||assignment.getRequirementRevision()<1
                ||assignment.getRequirementRevision()>MAX_SAFE
                ||!Objects.equals(java.util.List.of("GENERATE_IMAGE"),assignment.getRequestedOperations())
                ||!Objects.equals("GENERATE_IMAGE",assignment.getInitialOperation())
                ||assignment.getInputRefs()==null||assignment.getInputRefs().size()>16)
            throw failure(Reason.BAD_REQUEST);
        long expected=parseVersion(request.providerConsent().expectedVersion());
        return new Valid(request.providerConsent().consentId(),expected);
    }
    private String digest(Object request){try{return sha("CONTROLLED_IMAGE_POINT_AND_START_WRAPPER_V1\n"+json.writeValueAsString(request));}catch(Exception e){throw failure(Reason.BAD_REQUEST,e);}}
    private static long parseVersion(String value){if(value==null||!value.matches("[1-9][0-9]*"))throw failure(Reason.BAD_REQUEST);try{long v=Long.parseLong(value);if(v>MAX_SAFE)throw failure(Reason.BAD_REQUEST);return v;}catch(NumberFormatException e){throw failure(Reason.BAD_REQUEST,e);}}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static boolean same(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static void validateScope(AgentTaskExecutionGrantService.Scope s){if(s==null||!"0".equals(s.tenantId())||"0".equals(s.ownerJiacn()))throw failure(Reason.NOT_FOUND);id(s.clientId(),50);id(s.ownerJiacn(),50);}
    private static void id(String v,int max){if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}"))throw failure(Reason.BAD_REQUEST);}
    private static void key(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,99}"))throw failure(Reason.BAD_REQUEST);}
    private static AgentTaskProviderCostConsentService.Scope consentScope(AgentTaskExecutionGrantService.Scope s){return new AgentTaskProviderCostConsentService.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
    private static Failure translate(AgentTaskExecutionGrantException f){return switch(f.reason()){case BAD_REQUEST->failure(Reason.BAD_REQUEST,f);case NOT_FOUND,UNAUTHENTICATED->failure(Reason.NOT_FOUND,f);case CONFLICT,IDEMPOTENCY_CONFLICT,FORBIDDEN_OPERATION,PAID_EXECUTION_NOT_AUTHORIZED->failure(Reason.CONFLICT,f);case INVALID_PERSISTED_STATE->failure(Reason.SOURCE_UNAVAILABLE,f);};}
    private static Failure translate(AgentTaskProviderCostConsentService.Failure f){return switch(f.reason()){case BAD_REQUEST->failure(Reason.BAD_REQUEST,f);case FORBIDDEN,NOT_FOUND->failure(Reason.NOT_FOUND,f);case CONFLICT->failure(Reason.CONFLICT,f);case SOURCE_UNAVAILABLE->failure(Reason.SOURCE_UNAVAILABLE,f);};}
    private static Failure failure(Reason r){return new Failure(r);}private static Failure failure(Reason r,Throwable c){return new Failure(r,c);}
    private record Valid(String consentId,long expectedVersion){}
}
