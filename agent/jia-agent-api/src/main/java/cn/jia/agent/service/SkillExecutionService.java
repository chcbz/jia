package cn.jia.agent.service;

import java.util.List;
import java.util.Map;
import static cn.jia.agent.service.PersonalWorkspaceExecutionService.*;

/** Trusted application execution boundary; no browser-provided skill authority or fees. */
public interface SkillExecutionService {
    /** Isolated local workspace/file and artifact/result tools only. Never network, credentials or paid providers. */
    String NATIVE_LOCAL_FILES_POLICY = "NATIVE_LOCAL_FILES_V1";
    String NATIVE_SKILL_SCRIPT_POLICY = "NATIVE_SKILL_SCRIPT_V1";
    record Create(String actionEventId,String taskId,String conversationId,long conversationGeneration,
            String targetAgentId,String grantId,long grantVersion,long assignmentRevision,Map<String,Object> snapshot) { }
    record Creation(String executionId,String taskId,String runId,String conversationId,String targetAgentId,
            String state,String snapshotDigest) { }
    Creation create(OwnerScope scope,Create command);
    record Skill(String key,String version,String packageSha256,String manifestSha256) { }
    /** Protocol 5 only. Skills are exact managed bindings, never execution/Provider authority. */
    record AuthorizedSkill(Skill skill,Map<String,Object> taskSkillBinding) { }
    record Command(int executionProtocolVersion,String executionId,String taskId,String runId,
            String conversationId,String conversationGeneration,String commandId,String messageId,List<AuthorizedSkill> skills,
            String snapshotDigest,Map<String,Object> parameters,String instruction,List<String> inputRefs,
            Map<String,Object> outputs,String toolPolicyRef) implements ConversationCommandView { }
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
    RuntimeDownload inputContent(RuntimeScope scope,String taskId,String runId,String inputRef,ConversationFence fence);
    record Resume(String executionId,String snapshotDigest) { }
    record ResumeReceipt(ConversationLease lease,String phase,List<String> allowedWork,Map<String,Object> resultManifest,
            Map<String,Object> effects,String turnFailureCode) {
        public ResumeReceipt(ConversationLease lease,String phase,List<String> allowedWork,Map<String,Object> resultManifest,Map<String,Object> effects) {
            this(lease,phase,allowedWork,resultManifest,effects,null);
        }
        public ResumeReceipt(ConversationLease lease,String phase,List<String> allowedWork,Map<String,Object> resultManifest) {
            this(lease,phase,allowedWork,resultManifest,Map.of(),null);
        }
    }
    ResumeReceipt resume(RuntimeScope scope,String taskId,String runId,Resume command);
    record InputReceipt(String inputRef,String sha256,String carrier,String delivery) { }
    record ResultCommit(ConversationFence fence,String snapshotDigest,Object data,List<OutputDeclaration> artifacts,
            List<InputReceipt> inputReceipts,List<String> effectDependencies) {
        /** Source compatibility for internal callers only; v5 HTTP requires explicit dependencies. */
        public ResultCommit(ConversationFence fence,String snapshotDigest,Object data,List<OutputDeclaration> artifacts,
                List<InputReceipt> inputReceipts) { this(fence,snapshotDigest,data,artifacts,inputReceipts,List.of()); }
    }
    record ResultReceipt(String manifestId,String state,String phase,String resultDigest) { }
    ResultReceipt commitResult(RuntimeScope scope,String taskId,String runId,String manifestId,ResultCommit command);
    record ResultView(String executionId,String state,String phase,String lastErrorCode,String snapshotDigest,long executionStateVersion,
            Map<String,Object> resultManifest) { }
    ResultView result(OwnerScope scope,String executionId);
    /** Called by committed Chat projection in its mandatory transaction; not a runtime success claim. */
    void markProjected(OwnerScope scope,String executionId,String expectedResultDigest);
}
