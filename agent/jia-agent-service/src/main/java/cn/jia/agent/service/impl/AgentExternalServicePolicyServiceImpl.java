package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static cn.jia.agent.service.AgentTaskExecutionGrantException.Reason.*;

@Named
public class AgentExternalServicePolicyServiceImpl implements AgentExternalServicePolicyService {
    private final AgentExternalServicePolicyDao policies;
    private final AgentIdentityService identities;
    private final AgentTaskMetaDao tasks;
    private final AgentTaskExecutionGrantDao grants;
    private final AgentRuntimeDao runtimes;
    @Inject public AgentExternalServicePolicyServiceImpl(AgentExternalServicePolicyDao policies,
            AgentIdentityService identities,AgentTaskMetaDao tasks,AgentTaskExecutionGrantDao grants,AgentRuntimeDao runtimes) {
        this.policies=policies;this.identities=identities;this.tasks=tasks;this.grants=grants;this.runtimes=runtimes;
    }
    public Policy read(AgentTaskExecutionGrantService.Scope scope,String agent) {
        var identity=owned(scope,agent);
        return view(agent,policies.latest(scope.tenantId(),scope.clientId(),agent,identity.getBindingId()));
    }
    @Transactional(rollbackFor=Exception.class)
    public Policy write(AgentTaskExecutionGrantService.Scope scope,String agent,String key,boolean enabled,long expected) {
        exact(key,100);if(expected<0 || expected==Long.MAX_VALUE) throw failure(BAD_REQUEST);
        var identity=owned(scope,agent);
        identities.lockActiveCanonicalAgentIdsInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),List.of(agent));
        String hash=digest(scope,agent,identity.getBindingId(),expected,enabled);
        var replay=policies.receiptForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),key);
        if(replay!=null) {
            if(!agent.equals(replay.getAgentId()) || !hash.equals(replay.getRequestHash())) throw failure(IDEMPOTENCY_CONFLICT);
            return view(agent,replay);
        }
        var current=policies.latestForUpdate(scope.tenantId(),scope.clientId(),agent,identity.getBindingId());
        long revision=current==null?0:current.getRevision();
        if(revision!=expected) throw failure(CONFLICT);
        var row=new AgentExternalServicePolicyEntity().setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setProviderOwnerJiacn(scope.ownerJiacn()).setAgentId(agent).setBindingId(identity.getBindingId())
                .setRevision(expected+1).setExpectedRevision(expected).setEnabled(enabled).setIdempotencyKey(key)
                .setRequestHash(hash).setCreatedAt(System.currentTimeMillis());
        policies.insert(row);return view(agent,row);
    }
    public Policy receipt(AgentTaskExecutionGrantService.Scope scope,String agent,String key) {
        exact(key,100);owned(scope,agent);
        var row=policies.receipt(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),key);
        if(row==null || !agent.equals(row.getAgentId())) throw failure(NOT_FOUND);
        return view(agent,row);
    }
    public Candidates candidates(AgentTaskExecutionGrantService.Scope scope,String task,String after,int size) {
        requireTask(scope,task);if(size<1||size>100) throw failure(BAD_REQUEST);
        if(after==null) after="";else if(!after.isEmpty()) exact(after,100);
        var rows=policies.candidates(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),after,size+1);
        boolean more=rows.size()>size;
        var page=rows.subList(0,Math.min(size,rows.size()));
        return new Candidates(page.stream().map(i->summary(scope,i)).toList(),more?page.getLast().getCanonicalAgentId():null);
    }
    public AgentTaskTargetSummaryDTO assignedTarget(AgentTaskExecutionGrantService.Scope scope,String task) {
        requireTask(scope,task);
        var grant=grants.findActiveByTask(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task);
        if(grant==null) return null;
        var identity=policies.identity(scope.tenantId(),scope.clientId(),grant.getTargetAgentId());
        if(identity==null || !Objects.equals(identity.getOwnerJiacn(),grant.getExecutorOwnerJiacn())
                || !Objects.equals(identity.getBindingId(),grant.getExecutorBindingId())) return null;
        return summary(scope,identity);
    }
    private AgentTaskTargetSummaryDTO summary(AgentTaskExecutionGrantService.Scope scope,AgentIdentityRegistryEntity identity) {
        var runtime=runtimes.findByAgentId(identity.getCanonicalAgentId());
        boolean valid=runtime!=null && Objects.equals(runtime.getClientId(),scope.clientId())
                && Objects.equals(runtime.getOwnerJiacn(),identity.getOwnerJiacn())
                && Objects.equals(runtime.getBindingId(),identity.getBindingId());
        // Public profile source is optional. Never expose the internal login as a nickname.
        return new AgentTaskTargetSummaryDTO(identity.getCanonicalAgentId(),valid?runtime.getName():identity.getCanonicalAgentId(),
                null,scope.ownerJiacn().equals(identity.getOwnerJiacn()),
                valid && "ACTIVE".equals(identity.getLifecycleStatus())?runtime.getStatus():"unavailable");
    }
    private AgentIdentityRegistryEntity owned(AgentTaskExecutionGrantService.Scope scope,String agent) {
        exact(agent,100);var i=policies.identity(scope.tenantId(),scope.clientId(),agent);
        if(i==null || !scope.ownerJiacn().equals(i.getOwnerJiacn()) || i.getBindingId()==null
                || !scope.tenantId().equals(i.getTenantId()) || !scope.clientId().equals(i.getClientId())) throw failure(NOT_FOUND);
        identities.requireCanonicalAgentIdInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),agent);
        return i;
    }
    private void requireTask(AgentTaskExecutionGrantService.Scope scope,String task) {
        exact(task,100);if(tasks.findByTaskIdInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),task)==null) throw failure(NOT_FOUND);
    }
    private static Policy view(String agent,AgentExternalServicePolicyEntity row) {
        return new Policy(agent,row!=null&&Boolean.TRUE.equals(row.getEnabled()),row==null?"0":row.getRevision().toString());
    }
    private static String digest(AgentTaskExecutionGrantService.Scope scope,String agent,long binding,long expected,boolean enabled) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            ("EXTERNAL_POLICY_V1\n"+scope.tenantId()+"\n"+scope.clientId()+"\n"+scope.ownerJiacn()+"\n"+agent+"\n"+binding+"\n"+expected+"\n"+enabled).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void exact(String v,int n) { if(v==null||v.isBlank()||!v.equals(v.strip())||v.length()>n||v.chars().anyMatch(Character::isISOControl)) throw failure(BAD_REQUEST); }
    private static AgentTaskExecutionGrantException failure(AgentTaskExecutionGrantException.Reason reason) {
        return new AgentTaskExecutionGrantException(reason,"Agent service policy operation was not accepted");
    }
}
