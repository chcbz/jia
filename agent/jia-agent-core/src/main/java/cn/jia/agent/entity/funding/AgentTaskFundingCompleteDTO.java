package cn.jia.agent.entity.funding;

/** Synthetic preview orchestration only, not provider-billing evidence. */
public record AgentTaskFundingCompleteDTO(String expectedTaskVersion, String actualComputeMicro) { }
