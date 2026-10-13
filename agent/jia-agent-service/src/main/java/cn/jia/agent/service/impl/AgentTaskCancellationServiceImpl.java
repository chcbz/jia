package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.dao.AgentTaskCancellationDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.service.AgentTaskCancellationService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static cn.jia.agent.exception.AgentTaskStateException.Reason.*;

/** Root-first atomic cancellation. Presence of current execution/transport/cost facts is unsupported,
 * not proof of failure/completion. No process, installation, file, or history is deleted. */
@Named
public class AgentTaskCancellationServiceImpl implements AgentTaskCancellationService {
    private final AgentTaskMutationTransaction transactions;
    private final AgentTaskStateService states;
    private final AgentTaskWorkItemDao workItems;
    private final AgentTaskExecutionGrantDao grants;
    private final AgentTaskCancellationDao cancellation;

    @Inject
    public AgentTaskCancellationServiceImpl(AgentTaskMutationTransaction transactions,
            AgentTaskStateService states, AgentTaskWorkItemDao workItems,
            AgentTaskExecutionGrantDao grants, AgentTaskCancellationDao cancellation) {
        this.transactions=Objects.requireNonNull(transactions);
        this.states=Objects.requireNonNull(states);
        this.workItems=Objects.requireNonNull(workItems);
        this.grants=Objects.requireNonNull(grants);
        this.cancellation=Objects.requireNonNull(cancellation);
    }

