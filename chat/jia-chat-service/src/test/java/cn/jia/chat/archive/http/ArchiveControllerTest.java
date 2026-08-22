package cn.jia.chat.archive.http;

import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.dto.ArchiveCatalogDTO;
import cn.jia.chat.archive.service.ArchiveReaderService;
import cn.jia.chat.archive.service.ArchiveRepresentation;
import cn.jia.core.entity.JsonResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveControllerTest {
    @Test
    void directJwtClaimsAreRequiredBeforeFeatureGateOrContentLookup() {
        ArchiveReaderService service = mock(ArchiveReaderService.class);
        ArchiveController controller = new ArchiveController(service, disabledPolicy());

        var nonJwt = controller.catalog(null,
                new UsernamePasswordAuthenticationToken("tenant-a", "n/a"));
        assertEquals(HttpStatus.UNAUTHORIZED, nonJwt.getStatusCode());
        assertEquals("AUTH_CONTEXT_INCOMPLETE", nonJwt.getBody().getCode());

        var aliasesOnly = controller.catalog(null, jwt(Map.of(
                "username", "tenant-a", "sub", "tenant-a", "aud", List.of("client-a"))));
        assertEquals(HttpStatus.UNAUTHORIZED, aliasesOnly.getStatusCode());
        assertEquals("AUTH_CONTEXT_INCOMPLETE", aliasesOnly.getBody().getCode());

        var disabled = controller.catalog(null, jwt(Map.of("jiacn", "tenant-a", "client_id", "client-a")));
        assertEquals(HttpStatus.NOT_FOUND, disabled.getStatusCode());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", disabled.getBody().getCode());
        verify(service, never()).catalog();
    }

    @Test
    void catalogUsesFrozenWeakValidatorAndReturnsBodyless304() {
        ArchiveReaderService service = mock(ArchiveReaderService.class);
        ArchiveCatalogDTO data = mock(ArchiveCatalogDTO.class);
        String etag = "W/\"archive-catalog-json-v1-current\"";
        when(service.catalog()).thenReturn(new ArchiveRepresentation<>(etag, data));
        ArchiveController controller = new ArchiveController(service, enabledPolicy());
        JwtAuthenticationToken authentication = jwt(Map.of("jiacn", "tenant-a", "client_id", "client-a"));

        var ok = controller.catalog("W/\"stale\"", authentication);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals(etag, ok.getHeaders().getETag());
        assertEquals("private, no-store", ok.getHeaders().getCacheControl());
        assertEquals(data, ((JsonResult<?>) ok.getBody()).getData());

        var notModified = controller.catalog("\"archive-catalog-json-v1-current\"", authentication);
        assertEquals(HttpStatus.NOT_MODIFIED, notModified.getStatusCode());
        assertEquals(etag, notModified.getHeaders().getETag());
        assertNull(notModified.getBody());
    }

    @Test
    void malformedConditionalAndUnknownIdsUseFrozenErrors() {
        ArchiveReaderService service = mock(ArchiveReaderService.class);
        ArchiveController controller = new ArchiveController(service, enabledPolicy());
        JwtAuthenticationToken authentication = jwt(Map.of("jiacn", "tenant-a", "client_id", "client-a"));

        var malformed = controller.catalog("W/\"bad tag\"", authentication);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertEquals("INVALID_CONDITIONAL_HEADER", malformed.getBody().getCode());

        var unknown = controller.chapter("shuihuzhuan-zh-120-v1 ", "chapter", null, authentication);
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", unknown.getBody().getCode());
        verify(service, never()).chapter("shuihuzhuan-zh-120-v1 ", "chapter");
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
        scope.setTenantId("tenant-a");
        scope.setClientId("client-a");
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scope)));
        return ArchiveReaderAccessPolicy.from(properties);
    }
}
