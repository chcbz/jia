package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskCancellationDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.mapper.AgentTaskCancellationMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;

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
        scope(t,c,o,id); return mapper.execution(t,c,o,id,System.currentTimeMillis())!=null || mapper.finalization(t,c,o,id)!=null || mapper.delivery(t,c,o,id)!=null || mapper.costConsent(t,c,o,id)!=null
                || mapper.chatTurn(t,c,o,id)!=null || mapper.chatRequest(t,c,o,id)!=null
                || mapper.chatStep(t,c,o,id)!=null || mapper.chatDispatch(t,c,o,id)!=null;
    }
    @Override public boolean hasCommandFacts(String t, String c, String o, String id) {
        scope(t,c,o,id); return mapper.command(t,c,o,id)!=null || mapper.outbox(t,c,o,id)!=null;
    }
    @Override public int releaseTaskOccupation(String t, String c, String o, String id, long now) {
        scope(t,c,o,id); return mapper.releaseTaskOccupation(t,c,o,id,now);
    }
}
