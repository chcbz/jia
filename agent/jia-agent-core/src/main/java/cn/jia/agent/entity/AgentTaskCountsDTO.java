package cn.jia.agent.entity;

import java.util.Map;

/** Decimal strings preserve COUNT bigint precision across the HTTP boundary. */
public record AgentTaskCountsDTO(Map<String, String> phaseCounts, Map<String, String> rawStatusCounts) { }
