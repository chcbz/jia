package cn.jia.agent.entity;

/** Closed set of canonical Agent command payloads. */
public sealed interface AgentCommandPayload
        permits AgentTaskInvitePayload, AgentHallCommandPayload, AgentSkillInstallPayload {
}
