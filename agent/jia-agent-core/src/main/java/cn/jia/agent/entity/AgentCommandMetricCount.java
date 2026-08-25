package cn.jia.agent.entity;

/** Mapper aggregate row with a bounded status/outcome label. */
public record AgentCommandMetricCount(String label, long count) {
}
