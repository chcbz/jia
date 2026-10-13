package cn.jia.chat.service;
import cn.jia.agent.service.SkillExecutionService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatGenericResultContractTest {
    private Map<String,Object> manifest(Object data) {
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion",1);m.put("data",data);m.put("artifacts",List.of());String digest=ChatSkillSnapshotJson.sha(m);
        m.put("resultDigest",digest);m.put("manifestId","manifest");return m;
    }
    @Test void dataOnlyResultDoesNotRequireAnArtifactAndArchivingIsNotACompletionClaim() {
        for(Object data:Arrays.asList(null,Map.of("answer","done"),"text",List.of(1,true))) {
            var m=manifest(data);var view=new SkillExecutionService.ResultView("execution","RESULT_COMMITTED","ARCHIVING",null,"a".repeat(64),m);
            assertEquals(m,ChatBountyAssetProjector.verifiedResult(view,"execution"));ChatBountyAssetProjector.verifyArtifacts(m,List.of());
        }
    }
    @Test void uncommittedTamperedOrForeignManifestCannotBeProjected() {
        var m=manifest(Map.of("answer","done"));m.put("data",Map.of("answer","tampered"));
        assertThrows(IllegalStateException.class,()->ChatBountyAssetProjector.verifiedResult(new SkillExecutionService.ResultView("execution","RESULT_COMMITTED","ARCHIVING",null,"a".repeat(64),m),"execution"));
        assertThrows(IllegalStateException.class,()->ChatBountyAssetProjector.verifiedResult(new SkillExecutionService.ResultView("other","RESULT_COMMITTED","COMPLETE",null,"a".repeat(64),manifest(null)),"execution"));
        assertThrows(IllegalStateException.class,()->ChatBountyAssetProjector.verifiedResult(new SkillExecutionService.ResultView("execution","RUNNING","RESULT_READY",null,"a".repeat(64),manifest(null)),"execution"));
    }
}
