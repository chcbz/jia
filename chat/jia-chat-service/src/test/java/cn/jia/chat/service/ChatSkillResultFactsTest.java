package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillExecutionService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatSkillResultFactsTest {
    static Map<String,Object> manifest(int schema) {
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",schema);
        manifest.put("data",Map.of("answer","result"));manifest.put("artifacts",List.of());manifest.put("inputReceipts",List.of());
        if(schema==2)manifest.put("effectDependencies",List.of("operation_1"));
        String digest=ChatSkillSnapshotJson.sha(manifest);manifest.put("manifestId","manifest_"+digest);manifest.put("resultDigest",digest);
        return manifest;
    }
    static SkillExecutionService.ResultView view(Map<String,Object> manifest) {
        return new SkillExecutionService.ResultView("execution","RESULT_COMMITTED","COMPLETE",null,"a".repeat(64),3,manifest);
    }
    @Test void historyAndNativeUseTheirOwnUnchangedPreimages() {
        for(int schema:List.of(1,2)) {
            var manifest=manifest(schema);String before=ChatSkillSnapshotJson.write(manifest);
            assertSame(manifest,ChatSkillResultFacts.verifiedResult(view(manifest),"execution"));
            assertEquals(before,ChatSkillSnapshotJson.write(manifest));
        }
        assertNotEquals(manifest(1).get("resultDigest"),manifest(2).get("resultDigest"));
    }
    @Test void missingDependenciesAndPostCommitChangesCannotBeProjected() {
        var missing=manifest(2);missing.remove("effectDependencies");
        assertThrows(IllegalStateException.class,()->ChatSkillResultFacts.verifiedResult(view(missing),"execution"));
        var changed=manifest(2);changed.put("effectDependencies",List.of("other"));
        assertThrows(IllegalStateException.class,()->ChatSkillResultFacts.verifiedResult(view(changed),"execution"));
        var legacy=manifest(1);legacy.put("effectDependencies",List.of());
        assertThrows(IllegalStateException.class,()->ChatSkillResultFacts.verifiedResult(view(legacy),"execution"));
    }
    @Test void artifactByteLengthIsPartOfTheHashAndExactCommittedLineage() {
        var manifest=manifest(2);manifest.remove("resultDigest");manifest.remove("manifestId");
        String hash="b".repeat(64);
        manifest.put("artifacts",List.of(Map.of("outputId","output_1","sha256",hash,"byteLength",14L)));
        String digest=ChatSkillSnapshotJson.sha(manifest);
        manifest.put("resultDigest",digest);manifest.put("manifestId","manifest_"+digest);
        ChatSkillResultFacts.verifiedResult(view(manifest),"execution");
        ChatSkillResultFacts.verifyArtifacts(manifest,List.of(new PersonalWorkspaceExecutionService.ConversationOutputInfo("execution","output_1","text/plain",hash,14)));
        assertThrows(IllegalStateException.class,()->ChatSkillResultFacts.verifyArtifacts(manifest,List.of(new PersonalWorkspaceExecutionService.ConversationOutputInfo("execution","output_1","text/plain",hash,15))));
        manifest.put("artifacts",List.of(Map.of("outputId","output_1","sha256",hash,"byteLength","14")));
        assertThrows(IllegalStateException.class,()->ChatSkillResultFacts.verifiedResult(view(manifest),"execution"));
    }
}
