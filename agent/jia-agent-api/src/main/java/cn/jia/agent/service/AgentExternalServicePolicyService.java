package cn.jia.agent.service;
import java.util.List;
import cn.jia.agent.entity.AgentTaskTargetSummaryDTO;
public interface AgentExternalServicePolicyService {
    record Policy(String agentId, boolean enabled, String revision) { }
    record Candidates(List<AgentTaskTargetSummaryDTO> items,String nextAfterAgentId) { }
    Policy read(AgentTaskExecutionGrantService.Scope scope,String agentId);
    Policy write(AgentTaskExecutionGrantService.Scope scope,String agentId,String key,boolean enabled,long expectedRevision);
    Policy receipt(AgentTaskExecutionGrantService.Scope scope,String agentId,String key);
    Candidates candidates(AgentTaskExecutionGrantService.Scope scope,String taskId,String afterAgentId,int pageSize);
    /** Internal projection for task-authorized recommendations; not a management permission or HTTP DTO. */
    List<cn.jia.agent.entity.AgentTaskAssigneeCandidateDTO> recommendationCandidates(AgentTaskExecutionGrantService.Scope scope,String taskId);
    AgentTaskTargetSummaryDTO assignedTarget(AgentTaskExecutionGrantService.Scope scope,String taskId);
}
