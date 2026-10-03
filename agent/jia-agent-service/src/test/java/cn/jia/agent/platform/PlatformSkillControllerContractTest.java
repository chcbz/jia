package cn.jia.agent.platform;

import cn.jia.core.security.SensitiveResponseBodyAdvice;
import cn.jia.core.security.SensitiveResponseProperties;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import static cn.jia.agent.platform.PlatformInstallationStore.Scope;
import static cn.jia.agent.platform.PlatformSkillInstallationService.Actor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PlatformSkillControllerContractTest {
    @Test void catalogRouteIsAuthenticatedNoQueryPrivateAndExactlySanitized() throws Exception {
        var service=mock(PlatformSkillInstallationService.class);
        var expected=new PlatformSkillCatalogView("archive-maintainer","1.0.0",
                PlatformSkillCatalog.APPROVED_RELEASE_SHA256,"archive-maintainer/utf8-exact-v1");
        var actor=new Actor("user-a",new Scope("0","client-a","owner-a"));
        when(service.catalog(actor)).thenReturn(List.of(expected));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new PlatformSkillController(service))
                .setControllerAdvice(new SensitiveResponseBodyAdvice(new SensitiveResponseProperties()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
                .build();

        var response=mvc.perform(get("/agent/platform-skills/catalog").principal(jwt("user-a","client-a","owner-a")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("archive-maintainer"))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].packageSha256").value(PlatformSkillCatalog.APPROVED_RELEASE_SHA256))
                .andExpect(jsonPath("$[0].protocol").value("archive-maintainer/utf8-exact-v1"))
                .andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("E999"));
        assertArrayEquals(new String[]{"key","version","packageSha256","protocol"},
                Arrays.stream(PlatformSkillCatalogView.class.getRecordComponents()).map(c->c.getName()).toArray(String[]::new));
        verify(service).catalog(actor);
    }
    @Test void catalogRouteRejectsUnauthenticatedWrongActorAndAnyQueryBeforeMetadataRead() throws Exception {
        var service=mock(PlatformSkillInstallationService.class);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new PlatformSkillController(service)).build();
        var unauthenticated=jwt("user-a","client-a","owner-a");unauthenticated.setAuthenticated(false);
        mvc.perform(get("/agent/platform-skills/catalog")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PLATFORM_SKILL_UNAUTHENTICATED"));
        mvc.perform(get("/agent/platform-skills/catalog").principal(unauthenticated)).andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/platform-skills/catalog?include=bytes").principal(jwt("user-a","client-a","owner-a")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLATFORM_SKILL_REQUEST_INVALID"));
        mvc.perform(get("/agent/platform-skills/catalog").principal(jwt(null,"client-a","owner-a")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLATFORM_SKILL_FORBIDDEN"));
        verifyNoInteractions(service);
    }
    @Test void catalogControllerKeepsSingleServiceConstructorAndDefaultOffGate() {
        var constructors=PlatformSkillController.class.getDeclaredConstructors();
        assertEquals(1,constructors.length);
        assertArrayEquals(new Class<?>[]{PlatformSkillInstallationService.class},constructors[0].getParameterTypes());
        var gate=PlatformSkillController.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate);assertEquals("agent.platform-skills",gate.prefix());
        assertArrayEquals(new String[]{"enabled"},gate.name());assertEquals("true",gate.havingValue());assertFalse(gate.matchIfMissing());
    }
    @Test void requestDoesNotCoerceBindingOrPermitClientSelectedOwnerOrUrl() {
        String good="{\"agentId\":\"agt_1\",\"bindingVersion\":\"17\",\"skillKey\":\"archive-maintainer\",\"skillVersion\":\"1.0.0\"}";
        assertEquals("17",PlatformSkillController.decodeRequest(bytes(good)).bindingVersion());
        for(String bad:List.of(good.replace("\"17\"","17"),good.replace("\"agentId\"","\"owner\""),good+"{}",
                good.replace("\"bindingVersion\":\"17\"","\"bindingVersion\":\"17\",\"bindingVersion\":\"17\"")))
            assertThrows(PlatformSkillException.class,()->PlatformSkillController.decodeRequest(bytes(bad)));
    }
    @Test void receiptRejectsStringOrFractionalAttemptUnknownFieldsAndMissingErrorField() {
        String good="{\"schemaVersion\":1,\"installationId\":\"psi_1\",\"commandId\":\"cmd_1\",\"attempt\":1,\"executionEpoch\":\"1\",\"challengeId\":\"psc_1\",\"packageSha256\":\""+"a".repeat(64)+"\",\"outcome\":\"SUCCEEDED\",\"errorCode\":null}";
        assertEquals(1,PlatformSkillController.decodeResult(bytes(good)).attempt());
        for(String bad:List.of(good.replace("\"attempt\":1","\"attempt\":\"1\""),good.replace("\"attempt\":1","\"attempt\":1.1"),
                good.replace("\"executionEpoch\":\"1\"","\"executionEpoch\":1"),good.replace(",\"errorCode\":null",""),
                good.replace("\"errorCode\":null","\"errorCode\":null,\"tenantId\":\"0\"")))
            assertThrows(PlatformSkillException.class,()->PlatformSkillController.decodeResult(bytes(bad)));
    }
    @Test void nativeLaneIsExactAndCannotReachOldMarketplaceOrAdmin() {
        String root="/internal/agent/platform-skills/installations/psi_1";
        assertTrue(allowed("GET",root+"/package"));assertTrue(allowed("POST",root+"/result"));
        for(String path:List.of(root+"/result/extra",root+"/package?x",root.replace("psi_1","%70si_1")+"/package",
                root.replace("psi_1","../psi_1")+"/package","/agent/platform-skills/installations/psi_1","/internal/agent/skill-installations/psi_1/package"))
            assertFalse(allowed("GET",path));
        assertFalse(allowed("POST",root+"/package"));assertFalse(allowed("GET",root+"/result"));
    }
    @Test void packageIsApprovedReleaseResourceNotCommercialEntitlement() {
        var a=new PlatformSkillCatalog();var b=new PlatformSkillCatalog();
        assertArrayEquals(a.packageBytes("archive-maintainer","1.0.0",a.sha256()),b.packageBytes("archive-maintainer","1.0.0",b.sha256()));
        assertThrows(PlatformSkillException.class,()->a.packageBytes("other","1.0.0",a.sha256()));
        assertThrows(PlatformSkillException.class,()->a.packageBytes("archive-maintainer","1.0.0","f".repeat(64)));
    }
    static JwtAuthenticationToken jwt(String subject,String client,String owner) {
        Jwt.Builder token=Jwt.withTokenValue("fixture").header("alg","none")
                .claim("client_id",client).claim("jiacn",owner)
                .issuedAt(Instant.ofEpochSecond(1)).expiresAt(Instant.ofEpochSecond(2));
        if(subject!=null) token.subject(subject);
        var jwt=token.build();
        var authentication=subject==null ? new JwtAuthenticationToken(jwt,List.of(),"untrusted-name")
                : new JwtAuthenticationToken(jwt,List.of());
        authentication.setAuthenticated(true);
        return authentication;
    }
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static boolean allowed(String method,String path){return AgentRuntimeAuthenticationFilter.allowed(new MockHttpServletRequest(method,path));}
}
