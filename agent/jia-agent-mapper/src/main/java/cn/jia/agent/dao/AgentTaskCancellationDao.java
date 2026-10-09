package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import java.util.List;

/** Current reads, always called beneath the owner-scoped task-root lock. */
public interface AgentTaskCancellationDao {
    List<AgentTaskMemberEntity> lockMembers(String tenantId, String clientId, String ownerJiacn, String taskId);
    List<AgentTaskExecutionGrantEntity> lockGrants(String tenantId, String clientId, String ownerJiacn, String taskId);
    List<cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity> lockBootstraps(
            String tenantId, String clientId, String ownerJiacn, String taskId);
    int cancelRetryBootstrap(String tenantId, String clientId, String ownerJiacn,
            String taskId, long id, long version, long now);
    boolean hasMoneyFacts(String tenantId, String clientId, String ownerJiacn, String taskId);
    boolean hasExecutionFacts(String tenantId, String clientId, String ownerJiacn, String taskId);
    boolean hasCommandFacts(String tenantId, String clientId, String ownerJiacn, String taskId);
    int releaseTaskOccupation(String tenantId, String clientId, String ownerJiacn, String taskId, long now);
}
