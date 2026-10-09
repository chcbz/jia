package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskCancellationDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.mapper.AgentTaskCancellationMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.HashSet;

@Named
public class AgentTaskCancellationDaoImpl implements AgentTaskCancellationDao {
    private final AgentTaskCancellationMapper mapper;
    @Inject public AgentTaskCancellationDaoImpl(AgentTaskCancellationMapper mapper) { this.mapper=mapper; }
    private void scope(String tenant, String client, String owner, String task) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenant, client, owner);
        TaskCollaborationDaoSupport.requireId(task, "taskId");
    }
    @Override public List<AgentTaskMemberEntity> lockMembers(String t, String c, String o, String id) {
        scope(t,c,o,id); return mapper.lockMembers(t,c,o,id);
    }
    @Override public List<AgentTaskExecutionGrantEntity> lockGrants(String t, String c, String o, String id) {
        scope(t,c,o,id); return mapper.lockGrants(t,c,o,id);
    }
    @Override public List<cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity> lockBootstraps(
            String t, String c, String o, String id) {
        scope(t,c,o,id); return mapper.lockBootstraps(t,c,o,id);
    }
    @Override public int cancelRetryBootstrap(String t, String c, String o, String task,
            long id, long version, long now) {
        scope(t,c,o,task); return mapper.cancelRetryBootstrap(t,c,o,task,id,version,now);
    }
    @Override public boolean hasMoneyFacts(String t, String c, String o, String id) {
        scope(t,c,o,id); return mapper.funding(t,c,o,id)!=null || mapper.fundingOperation(t,c,o,id)!=null;
    }
    @Override public boolean hasExecutionFacts(String t, String c, String o, String id) {
        scope(t,c,o,id); long now=System.currentTimeMillis();
        return mapper.execution(t,c,o,id,now)!=null || mapper.finalization(t,c,o,id)!=null
                || mapper.delivery(t,c,o,id)!=null || hasUnsafeCostConsent(t,c,o,id,now)
                || mapper.chatTurn(t,c,o,id)!=null || mapper.chatRequest(t,c,o,id)!=null
                || mapper.chatStep(t,c,o,id)!=null || mapper.chatDispatch(t,c,o,id)!=null;
    }
    private boolean hasUnsafeCostConsent(String t,String c,String o,String task,long now) {
        List<AgentTaskProviderCostConsentEntity> rows=mapper.lockCostConsents(t,c,o,task);
        if (rows==null) throw new IllegalStateException("Cost consent inventory is unavailable");
        var ids=new HashSet<String>();
        for (var row:rows) {
            if (row==null || !t.equals(row.getTenantId()) || !c.equals(row.getClientId())
                    || !o.equals(row.getOwnerJiacn()) || !task.equals(row.getTaskId())
                    || !locator(row.getConsentId()) || !ids.add(row.getConsentId())
                    || row.getVersion()==null || row.getVersion()<1
                    || row.getCreatedAt()==null || row.getCreatedAt()<1 || row.getCreatedAt()>now
                    || row.getExpiresAt()==null || row.getExpiresAt()<=row.getCreatedAt()) return true;
            boolean bound=row.getBoundGrantId()!=null || row.getBoundGrantVersion()!=null
                    || row.getBoundAssignmentRevision()!=null;
            if (bound && (!locator(row.getBoundGrantId()) || row.getBoundGrantVersion()==null
                    || row.getBoundGrantVersion()<1 || row.getBoundAssignmentRevision()==null
                    || row.getBoundAssignmentRevision()<0)) return true;
            boolean reserved=row.getReservedExecutionId()!=null || row.getReservedRunId()!=null;
            if (reserved && (!bound || !locator(row.getReservedExecutionId())
                    || !locator(row.getReservedRunId()))) return true;
            if ("CONSUMED".equals(row.getState())) {
                // ISSUED -> BOUND -> RESERVED -> CONSUMED. Expiry is not current activity.
                if (row.getVersion()<4 || !bound || !reserved || !locator(row.getConsumedLeaseId())
                        || !historic(row.getConsumedAt(),row.getCreatedAt(),now)
                        || row.getRevokedAt()!=null || row.getRevokeIdempotencyKey()!=null
                        || row.getRevokeRequestDigest()!=null) return true;
            } else if ("REVOKED".equals(row.getState())) {
                // A revoked ISSUED/BOUND/RESERVED row may retain its binding/reservation history.
                if (row.getVersion()<(reserved ? 4 : bound ? 3 : 2)
                        || row.getConsumedAt()!=null || row.getConsumedLeaseId()!=null
                        || !historic(row.getRevokedAt(),row.getCreatedAt(),now)
                        || !locator(row.getRevokeIdempotencyKey()) || row.getRevokeRequestDigest()==null
                        || !row.getRevokeRequestDigest().matches("[0-9a-f]{64}")) return true;
            } else return true; // ISSUED, BOUND, RESERVED, expired projections, unknown states.
        }
        return false;
    }
    private static boolean historic(Long at,long created,long now) {
        return at!=null && at>=created && at<=now;
    }
    private static boolean locator(String value) {
        return value!=null && !value.isBlank() && value.length()<=100 && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }
    @Override public boolean hasCommandFacts(String t, String c, String o, String id) {
        scope(t,c,o,id); return mapper.command(t,c,o,id)!=null || mapper.outbox(t,c,o,id)!=null;
    }
    @Override public int releaseTaskOccupation(String t, String c, String o, String id, long now) {
        scope(t,c,o,id); return mapper.releaseTaskOccupation(t,c,o,id,now);
    }
}
