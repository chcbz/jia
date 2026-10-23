package cn.jia.agent.service;

import java.util.List;
import java.util.Map;
import static cn.jia.agent.service.PersonalWorkspaceExecutionService.*;

/** Trusted application execution boundary; no browser-provided skill authority or fees. */
public interface SkillExecutionService {
    record Create(String actionEventId,String taskId,String conversationId,long conversationGeneration,
            String targetAgentId,String grantId,long grantVersion,long assignmentRevision,Map<String,Object> snapshot) { }
    record Creation(String executionId,String taskId,String runId,String conversationId,String targetAgentId,
            String state,String snapshotDigest) { }
    Creation create(OwnerScope scope,Create command);
    record Skill(String key,String version,String packageSha256,String manifestSha256) { }
    record Command(int executionProtocolVersion,String executionId,String taskId,String runId,
            String conversationId,String conversationGeneration,String commandId,String messageId,Skill skill,
            String snapshotDigest,Map<String,Object> parameters,String instruction,List<String> inputRefs,
            Map<String,Object> outputs,Map<String,Object> taskSkillBinding) implements ConversationCommandView { }
    record EffectStart(String executionId,String snapshotDigest,ConversationFence fence,
            String effectDigest,String providerBindingRevision) { }
    record EffectResult(String executionId,String snapshotDigest,ConversationFence fence,
            String effectDigest,String providerBindingRevision,String status,String providerRequestId,String resultDigest) { }
    record EffectReceipt(int schemaVersion,String disposition,String status,String providerRequestId,String resultDigest) { }
    EffectReceipt startEffect(RuntimeScope scope,String taskId,String runId,String effectId,EffectStart command);
    EffectReceipt recordEffect(RuntimeScope scope,String taskId,String runId,String effectId,EffectResult command);
    record Inputs(int schemaVersion,String executionId,long leaseVersion,String snapshotDigest,
            boolean noReferencedMaterials,List<Map<String,Object>> inputs) { }
    Inputs inputs(RuntimeScope scope,String taskId,String runId,ConversationFence fence);
    RuntimeContent inputContent(RuntimeScope scope,String taskId,String runId,String inputRef,ConversationFence fence);
    record Resume(String executionId,String snapshotDigest) { }
    record ResumeReceipt(ConversationLease lease,String phase,List<String> allowedWork,Map<String,Object> resultManifest) { }
    ResumeReceipt resume(RuntimeScope scope,String taskId,String runId,Resume command);
    record InputReceipt(String inputRef,String sha256,String carrier,String delivery) { }
    record ResultCommit(ConversationFence fence,String snapshotDigest,Object data,List<OutputDeclaration> artifacts,
            List<InputReceipt> inputReceipts) { }
    record ResultReceipt(String manifestId,String state,String phase,String resultDigest) { }
    ResultReceipt commitResult(RuntimeScope scope,String taskId,String runId,String manifestId,ResultCommit command);
    record ResultView(String executionId,String state,String phase,String lastErrorCode,String snapshotDigest,long executionStateVersion,
            Map<String,Object> resultManifest) { }
    ResultView result(OwnerScope scope,String executionId);
    /** Called by committed Chat projection in its mandatory transaction; not a runtime success claim. */
    void markProjected(OwnerScope scope,String executionId,String expectedResultDigest);
}
