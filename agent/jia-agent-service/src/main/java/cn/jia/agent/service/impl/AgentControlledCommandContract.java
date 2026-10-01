package cn.jia.agent.service.impl;

import cn.jia.agent.entity.*;

/** Strict v1 business references. No user input can select an endpoint or runtime scope. */
final class AgentControlledCommandContract {
    private AgentControlledCommandContract() { }
    static void validate(AgentCommandDraft d) {
        String resource;
        if ("PLATFORM_SKILL_INSTALL".equals(d.commandType()) && d.payload() instanceof AgentPlatformSkillInstallPayload p) {
            require(p.schemaVersion()==1);
            requireId(p.installationId()); positive(p.bindingVersion()); requireId(p.challengeId());
            require(p.skillKey()!=null && p.skillKey().length()<=64 && p.skillKey().matches("[a-z0-9]+(?:-[a-z0-9]+)*"));
            require(p.skillVersion()!=null && p.skillVersion().matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}"));
            sha(p.packageSha256());
            require(("/internal/agent/platform-skills/installations/"+p.installationId()+"/package").equals(p.packageRef()));
            resource=p.installationId();
            require(resource.equals(d.taskId()) && p.challengeId().equals(d.causationId()));
        } else if ("ARCHIVE_MAINTENANCE_EXECUTE".equals(d.commandType()) && d.payload() instanceof AgentArchiveMaintenancePayload p) {
            require(p.schemaVersion()==1);
            requireId(p.jobId()); requireId(p.runId()); requireId(p.appointmentId()); requireId(p.grantRef()); requireId(p.executionRef()); requireId(p.dispatchKey()); requireId(p.skillInstallationId());
            positive(p.executionEpoch()); positive(p.appointmentRevision());
            positive(p.managerAuthorizationRevision()); positive(p.bindingVersion()); sha(p.skillPackageSha256());
            require(("/internal/archive/v1/jobs/"+p.jobId()+"/runs/"+p.runId()+"/context").equals(p.contextRef()));
            resource=p.runId();
            require(p.jobId().equals(d.taskId()) && resource.equals(d.causationId()));
        } else throw new IllegalArgumentException("Controlled command payload type mismatch");
        require(d.intentId()==null && d.workItemId()==null && d.commandId().equals(
                AgentCommandCanonicalCodec.controlledCommandId(d.tenantId(),d.clientId(),d.ownerJiacn(),resource,d.targetAgentId(),d.commandType())));
    }
    static void requireId(String v) { require(v!=null && v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")); }
    private static void positive(String v) {
        require(v!=null && v.matches("[1-9][0-9]{0,18}"));
        try { Long.parseLong(v); } catch (NumberFormatException e) { throw new IllegalArgumentException("Controlled version overflow"); }
    }
    private static void sha(String v) { require(v!=null && v.matches("[0-9a-f]{64}")); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid controlled command reference"); }
}

