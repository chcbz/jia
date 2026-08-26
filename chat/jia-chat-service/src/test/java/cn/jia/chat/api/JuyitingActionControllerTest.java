package cn.jia.chat.api;

import cn.jia.chat.service.HallActionDispatchResult;
import cn.jia.chat.service.HallActionDispatcher;
import cn.jia.chat.service.HallActionIntent;
import cn.jia.chat.service.HallDurableMailboxPage;
import cn.jia.chat.service.HallTrustedCaller;
import cn.jia.core.entity.JsonResult;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JuyitingActionControllerTest extends BaseMockTest {
    @Mock
    HallActionDispatcher dispatcher;

    @Test
    void requestDtoRejectsUnknownIdentityAndCredentialFields() {
        HallActionIntent request = new HallActionIntent();

        assertThrows(IllegalArgumentException.class,
                () -> request.rejectUnknownField("tenantId", "tenant-forged"));
        assertThrows(IllegalArgumentException.class,
                () -> request.rejectUnknownField("authorization", "Bearer secret"));
    }

    @Test
    void dispatchesWithJwtScopeAndSeparatelyNominatedCaller() {
        HallActionIntent request = new HallActionIntent();
        request.setActorAgentId("agent-target");
        HallTrustedCaller trusted = new HallTrustedCaller(
                "tenant-a", "client-a", "agent-caller");
        when(dispatcher.dispatch(request, trusted)).thenReturn(
                new HallActionDispatchResult(
                        "intent-1", "agent-target", "accepted", "accepted for durable delivery"));
        JuyitingActionController controller = new JuyitingActionController(dispatcher);

        JsonResult<HallActionDispatchResult> result = controller.dispatch(
                "intent-1", "agent-caller", request,
                jwt("tenant-a", "client-a"));

        assertEquals("intent-1", request.getIntentId());
        verify(dispatcher).dispatch(request, trusted);
        assertEquals("intent-1", result.getData().getIntentId());
        assertEquals("accepted", result.getData().getStatus());
    }

    @Test
    void bodyActorNeverBecomesCallerWhenCallerParameterIsMissing() {
        HallActionIntent request = new HallActionIntent();
        request.setActorAgentId("forged-body-actor");
        when(dispatcher.dispatch(request,
                new HallTrustedCaller("tenant-a", "client-a", null)))
                .thenReturn(new HallActionDispatchResult(
                        "intent-2", "forged-body-actor", "failed",
                        "request is not authorized or valid"));
        JuyitingActionController controller = new JuyitingActionController(dispatcher);

        JsonResult<HallActionDispatchResult> result = controller.dispatch(
                "intent-2", null, request, jwt("tenant-a", "client-a"));

        verify(dispatcher).dispatch(request,
                new HallTrustedCaller("tenant-a", "client-a", null));
        assertEquals("failed", result.getData().getStatus());
    }

    @Test
    void rejectsBodyPathIntentConflictBeforeDispatcher() {
        HallActionIntent request = new HallActionIntent();
        request.setIntentId("intent-body");
        request.setActorAgentId("agent-target");
        JuyitingActionController controller = new JuyitingActionController(dispatcher);

        JsonResult<HallActionDispatchResult> result = controller.dispatch(
                "intent-path", "agent-caller", request,
                jwt("tenant-a", "client-a"));

        assertEquals("failed", result.getData().getStatus());
        verify(dispatcher, never()).dispatch(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidAuthenticationNeverSynthesizesScopeFromRequestFields() {
        HallActionIntent request = new HallActionIntent();
        request.setActorAgentId("agent-target");
        when(dispatcher.dispatch(request, null)).thenReturn(
                new HallActionDispatchResult(
                        "intent-3", "agent-target", "failed",
                        "request is not authorized or valid"));
        JuyitingActionController controller = new JuyitingActionController(dispatcher);

        controller.dispatch("intent-3", "agent-caller", request, null);

        verify(dispatcher).dispatch(request, null);
    }

    @Test
    void listsDurableMailboxWithTrustedScopeAndBoundedQueryParameters() {
        HallDurableMailboxPage page = new HallDurableMailboxPage(List.of(), null, false);
        HallTrustedCaller trusted = new HallTrustedCaller(
                "tenant-a", "client-a", "agent-caller");
        when(dispatcher.mailbox(
                "agent-target", "task-1", "cursor-1", 25, false, trusted))
                .thenReturn(page);
        JuyitingActionController controller = new JuyitingActionController(dispatcher);

        JsonResult<Object> result = controller.mailbox(
                "agent-target", "agent-caller", "task-1", "cursor-1",
                25, false, jwt("tenant-a", "client-a"));

        assertEquals(page, result.getData());
        verify(dispatcher).mailbox(
                "agent-target", "task-1", "cursor-1", 25, false, trusted);
    }

    @Test
    void malformedJwtClaimsPassNoTrustedScope() {
        JuyitingActionController controller = new JuyitingActionController(dispatcher);
        Jwt malformed = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", "tenant-a")
                .claim("client_id", 42)
                .issuedAt(Instant.ofEpochSecond(1))
                .expiresAt(Instant.ofEpochSecond(10_000))
                .build();
        when(dispatcher.mailbox(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.nullable(HallTrustedCaller.class)))
                .thenReturn(new HallDurableMailboxPage(List.of(), null, false));

        controller.mailbox(
                "agent-target", "agent-caller", null, null, 50, false,
                new JwtAuthenticationToken(malformed, List.of()));

        verify(dispatcher).mailbox(
                org.mockito.ArgumentMatchers.eq("agent-target"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(50),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.isNull());
    }

    private JwtAuthenticationToken jwt(String tenantId, String clientId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", tenantId)
                .claim("client_id", clientId)
                .issuedAt(Instant.ofEpochSecond(1))
                .expiresAt(Instant.ofEpochSecond(10_000))
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
