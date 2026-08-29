package cn.jia.chat.archive.http;

import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.dto.ArchivePageDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.service.ArchiveMutationResult;
import cn.jia.chat.archive.service.ArchivePersonalDataService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArchivePersonalDataControllerTest {
    private static final String EDITION = "shuihuzhuan-zh-120-v1";
    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void directJwtOnlyDerivesExactOwnerTupleWithoutAliasFallback() {
        ArchivePersonalDataService service = mock(ArchivePersonalDataService.class);
        ArchivePersonalDataController controller = new ArchivePersonalDataController(service, enabledPolicy());

        assertEquals(HttpStatus.UNAUTHORIZED, controller.progress(EDITION,
                new UsernamePasswordAuthenticationToken("tenant-a", "n/a")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.progress(EDITION,
                jwt(Map.of("sub", "tenant-a", "aud", List.of("client-a")))).getStatusCode());

        controller.progress(EDITION, jwt(Map.of("jiacn", "owner-a", "client_id", "client-a")));
        ArgumentCaptor<ArchiveOwnerScope> owner = ArgumentCaptor.forClass(ArchiveOwnerScope.class);
        verify(service).progress(owner.capture(), org.mockito.ArgumentMatchers.eq(EDITION));
        assertEquals(new ArchiveOwnerScope("owner-a", "client-a", "owner-a"), owner.getValue());
    }

    @Test
    void authThenSyntaxThenFeatureGatePreventsServiceReadsAndWrites() {
        ArchivePersonalDataService service = mock(ArchivePersonalDataService.class);
        ArchivePersonalDataController controller = new ArchivePersonalDataController(service, disabledPolicy());
        HttpServletRequest request = mock(HttpServletRequest.class);
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "tenant-a", "client_id", "client-a"));

        var missingAuth = controller.putBookmark(ID, "key", new byte[]{'{', '}'}, request,
                new UsernamePasswordAuthenticationToken("x", "x"));
        assertEquals(HttpStatus.UNAUTHORIZED, missingAuth.getStatusCode());

        when(request.getQueryString()).thenReturn("x=1");
        var malformed = controller.putBookmark(ID, "key", new byte[]{'{', '}'}, request, valid);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, malformed.getStatusCode());
        assertEquals("INVALID_REQUEST_JSON", ((JsonResult<?>) malformed.getBody()).getCode());

        when(request.getQueryString()).thenReturn(null);
        var disabled = controller.putBookmark(ID, "key", new byte[]{'{', '}'}, request, valid);
        assertEquals(HttpStatus.NOT_FOUND, disabled.getStatusCode());
        verify(service, never()).putBookmark(any(), any(), any(), any(), any());
    }

    @Test
    void nonAsciiProgressIdentifierIsConcealedBeforeServiceAtControllerAndMvcLayers() throws Exception {
        ArchivePersonalDataService service = mock(ArchivePersonalDataService.class);
        ArchivePersonalDataController controller = new ArchivePersonalDataController(service, enabledPolicy());
        HttpServletRequest request = mock(HttpServletRequest.class);
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "owner-a", "client_id", "client-a"));
        when(service.putProgress(any(), org.mockito.ArgumentMatchers.eq("水"), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("non-ASCII path reached SQL"));
        when(service.putProgress(any(), org.mockito.ArgumentMatchers.eq(EDITION),
                org.mockito.ArgumentMatchers.eq("/archive/v1/me/progress/" + EDITION),
                org.mockito.ArgumentMatchers.eq("valid-key"), any()))
                .thenReturn(new ArchiveMutationResult(
                        200, "application/json;charset=UTF-8", new byte[]{'{', '}'}, false));
        when(service.notes(any(), org.mockito.ArgumentMatchers.eq(EDITION),
                org.mockito.ArgumentMatchers.eq(EDITION + "-c001"), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new ArchivePageDTO<>(List.of(), null));

        var direct = assertDoesNotThrow(() -> controller.putProgress(
                "水", "unicode-key", new byte[]{'{', '}'}, request, valid));
        assertEquals(HttpStatus.NOT_FOUND, direct.getStatusCode());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", ((JsonResult<?>) direct.getBody()).getCode());
        verify(service, never()).putProgress(any(), org.mockito.ArgumentMatchers.eq("水"), any(), any(), any());

        assertEquals(HttpStatus.OK, controller.putProgress(
                EDITION, "valid-key", new byte[]{'{', '}'}, request, valid).getStatusCode());
        assertEquals(HttpStatus.OK,
                controller.notes(EDITION, EDITION + "-c001", null, null, valid).getStatusCode());

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(put("/archive/v1/me/progress/{editionId}", "水")
                        .principal(valid)
                        .header("Idempotency-Key", "mvc-unicode-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARCHIVE_RESOURCE_NOT_FOUND"));
        verify(service, never()).putProgress(any(), org.mockito.ArgumentMatchers.eq("水"), any(), any(), any());
    }

    @Test
    void deleteAcceptsOnlyExactStrongSingleVersionAndSuccessfulReplayBytesAreUntouched() {
        ArchivePersonalDataService service = mock(ArchivePersonalDataService.class);
        ArchivePersonalDataController controller = new ArchivePersonalDataController(service, enabledPolicy());
        HttpServletRequest request = mock(HttpServletRequest.class);
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "tenant-a", "client_id", "client-a"));

        for (String invalid : new String[]{null, "W/\"v1\"", "*", "\"v1\", \"v2\"", " \"v1\"", "\"v01\""}) {
            var response = controller.deleteNote(ID, invalid, "key", request, valid);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode(), String.valueOf(invalid));
            assertEquals("INVALID_VERSION", ((JsonResult<?>) response.getBody()).getCode());
        }

        byte[] stored = "{\"status\":200,\"data\":{\"version\":\"2\"}}".getBytes(StandardCharsets.UTF_8);
        when(service.deleteNote(any(), org.mockito.ArgumentMatchers.eq(ID), org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.eq("/archive/v1/me/notes/" + ID),
                org.mockito.ArgumentMatchers.eq("key")))
                .thenReturn(new ArchiveMutationResult(200, "application/json;charset=UTF-8", stored, true));
        var response = controller.deleteNote(ID, "\"v1\"", "key", request, valid);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(stored, (byte[]) response.getBody());
    }

    @Test
    void uuidSyntaxAcceptsVersion8AndFutureHexButRejectsUppercase() {
        ArchivePersonalDataService service = mock(ArchivePersonalDataService.class);
        ArchivePersonalDataController controller = new ArchivePersonalDataController(service, enabledPolicy());
        HttpServletRequest request = mock(HttpServletRequest.class);
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "owner-a", "client_id", "client-a"));
        when(service.putBookmark(any(), any(), any(), any(), any()))
                .thenReturn(new ArchiveMutationResult(200, "application/json;charset=UTF-8", new byte[]{'{', '}'}, false));

        for (String id : List.of("123e4567-e89b-82d3-a456-426614174000",
                "123e4567-e89b-f2d3-a456-426614174000")) {
            assertEquals(HttpStatus.OK,
                    controller.putBookmark(id, "key-" + id.charAt(14), new byte[]{'{', '}'}, request, valid)
                            .getStatusCode());
        }
        assertEquals(HttpStatus.NOT_FOUND,
                controller.putBookmark("123E4567-e89b-82d3-a456-426614174000", "key", new byte[]{'{', '}'},
                        request, valid).getStatusCode());
    }

    @Test
    void listSyntaxIsCanonicalAndCheckedBeforeDisabledGate() {
        ArchivePersonalDataService service = mock(ArchivePersonalDataService.class);
        ArchivePersonalDataController controller = new ArchivePersonalDataController(service, disabledPolicy());
        JwtAuthenticationToken valid = jwt(Map.of("jiacn", "tenant-a", "client_id", "client-a"));

        var invalidCursor = controller.bookmarks(EDITION, "01", "100", valid);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalidCursor.getStatusCode());
        var invalidLimit = controller.notes(EDITION, null, null, "101", valid);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalidLimit.getStatusCode());
        var disabled = controller.bookmarks(EDITION, null, null, valid);
        assertEquals(HttpStatus.NOT_FOUND, disabled.getStatusCode());
        verify(service, never()).bookmarks(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private JwtAuthenticationToken jwt(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Map.of("alg", "none"), claims);
        return new JwtAuthenticationToken(jwt);
    }

    private ArchiveReaderAccessPolicy disabledPolicy() {
        return ArchiveReaderAccessPolicy.from(new ArchiveReaderProperties());
    }

    private ArchiveReaderAccessPolicy enabledPolicy() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId("owner-a");
        scope.setClientId("client-a");
        ArchiveReaderProperties.AllowedScope second = new ArchiveReaderProperties.AllowedScope();
        second.setTenantId("tenant-a");
        second.setClientId("client-a");
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scope, second)));
        return ArchiveReaderAccessPolicy.from(properties);
    }
}