    @Override
    public Receipt cancel(String tenant, String client, String owner, String actor,
            String taskId, long expectedTaskVersion) {
        if (!"0".equals(tenant) || !exact(client,50) || "0".equals(client)
                || !exact(owner,50) || "0".equals(owner) || !exact(actor,100)
                || !exact(taskId,100) || expectedTaskVersion<0 || expectedTaskVersion==Long.MAX_VALUE) {
            throw error(INVALID_REQUEST);
        }
        // The REQUIRED root transaction, not an annotation/self-invocation, owns every write/event.
        return transactions.executeWithLockedTaskRootInOwnerScope(tenant,client,owner,taskId, root -> {
            if (root==null || !tenant.equals(root.getTenantId()) || !client.equals(root.getClientId())
                    || !owner.equals(root.getOwnerJiacn()) || !taskId.equals(root.getTaskId())
                    || root.getTaskVersion()==null || root.getTaskVersion()<0
                    || root.getCurrentEventVersion()==null || root.getCurrentEventVersion()<0) {
                throw error(INVALID_PERSISTED_STATE);
            }
            boolean replay="cancelled".equals(root.getRewardStatus());
            if (!replay && root.getTaskVersion()!=expectedTaskVersion) throw error(VERSION_CONFLICT);
            if (root.getReward()!=null && root.getReward()!=0
                    || cancellation.hasMoneyFacts(tenant,client,owner,taskId)) throw error(INVALID_TRANSITION);
            if ((!replay && !Set.of("open","planning","assigned").contains(
                    root.getRewardStatus()==null ? "" : root.getRewardStatus()))
                    || root.getStartedAt()!=null || root.getCompletedAt()!=null) throw error(INVALID_TRANSITION);

            List<AgentTaskMemberEntity> members=cancellation.lockMembers(tenant,client,owner,taskId);
            if (members==null) throw error(INVALID_PERSISTED_STATE);
            Set<String> memberIds=new HashSet<>();
            for (var member:members) {
                if (member==null || !scoped(member,tenant,client,owner,taskId)
                        || !exact(member.getAgentId(),100) || !memberIds.add(member.getAgentId())) {
                    throw error(INVALID_PERSISTED_STATE);
                }
                version(member.getVersion());
                AgentTaskMemberStatus status;
                try { status=AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus()); }
                catch (IllegalArgumentException failure) { throw error(INVALID_PERSISTED_STATE); }
                // LEFT/REJECTED completedAt is an exit clock written by the existing
                // state service, not work-start evidence. Preserve it on initial cancel/replay.
                if (member.getStartedAt()!=null
                        || !(status==AgentTaskMemberStatus.INVITED || status==AgentTaskMemberStatus.ACCEPTED
                            || status==AgentTaskMemberStatus.LEFT || status==AgentTaskMemberStatus.REJECTED)
                        || !status.isTerminal() && member.getCompletedAt()!=null
                        || replay && !status.isTerminal()) throw error(INVALID_TRANSITION);
            }
            List<AgentTaskWorkItemEntity> items=workItems.listByTaskForUpdate(
                    tenant,client,owner,taskId,AgentWorkItemDependencyServiceImpl.MAX_WORK_ITEMS+1);
            if (items==null) throw error(INVALID_PERSISTED_STATE);
            if (items.size()>AgentWorkItemDependencyServiceImpl.MAX_WORK_ITEMS) throw error(INVALID_PERSISTED_STATE);
            Set<String> itemIds=new HashSet<>();
            for (var item:items) {
                if (item==null || !scoped(item,tenant,client,owner,taskId)
                        || !exact(item.getWorkItemId(),100) || !itemIds.add(item.getWorkItemId())) {
                    throw error(INVALID_PERSISTED_STATE);
                }
                version(item.getVersion());
                AgentTaskWorkItemStatus status;
                try { status=AgentTaskWorkItemStatus.fromPersistedValue(item.getStatus()); }
                catch (IllegalArgumentException failure) { throw error(INVALID_PERSISTED_STATE); }
                if (!(status==AgentTaskWorkItemStatus.PENDING || status==AgentTaskWorkItemStatus.READY
                        || status==AgentTaskWorkItemStatus.CANCELLED) || item.getAttemptCount()==null
                        || item.getAttemptCount()!=0 || item.getLeaseToken()!=null || item.getLeaseUntil()!=null
                        || item.getSubmittedAt()!=null || item.getCompletedAt()!=null
                        || item.getResultArtifactId()!=null || replay && !status.isTerminal()) {
                    throw error(INVALID_TRANSITION);
                }
            }
            // Current locking reads, not a potentially stale REPEATABLE_READ snapshot.
            // Native preparations/current Chat work are unsupported; closed conversation outputs stay intact.
            if (cancellation.hasExecutionFacts(tenant,client,owner,taskId)
                    || cancellation.hasCommandFacts(tenant,client,owner,taskId)) throw error(INVALID_TRANSITION);
            List<AgentTaskExecutionGrantEntity> taskGrants=cancellation.lockGrants(tenant,client,owner,taskId);
            if (taskGrants==null) throw error(INVALID_PERSISTED_STATE);
            Set<String> grantIds=new HashSet<>();
            for (var grant:taskGrants) {
                if (grant==null || !scoped(grant,tenant,client,owner,taskId)
                        || !exact(grant.getGrantId(),100) || !grantIds.add(grant.getGrantId())
                        || grant.getGrantVersion()==null || grant.getGrantVersion()<1
                        || grant.getGrantVersion()==Long.MAX_VALUE || grant.getCreatedAt()==null
                        || grant.getCreatedAt()<0 || !Set.of("ACTIVE","REVOKED","SUPERSEDED").contains(
                                grant.getState()==null ? "" : grant.getState())) throw error(INVALID_PERSISTED_STATE);
                if (replay && "ACTIVE".equals(grant.getState())) throw error(INVALID_PERSISTED_STATE);
            }
            var bootstraps=cancellation.lockBootstraps(tenant,client,owner,taskId);
            if (bootstraps==null) throw error(INVALID_PERSISTED_STATE);
            Set<Long> bootstrapIds=new HashSet<>();
            for (var bootstrap:bootstraps) {
                if (bootstrap==null || !tenant.equals(bootstrap.getTenantId())
                        || !client.equals(bootstrap.getClientId()) || !owner.equals(bootstrap.getOwnerJiacn())
                        || !taskId.equals(bootstrap.getTaskId()) || bootstrap.getId()==null
                        || !bootstrapIds.add(bootstrap.getId()) || bootstrap.getAttemptCount()==null
                        || bootstrap.getAttemptCount()<0 || bootstrap.getCreatedAt()==null
                        || bootstrap.getCreatedAt()<=0) throw error(INVALID_PERSISTED_STATE);
                version(bootstrap.getVersion());
                if (bootstrap.getLeaseOwner()!=null || bootstrap.getLeaseUntil()!=null
                        || !Set.of("RETRY","ADMITTED","DEAD").contains(
                                bootstrap.getStatus()==null ? "" : bootstrap.getStatus())
                        || replay && "RETRY".equals(bootstrap.getStatus())) throw error(INVALID_TRANSITION);
                if (bootstrap.getAttemptCount()<1) throw error(INVALID_PERSISTED_STATE);
                if ("RETRY".equals(bootstrap.getStatus()) && (bootstrap.getAdmittedConversationId()!=null
                        || bootstrap.getAdmittedRequestId()!=null || bootstrap.getNextRetryAt()==null)) {
                    throw error(INVALID_PERSISTED_STATE);
                }
            }
            // State-idempotent closed-root readback, including the original version after a lost response.
            // No separate public idempotency key/receipt is claimed or created.
            if (replay) return new Receipt(taskId,"cancelled",root.getTaskVersion());
            for (var member:members) {
                String target=switch (member.getMemberStatus()) {
                    case "invited" -> "rejected";
                    case "accepted" -> "left";
                    default -> null; // preserve pre-existing rejected/left terminals
                };
                if (target!=null) states.transitionMember(tenant,client,owner,taskId,member.getAgentId(),
                        transition(target,member.getVersion()));
            }
            for (var item:items) {
                if (!"cancelled".equals(item.getStatus())) states.transitionWorkItem(
                        tenant,client,owner,item.getWorkItemId(),transition("cancelled",item.getVersion()));
            }
            long now=System.currentTimeMillis();
            for (var grant:taskGrants) {
                if (!"ACTIVE".equals(grant.getState())) continue;
                String hash=TaskEventPayload.ContentDigest.fromUtf8(
                        "INITIAL_CANCEL\n"+taskId+"\n"+expectedTaskVersion+"\n"+grant.getGrantId()).sha256();
                if (!grants.revoke(tenant,client,owner,taskId,grant.getGrantId(),grant.getGrantVersion(),
                        "cancel_"+hash,hash,Math.max(now,grant.getCreatedAt()))) throw error(VERSION_CONFLICT);
            }
            for (var bootstrap:bootstraps) {
                if ("RETRY".equals(bootstrap.getStatus()) && cancellation.cancelRetryBootstrap(
                        tenant,client,owner,taskId,bootstrap.getId(),bootstrap.getVersion(),now)!=1) {
                    throw error(VERSION_CONFLICT);
                }
            }
            AgentTaskStateDTO result=states.transitionTask(tenant,client,owner,taskId,
                    transition("cancelled",expectedTaskVersion));
            if (result==null || !taskId.equals(result.getTaskId()) || !"cancelled".equals(result.getStatus())
                    || !Objects.equals(result.getVersion(),expectedTaskVersion+1)) throw error(INVALID_PERSISTED_STATE);
            cancellation.releaseTaskOccupation(tenant,client,owner,taskId,now);
            return new Receipt(result.getTaskId(),result.getStatus(),result.getVersion());
        });
    }

    private static boolean scoped(cn.jia.core.entity.BaseEntity row,String t,String c,String o,String id) {
        if (!t.equals(row.getTenantId()) || !c.equals(row.getClientId())) return false;
        if (row instanceof AgentTaskMemberEntity m) return o.equals(m.getOwnerJiacn()) && id.equals(m.getTaskId());
        if (row instanceof AgentTaskWorkItemEntity w) return o.equals(w.getOwnerJiacn()) && id.equals(w.getTaskId());
        if (row instanceof AgentTaskExecutionGrantEntity g) return o.equals(g.getOwnerJiacn()) && id.equals(g.getTaskId());
        return false;
    }
    private static AgentTaskStateTransitionDTO transition(String status,long version) {
        var command=new AgentTaskStateTransitionDTO(); command.setTargetStatus(status); command.setExpectedVersion(version);
        return command;
    }
    private static void version(Long version) {
        if (version==null || version<0 || version==Long.MAX_VALUE) throw error(INVALID_PERSISTED_STATE);
    }
    private static boolean exact(String s,int max) {
        return s!=null && !s.isBlank() && s.length()<=max && s.equals(s.strip())
                && s.chars().noneMatch(Character::isISOControl);
    }
    private static AgentTaskStateException error(AgentTaskStateException.Reason reason) {
        return new AgentTaskStateException(reason,"Initial task cancellation is unavailable for this input or state");
    }
}
