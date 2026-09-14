package cn.jia.agent.entity;

/** Installer-only response. runtimeAuthorization must never be reused in Web responses or logs. */
public record AgentRuntimeV1EnrollmentResult(
        AgentRuntimeV1InstallationView installation, String runtimeAuthorization) { }
