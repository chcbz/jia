package cn.jia.chat.archive.http;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.config.ArchiveQuestionProperties;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.dto.ArchiveQuestionDTO;
import cn.jia.chat.archive.dto.ArchiveQuestionResponderDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.service.ArchiveMutationResult;
import cn.jia.chat.archive.service.ArchivePersonalDataException;
import cn.jia.chat.archive.service.ArchiveQuestionService;
import cn.jia.chat.archive.service.ArchiveQuestionSseService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveQuestionControllerTest {
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void directJwtDerivesExactOwnerAndCreateReturnsStored202BytesUntouched() {
        ArchiveQuestionService service = mock(ArchiveQuestionService.class);
        ArchiveQuestionSseService streams = mock(ArchiveQuestionSseService.class);
        ArchiveQuestionController controller = new ArchiveQuestionController(service, streams, enabledPolicy());
        HttpServletRequest request = mock(HttpServletRequest.class);
        byte[] stored = "{\"status\":200,\"data\":{\"version\":\"1\"}}".getBytes(StandardCharsets.UTF_8);
        when(service.create(any(), any(), any(), any(), any())).thenReturn(
                new ArchiveMutationResult(202, "application/json;charset=UTF-8", stored, true));

        var response = controller.create(ID, "key", "{}".getBytes(StandardCharsets.UTF_8), request,
                jwt(Map.of("jiacn", "owner-a", "client_id", "client-a")));
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertArrayEquals(stored, (byte[]) response.getBody());
        ArgumentCaptor<ArchiveOwnerScope> owner = ArgumentCaptor.forClass(ArchiveOwnerScope.class);
        verify(service).create(owner.capture(), org.mockito.ArgumentMatchers.eq(ID),
                org.mockito.ArgumentMatchers.eq("/archive/v1/me/questions/" + ID),
                org.mockito.ArgumentMatchers.eq("key"), any());
        assertEquals(new ArchiveOwnerScope("owner-a", "client-a", "owner-a"), owner.getValue());
    }

    @Test
    void authSyntaxThenFeatureGateFailClosedBeforeAnyServiceOrStreamAccess() {
        ArchiveQuestionService service = mock(ArchiveQuestionService.class);
        ArchiveQuestionSseService streams = mock(ArchiveQuestionSseService.class);
        ArchiveQuestionController controller = new ArchiveQuestionController(service, streams, disabledPolicy());
        HttpServletRequest request = mock(HttpServletRequest.class);
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "owner-a", "client_id", "client-a"));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.create(ID, "key", new byte[]{'{', '}'}, request,
                new UsernamePasswordAuthenticationToken("x", "x")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.get(ID,
                jwt(Map.of("sub", "owner-a", "aud", List.of("client-a")))).getStatusCode());
        when(request.getQueryString()).thenReturn("route=agent");
        var query = controller.create(ID, "key", new byte[]{'{', '}'}, request, valid);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, query.getStatusCode());
        assertEquals("INVALID_REQUEST_JSON", ((JsonResult<?>) query.getBody()).getCode());
        when(request.getQueryString()).thenReturn(null);
        var key = controller.retry(ID, " ", new byte[]{'{', '}'}, request, valid);
        assertEquals("INVALID_IDEMPOTENCY_KEY", ((JsonResult<?>) key.getBody()).getCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.get(ID, valid).getStatusCode());
        verify(service, never()).get(any(), any());
        verify(service, never()).create(any(), any(), any(), any(), any());
        verify(service, never()).retry(any(), any(), any(), any(), any());
        verify(streams, never()).open(any(), any(), anyLong());
    }

    @Test
    void canonicalLowercaseUuidConcealsMalformedAndNonAsciiPaths() {
        ArchiveQuestionService service = mock(ArchiveQuestionService.class);
        ArchiveQuestionController controller = new ArchiveQuestionController(
                service, mock(ArchiveQuestionSseService.class), enabledPolicy());
        HttpServletRequest request = mock(HttpServletRequest.class);
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "owner-a", "client_id", "client-a"));
        for (String invalid : List.of(ID.toUpperCase(), "水", "123", ID + "/")) {
            assertEquals(HttpStatus.NOT_FOUND,
                    controller.create(invalid, "key", new byte[]{'{', '}'}, request, valid).getStatusCode());
        }
        verify(service, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void lastEventIdIsExactCanonicalDecimalAndInvalidCursorIs400BeforeFeatureGate() {
        ArchiveQuestionService service = mock(ArchiveQuestionService.class);
        ArchiveQuestionSseService streams = mock(ArchiveQuestionSseService.class);
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "owner-a", "client_id", "client-a"));
        ArchiveQuestionController disabled = new ArchiveQuestionController(service, streams, disabledPolicy());
        for (String invalid : List.of("", "01", "-1", "+1", " 1", "1.0", "1e2", "9223372036854775808")) {
            var response = disabled.events(ID, invalid, valid);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), invalid);
            assertEquals("INVALID_EVENT_CURSOR", ((JsonResult<?>) response.getBody()).getCode());
        }
        assertEquals(HttpStatus.NOT_FOUND, disabled.events(ID, null, valid).getStatusCode());
        verify(streams, never()).open(any(), any(), anyLong());

        ArchiveQuestionController enabled = new ArchiveQuestionController(service, streams, enabledPolicy());
        SseEmitter emitter = new SseEmitter();
        when(streams.open(any(), org.mockito.ArgumentMatchers.eq(ID), org.mockito.ArgumentMatchers.eq(Long.MAX_VALUE)))
                .thenReturn(emitter);
        var response = enabled.events(ID, Long.toString(Long.MAX_VALUE), valid);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(emitter, response.getBody());
    }

    @Test
    void getRetryAndStreamMapConcealmentConflictRateAndProviderErrorsExactly() {
        ArchiveQuestionService service = mock(ArchiveQuestionService.class);
        ArchiveQuestionSseService streams = mock(ArchiveQuestionSseService.class);
        ArchiveQuestionController controller = new ArchiveQuestionController(service, streams, enabledPolicy());
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "owner-a", "client_id", "client-a"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(service.get(any(), any())).thenReturn(new ArchiveQuestionDTO(ID, "1", "1", "QUEUED",
                new ArchiveQuestionResponderDTO("archive-clerk-v1", "案卷书吏", "fallback"),
                "q", null, "text", "", 0, null, Instant.EPOCH.toString(), Instant.EPOCH.toString(), null));
        assertEquals(HttpStatus.OK, controller.get(ID, valid).getStatusCode());

        when(service.retry(any(), any(), any(), any(), any())).thenThrow(
                new ArchivePersonalDataException(409, "VERSION_CONFLICT", "conflict", "7"));
        var conflict = controller.retry(ID, "retry", "{}".getBytes(StandardCharsets.UTF_8), request, valid);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(Map.of("currentVersion", "7"), ((JsonResult<?>) conflict.getBody()).getData());

        when(service.create(any(), any(), any(), any(), any())).thenThrow(
                new ArchivePersonalDataException(429, "QUESTION_RATE_LIMITED", "limited"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                controller.create(ID, "create", "{}".getBytes(StandardCharsets.UTF_8), request, valid).getStatusCode());
        when(streams.open(any(), any(), anyLong())).thenThrow(
                new ArchivePersonalDataException(404, "ARCHIVE_RESOURCE_NOT_FOUND", "hidden"));
        assertEquals(HttpStatus.NOT_FOUND, controller.events(ID, "0", valid).getStatusCode());
    }

    private JwtAuthenticationToken jwt(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Map.of("alg", "none"), claims);
        return new JwtAuthenticationToken(jwt);
    }
    private ArchiveQuestionAccessPolicy disabledPolicy() {
        return ArchiveQuestionAccessPolicy.from(new ArchiveQuestionProperties(),
                ArchiveReaderAccessPolicy.from(new ArchiveReaderProperties()));
    }
    private ArchiveQuestionAccessPolicy enabledPolicy() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId("owner-a"); scope.setClientId("client-a");
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scope)));
        ArchiveQuestionProperties question = new ArchiveQuestionProperties();
        question.setEnabled(true);
        return ArchiveQuestionAccessPolicy.from(question, ArchiveReaderAccessPolicy.from(properties));
    }
}
