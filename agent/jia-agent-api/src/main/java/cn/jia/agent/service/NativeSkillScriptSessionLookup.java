package cn.jia.agent.service;

/** Current authenticated execution capability, not an installation or task grant. */
public interface NativeSkillScriptSessionLookup {
    boolean supports(PersonalWorkspaceExecutionService.RuntimeScope scope, String taskId);
}
