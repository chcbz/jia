package cn.jia.chat.api;

import cn.jia.chat.service.*;
import cn.jia.agent.service.TaskSkillPolicyService;
import cn.jia.core.context.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ChatSkillHttpBoundaryTest {
    final ChatActionCapabilityService capabilities=mock(ChatActionCapabilityService.class);
    final HumanSenderIdentityResolver identities=mock(HumanSenderIdentityResolver.class);
    final TenantScopeResolver tenants=TenantScopeResolver.legacySingleTenant();
    MockMvc mvc;
    @BeforeEach void init() {
        mvc=MockMvcBuilders.standaloneSetup(new TaskSkillCapabilityController(capabilities,identities,tenants)).build();
        var context=new EsContext();context.setTenantId("0");context.setJiacn("owner");context.setClientId("client");EsContextHolder.setContext(context);
        when(identities.resolve(any())).thenReturn(new ServerResolvedSender("user","Owner","owner","client",DisplayNameSource.FALLBACK));
    }
    @AfterEach void clear() { EsContextHolder.clearContext(); }
    static JwtAuthenticationToken jwt(String owner) {return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","none")
            .claim("tenant_id","0").claim("jiacn",owner).claim("client_id","client").build(),List.of());}
    @Test void directoryIsNoStoreReadOnlyAllowlistedProjection() throws Exception {
        when(capabilities.taskCatalog(any(),eq("task"))).thenReturn(new ChatActionCapabilityService.TaskCatalog("catalog_1",List.of(Map.of(
                "skillKey","extension.new","skillVersion","1","title","New","state","UNAVAILABLE","reasonCode","RUNTIME_NOT_READY","costInfo",Map.of("mode","UNKNOWN")))));
        mvc.perform(get("/agent/tasks/task/skill-capabilities").queryParam("targetAgentId","agent").principal(jwt("owner")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.items[0].costInfo.mode").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].token").doesNotExist()).andExpect(jsonPath("$.data.items[0].providerBinding").doesNotExist());
        verify(capabilities).taskCatalog(new ChatActionCapabilityService.Scope("0","owner","client","agent"),"task");
        verifyNoMoreInteractions(capabilities);
    }
    @Test void foreignMissingAndMalformedReadsStay404() throws Exception {
        mvc.perform(get("/agent/tasks/task/skill-capabilities").queryParam("targetAgentId","agent").principal(jwt("other"))).andExpect(status().isNotFound());
        mvc.perform(get("/agent/tasks/task/skill-capabilities").queryParam("targetAgentId","agent","other").principal(jwt("owner"))).andExpect(status().isNotFound());
        mvc.perform(get("/agent/tasks/task/skill-capabilities").queryParam("targetAgentId","agent").queryParam("extra","x").principal(jwt("owner"))).andExpect(status().isNotFound());
        verifyNoInteractions(capabilities);
        when(capabilities.taskCatalog(any(),anyString())).thenThrow(new TaskSkillPolicyService.Failure(TaskSkillPolicyService.Failure.Reason.NOT_FOUND));
        mvc.perform(get("/agent/tasks/task/skill-capabilities").queryParam("targetAgentId","agent").principal(jwt("owner")))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void realDependencyFailureIs503AndNeverAnEmptyCallableDirectory() throws Exception {
        when(capabilities.taskCatalog(any(),anyString())).thenThrow(new IllegalStateException("private provider/token/path"));
        mvc.perform(get("/agent/tasks/task/skill-capabilities").queryParam("targetAgentId","agent").principal(jwt("owner")))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("SKILL_CAPABILITIES_503")).andExpect(jsonPath("$.data").doesNotExist());
    }
}
