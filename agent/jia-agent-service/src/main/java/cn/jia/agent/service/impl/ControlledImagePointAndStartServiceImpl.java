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
                ||!Objects.equals(3,operation.getExecutionProtocolVersion())
                ||operation.getOperationGrantId()==null
                ||!operation.getOperationGrantId().matches("opgrant_[0-9a-f]{32}")
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
    private static void validateScope(AgentTaskExecutionGrantService.Scope s){if(s==null||!"0".equals(s.tenantId())||"0".equals(s.ownerJiacn()))throw failure(Reason.NOT_FOUND);id(s.clientId(),50);id(s.ownerJiacn(),50);}
    private static void id(String v,int max){if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}"))throw failure(Reason.BAD_REQUEST);}
    private static void key(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,99}"))throw failure(Reason.BAD_REQUEST);}
    private static Failure failure(Reason r){return new Failure(r);}private static Failure failure(Reason r,Throwable c){return new Failure(r,c);}
}
