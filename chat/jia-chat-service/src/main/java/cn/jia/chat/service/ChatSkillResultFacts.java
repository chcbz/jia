package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import java.util.*;

/** Shared immutable manifest validation for projection and authorized finalization. */
final class ChatSkillResultFacts {
    private ChatSkillResultFacts() {}
    static Map<String,Object> verifiedResult(cn.jia.agent.service.SkillExecutionService.ResultView view,String executionId) {
        if(view==null||!executionId.equals(view.executionId())||!"RESULT_COMMITTED".equals(view.state())
                ||!Set.of("ARCHIVING","COMPLETE").contains(view.phase())||view.resultManifest()==null)throw new IllegalStateException("Result not committed");
        var manifest=view.resultManifest();
        boolean nativeResult=Integer.valueOf(2).equals(manifest.get("schemaVersion"));
        var fields=new HashSet<>(Set.of("schemaVersion","data","artifacts","inputReceipts","resultDigest","manifestId"));
        if(nativeResult)fields.add("effectDependencies");
        if(!manifest.keySet().equals(fields)
                ||!(nativeResult||Integer.valueOf(1).equals(manifest.get("schemaVersion")))
                ||nativeResult&&!(manifest.get("effectDependencies") instanceof List<?>)||!(manifest.get("artifacts") instanceof List<?>)||!(manifest.get("inputReceipts") instanceof List<?>))throw new IllegalStateException("Invalid result manifest");
        var preimage=new java.util.LinkedHashMap<>(manifest);preimage.remove("resultDigest");preimage.remove("manifestId");
        if(!ChatSkillSnapshotJson.sha(preimage).equals(manifest.get("resultDigest")))throw new IllegalStateException("Result digest mismatch");return manifest;
    }
    static void verifyArtifacts(Map<String,Object> manifest,List<PersonalWorkspaceExecutionService.ConversationOutputInfo> outputs) {
        var artifacts=(List<?>)manifest.get("artifacts");if(artifacts.size()!=outputs.size())throw new IllegalStateException("Result artifact count mismatch");
        var seen=new java.util.HashSet<String>();
        for(Object value:artifacts) {
            if(!(value instanceof Map<?,?> item)||!item.keySet().equals(Set.of("outputId","sha256","byteLength"))
                    ||!(item.get("outputId") instanceof String id)||!seen.add(id))throw new IllegalStateException("Invalid artifact");
            var actual=outputs.stream().filter(o->id.equals(o.outputId())).toList();
            if(actual.size()!=1||!item.get("sha256").equals(actual.getFirst().sha256())
                    ||!item.get("byteLength").toString().equals(Long.toString(actual.getFirst().byteLength())))throw new IllegalStateException("Artifact lineage mismatch");
        }
    }
}
