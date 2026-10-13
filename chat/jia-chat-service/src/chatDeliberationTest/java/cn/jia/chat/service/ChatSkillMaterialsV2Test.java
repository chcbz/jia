package cn.jia.chat.service;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChatSkillMaterialsV2Test {
    private Map<String,Object> body(String scope,List<?> selectors) {
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion",2);m.put("materialScope",scope);m.put("intent","DISCUSSION");m.put("taskId","task");
        m.put("expectedAssignmentRevision","4");m.put("content","请处理");m.put("parentOutcomeId",null);m.put("expectedParentStateVersion",null);
        m.put("pendingQuestionId",null);m.put("expectedPendingQuestionStateVersion",null);m.put("sourceSelectors",selectors);return m;
    }
    @Test void strictDiscussionMaterialScopesAndTaggedResultSelectors() {
        var result=Map.of("kind","EXECUTION_RESULT","executionId","pwe_abc","resultDigest","a".repeat(64));
        assertEquals("EXPLICIT",ChatTypedDeliberationWire.parse(body("EXPLICIT",List.of(result))).materialScope());
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(body("NONE",List.of(result))));
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(body("HISTORY",List.of())));
        var legacy=body("TASK_DEFAULT",List.of());legacy.put("schemaVersion",1);assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(legacy));
        var extra=new LinkedHashMap<String,Object>(result);extra.put("assetId","ast_other");assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(body("EXPLICIT",List.of(extra))));
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(body("EXPLICIT",List.of(result,result))));
    }
    @Test void explicitAndNoneNeverScanWorkspaceOrImplicitHistory() {
        var jdbc=mock(JdbcTemplate.class);
        var context=new ChatTypedDeliberationContextService(jdbc,mock(cn.jia.chat.handler.TypedDeliberationSessionRegistry.class),
                mock(cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer.class),mock(ChatActionCapabilityService.class),true);
        var scope=new ChatTypedDeliberationContextService.Scope("0","owner","client","1",1);
        var source=new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION","file","1","INPUT",null,null);
        assertEquals(List.of(source),context.effectiveSelectors(scope,"task","EXPLICIT",List.of(source),List.of(source)));
        assertEquals(List.of(),context.effectiveSelectors(scope,"task","NONE",List.of(),List.of(source)));verifyNoInteractions(jdbc);
    }
    @Test void snapshotKeysAreSortedAndArrayOrderIsPreservedExactlyAsCore() {
        var a=new LinkedHashMap<String,Object>();a.put("z",List.of("b","a"));a.put("a",1);
        assertEquals("{\"a\":1,\"z\":[\"b\",\"a\"]}",ChatSkillSnapshotJson.write(a));
        assertNotEquals(ChatSkillSnapshotJson.sha(a),ChatSkillSnapshotJson.sha(Map.of("a",1,"z",List.of("a","b"))));
        assertEquals("{\"fraction\":1.5}",ChatSkillSnapshotJson.write(Map.of("fraction",1.5)));
    }
}
