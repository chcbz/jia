package cn.jia.agent.api;

import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallRequestDraftService.OwnerScope;
import cn.jia.core.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HallReadTenantScopeTest {
    @Test
    void overviewUsesOnlyTrustedLocalScopeAndRejectsClaimOrQueryOverrides() throws Exception {
        var service=mock(HallReadService.class);
        var empty=new HallReadService.Section("complete",Map.of("task",new HallReadService.Partition(List.of(),"complete",null,null,null)));
        when(service.overview(new OwnerScope("1","cyf-local","owner-a"))).thenReturn(
                new HallReadService.Overview(1,Map.of("recent",empty),Map.of(),1000));
        var mvc=MockMvcBuilders.standaloneSetup(new HallReadController(service))
                .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(),new cn.jia.core.config.ExceptionHandlerAdvice())
                .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
        TrustedTenantContext.runWithScopeChecked(new BusinessScope(TenantId.of("1"),DataDomainId.of("cyf"),"owner-a","cyf-local"),()->{
            mvc.perform(get("/agent/hall/overview").principal(jwt("1"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("E0"));
            mvc.perform(get("/agent/hall/overview").principal(jwt("0"))).andExpect(status().isForbidden());
            mvc.perform(get("/agent/hall/overview?tenant_id=0").principal(jwt("1"))).andExpect(status().isBadRequest());
        });
        verify(service,times(1)).overview(new OwnerScope("1","cyf-local","owner-a"));
        verifyNoMoreInteractions(service);
    }
    private static JwtAuthenticationToken jwt(String tenant) {
        var auth=new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","none").subject("user")
                .claim("tenant_id",tenant).claim("jiacn","owner-a").claim("client_id","cyf-local").build());
        auth.setAuthenticated(true);return auth;
    }
}
