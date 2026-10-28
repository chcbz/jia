package cn.jia.chat.api;

import cn.jia.agent.service.*;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.service.*;
import cn.jia.core.context.EsContext;
import cn.jia.core.entity.JsonResult;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real result consumer and wire; mocked SQL does not prove MySQL join/ACL enforcement. */
class ChatSkillResultControllerTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final ChatSkillSourceAccessService sources=mock(ChatSkillSourceAccessService.class);
    final SkillExecutionService skills=mock(SkillExecutionService.class);
    final ChatBountyMediaController media=mock(ChatBountyMediaController.class);
    final HumanSenderIdentityResolver identities=mock(HumanSenderIdentityResolver.class);
    final TenantScopeResolver tenants=mock(TenantScopeResolver.class);
    final Authentication auth=mock(Authentication.class);
    final PersonalWorkspaceExecutionService.OwnerScope owner=new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner");
    MockMvc mvc;
    @BeforeEach void setup() {
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender("user","Owner","owner","client",DisplayNameSource.JIACN));
        when(tenants.resolve(auth)).thenReturn("0");
        when(conversations.findScopedById("owner","client","42"))
                .thenReturn(new ChatConversationEntity().setTaskId("task").setLifecycleGeneration(1L));
        when(sources.conversation(owner,"task","42",1)).thenReturn("agent");
        chain("RESULT_COMMITTED");
        when(media.outputs("request","step",auth)).thenReturn(ResponseEntity.ok(JsonResult.success(List.of())));
        mvc=MockMvcBuilders.standaloneSetup(new ChatSkillResultController(jdbc,conversations,sources,skills,media,identities,tenants))
                .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(),new cn.jia.core.config.ExceptionHandlerAdvice())
                .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
    }
    void chain(String state) {
        when(jdbc.queryForList(anyString(),eq("0"),eq("client"),eq("owner"),eq("execution"),eq("42"),eq(1L),eq("task")))
                .thenReturn(List.of(Map.of("request_id","request","step_id","step","step_state",state,"link_state",state,"request_state",state)));
    }
    Map<String,Object> manifest(int schema) {
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion",schema);m.put("data",Map.of("answer","result"));
        m.put("artifacts",List.of());m.put("inputReceipts",List.of());if(schema==2)m.put("effectDependencies",List.of());
        String digest=ChatSkillSnapshotJson.sha(m);m.put("resultDigest",digest);m.put("manifestId","manifest_"+digest);return m;
    }
    void result(int schema,String phase) {
        when(skills.result(owner,"execution")).thenReturn(new SkillExecutionService.ResultView("execution","RESULT_COMMITTED",phase,null,"a".repeat(64),3,manifest(schema)));
    }
    @Test void bothHistoricalAndNativeResultsKeepSchemaTwoDiscoveryAndExplicitExecuteLink() throws Exception {
        for(int schema:List.of(1,2)) {
            result(schema,"COMPLETE");
            mvc.perform(get("/chat/conversations/42/executions/execution/result").principal(auth).header("X-Request-Id","result-read"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.schemaVersion").value(2))
                    .andExpect(jsonPath("$.data.stepId").value("step")).andExpect(jsonPath("$.data.requestId").value("request"))
                    .andExpect(jsonPath("$.data.data.answer").value("result")).andExpect(jsonPath("$.data.outputs").isEmpty());
        }
        verify(jdbc,atLeastOnce()).queryForList(contains("s.kind='EXECUTE' AND e.execution_protocol_version IN (4,5)"),eq("0"),eq("client"),eq("owner"),eq("execution"),eq("42"),eq(1L),eq("task"));
        verify(skills,never()).markProjected(any(),any(),any());
    }
    @Test void missingRelationCannotFallBackToLatestResultAndIncompleteChainCannotExposeCompleteResult() throws Exception {
        result(2,"COMPLETE");chain("RUNNING");
        mvc.perform(get("/chat/conversations/42/executions/execution/result").principal(auth)).andExpect(status().isServiceUnavailable());
        when(jdbc.queryForList(anyString(),eq("0"),eq("client"),eq("owner"),eq("execution"),eq("42"),eq(1L),eq("task"))).thenReturn(List.of());
        mvc.perform(get("/chat/conversations/42/executions/execution/result").principal(auth)).andExpect(status().isNotFound());
        verifyNoInteractions(media);
    }
    @Test void archivingDoesNotExposeOfficialOutputsOrRunProjection() throws Exception {
        result(2,"ARCHIVING");
        mvc.perform(get("/chat/conversations/42/executions/execution/result").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phase").value("ARCHIVING")).andExpect(jsonPath("$.data.outputs").isEmpty());
        verifyNoInteractions(media);verify(skills,never()).markProjected(any(),any(),any());
    }
}
