package cn.jia.agent.entity;
/** Public task-scoped target summary; never a management/runtime credential projection. */
public record AgentTaskTargetSummaryDTO(String agentId,String displayName,String providerDisplayName,
                                   boolean isMine,String availability) { }
