package cn.jia.agent.service;

/** Read-only, single-shot model port. Does not execute or assign tasks. */
public interface AgentAssigneeRecommendationModel {
    String judge(String inputJson);
}
