package cn.jia.chat.api;

import cn.jia.chat.service.ChatBountyRequestIndexService;
import cn.jia.chat.service.DisplayNameSource;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatBountyRequestIndexControllerTest {
    private ChatBountyRequestIndexService index;
    private HumanSenderIdentityResolver identities;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        index = mock(ChatBountyRequestIndexService.class);
        identities = mock(HumanSenderIdentityResolver.class);
        var controller = new ChatBountyRequestIndexController(index, identities,
                TenantScopeResolver.legacySingleTenant());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        EsContext context = new EsContext(); context.setTenantId("0"); context.setJiacn("owner");
        context.setClientId("client"); EsContextHolder.setContext(context);
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender(
                "user", "Owner", "owner", "client", DisplayNameSource.JIACN));
    }

    @AfterEach void clear() { EsContextHolder.clearContext(); }

    @Test void realMvcReturnsOnlyAllowlistedEnvelopeFromAuthenticatedServerIdentity() throws Exception {
        var request = new cn.jia.chat.service.ChatDeliberationService.RequestView("request-1", "1",
                "42", "7", "101", "COMPLETED", "2", List.of(), List.of());
        var page = new ChatBountyRequestIndexService.Page(1,
                new ChatBountyRequestIndexService.Scope("42", "7", "task-1"),
                "0", "12", null, false,
                List.of(new ChatBountyRequestIndexService.Entry("12", request)));
        when(index.read(eq("0"), eq("owner"), eq("client"), eq("42"), any())).thenReturn(page);
        mvc.perform(get("/chat/conversations/42/requests")
                        .queryParam("pageSize", "999999999999999999999999999")
                        .principal(jwt("owner", "client")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.scope.conversationId").value("42"))
                .andExpect(jsonPath("$.data.scope.conversationGeneration").value("7"))
                .andExpect(jsonPath("$.data.scope.taskId").value("task-1"))
                .andExpect(jsonPath("$.data.scope.owner").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].ordinal").value("12"))
                .andExpect(jsonPath("$.data.entries[0].request.requestId").value("request-1"));
        verify(index).read(eq("0"), eq("owner"), eq("client"), eq("42"),
                argThat(query -> "999999999999999999999999999".equals(query.pageSize())));
    }


    @Test void jwtAndEsContextOwnerClientMustMatchBeforeAnyCatalogueRead() throws Exception {
        mvc.perform(get("/chat/conversations/42/requests").principal(jwt("other-owner", "client")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("BOUNTY_REQUEST_INDEX_NOT_FOUND_OR_FORBIDDEN"));
        mvc.perform(get("/chat/conversations/42/requests").principal(jwt("owner", "other-client")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(index);
    }

    @Test void duplicateUnknownAndNonEmptyGetBodyAreTypedBadRequestsWithoutServiceRead() throws Exception {
        for (var request : List.of(
                get("/chat/conversations/42/requests").queryParam("after", "0", "1"),
                get("/chat/conversations/42/requests").queryParam("owner", "other"),
                get("/chat/conversations/42/requests").content("{}"))) {
            mvc.perform(request.principal(jwt("owner", "client")))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(jsonPath("$.code").value("BOUNTY_REQUEST_INDEX_INVALID_REQUEST"));
        }
        verifyNoInteractions(index);
    }

    @Test void typedConflictForbiddenAndUnavailableNeverLeakDependencyMessages() throws Exception {
        for (var reason : ChatBountyRequestIndexService.Reason.values()) {
            reset(index);
            when(index.read(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenThrow(new ChatBountyRequestIndexService.Failure(reason,
                            new IllegalStateException("/private/provider/path")));
            int status = switch (reason) {
                case INVALID_REQUEST -> 400;
                case NOT_FOUND_OR_FORBIDDEN -> 404;
                case CONFLICT -> 409;
                case UNAVAILABLE -> 503;
            };
            mvc.perform(get("/chat/conversations/42/requests").principal(jwt("owner", "client")))
                    .andExpect(status().is(status))
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(jsonPath("$.code").value("BOUNTY_REQUEST_INDEX_" + reason))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("provider/path"))));
        }
    }

    @Test void controllerIsExplicitlyDefaultOffOnTheExistingBountyMediaFlag() {
        for (Class<?> type : List.of(ChatBountyRequestIndexController.class,
                ChatBountyRequestIndexService.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(condition, type.getName());
            assertEquals("chat.bounty-media", condition.prefix());
            assertArrayEquals(new String[]{"enabled"}, condition.name());
            assertEquals("true", condition.havingValue());
            assertFalse(condition.matchIfMissing());
        }
    }

    private static JwtAuthenticationToken jwt(String owner, String client) {
        Jwt token = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("tenant_id", "0").claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(token, List.of());
        authentication.setAuthenticated(true);
        return authentication;
    }
}
