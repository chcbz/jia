package cn.jia.agent.api;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.service.HostingRentAdmissionException;
import cn.jia.agent.service.HostingRentAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentHostingRentControllerTest {
    private static final String PATH = "/agent/personas/wuyong/hosting-rent/quotes";
    private HostingRentAdmissionService admissionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        admissionService = mock(HostingRentAdmissionService.class);
        mvc = mvc(admissionService);
    }

    @Test
    void exactJwtSubjectTenantAndClientAreTheOnlyForwardedIdentity() throws Exception {
        doThrow(new HostingRentAdmissionException(
                HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED))
                .when(admissionService).requireQuoteAvailable(any(), anyString());

        mvc.perform(post(PATH).principal(jwt("actor-1", "Tenant-A", "Client-A"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("HOSTING_RENT_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.status").value(503));

        ArgumentCaptor<HostingRentAdmissionService.Principal> principal =
                ArgumentCaptor.forClass(HostingRentAdmissionService.Principal.class);
        verify(admissionService).requireQuoteAvailable(principal.capture(),
                org.mockito.ArgumentMatchers.eq("wuyong"));
        assertEquals("actor-1", principal.getValue().actorId());
        assertEquals("Tenant-A", principal.getValue().tenantId());
        assertEquals("Client-A", principal.getValue().clientId());
    }

    @Test
    void absentAndNonJwtAuthenticationFailClosedBeforeAdmissionService() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HOSTING_RENT_UNAUTHENTICATED"));

        var nonJwt = new UsernamePasswordAuthenticationToken("actor-1", "ignored", List.of());
        mvc.perform(post(PATH).principal(nonJwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("HOSTING_RENT_UNAUTHENTICATED"));

        verify(admissionService, never()).requireQuoteAvailable(any(), anyString());
    }

    @Test
    void missingMalformedOrFallbackIdentityClaimsAreForbidden() throws Exception {
        for (JwtAuthenticationToken authentication : List.of(
                jwt(null, "Tenant-A", "Client-A"),
                jwt(" actor-1", "Tenant-A", "Client-A"),
                jwt("actor-1", null, "Client-A"),
                jwt("actor-1", "Tenant-A", null),
                jwtWithFallbackClaims())) {
            mvc.perform(post(PATH).principal(authentication)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("HOSTING_RENT_FORBIDDEN"));
        }
        verify(admissionService, never()).requireQuoteAvailable(any(), anyString());
    }

    @Test
    void quoteBodyMustBeOnlyOneEmptyJsonObjectWithNoQueryParameters() throws Exception {
        JwtAuthenticationToken authentication = jwt("actor-1", "Tenant-A", "Client-A");
        for (String body : List.of(
                "", "null", "[]", "{\"amountMicro\":\"0\"}",
                "{\"actor\":\"forged\"}", "{} {}", "{\"x\":1,\"x\":2}")) {
            mvc.perform(post(PATH).principal(authentication)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(post(PATH + "?planVersion=rent-v1").principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verify(admissionService, never()).requireQuoteAvailable(any(), anyString());
    }

    @Test
    void configuredPlanStillReturnsNotReadyAndNeverInventsQuote() throws Exception {
        HostingRentAdmissionService configured = new HostingRentAdmissionService(
                new AgentHostingRentProperties(true, "rent-v1", "1000000", "86400"));

        mvc(configured).perform(post(PATH).principal(jwt("actor-1", "Tenant-A", "Client-A"))
                        .contentType(MediaType.APPLICATION_JSON).content(" { } "))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("HOSTING_RENT_NOT_READY"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static MockMvc mvc(HostingRentAdmissionService service) {
        return MockMvcBuilders.standaloneSetup(new AgentHostingRentController(service)).build();
    }

    private static JwtAuthenticationToken jwt(String actor, String tenant, String client) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (actor != null) builder.subject(actor);
        if (tenant != null) builder.claim("jiacn", tenant);
        if (client != null) builder.claim("client_id", client);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    private static JwtAuthenticationToken jwtWithFallbackClaims() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                .claim("uid", "actor-1")
                .claim("token_kind", "user")
                .claim("jiacn", "Tenant-A")
                .claim("client_id", "Client-A")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
