package cn.jia.chat.archive.http;

import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.service.ArchiveReaderService;
import cn.jia.chat.archive.service.ArchiveRepresentation;
import cn.jia.chat.archive.service.ArchiveResourceNotFoundException;
import cn.jia.chat.archive.service.ArchiveResourceGoneException;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@RestController
@RequestMapping("/archive/v1")
public class ArchiveController {
    private static final String CACHE_CONTROL = "private, no-store";
    private final ArchiveReaderService readerService;
    private final ArchiveReaderAccessPolicy accessPolicy;

    public ArchiveController(ArchiveReaderService readerService, ArchiveReaderAccessPolicy accessPolicy) {
        this.readerService = Objects.requireNonNull(readerService, "readerService");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @GetMapping("/catalog")
    public ResponseEntity<JsonResult<?>> catalog(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> conditionalError = validateConditional(ifNoneMatch);
        if (conditionalError != null) return conditionalError;
        if (!accessPolicy.allows(authorization.tenantId(), authorization.clientId())) return notFound();
        try {
            return render(ifNoneMatch, readerService.catalog());
        } catch (ArchiveResourceNotFoundException unavailable) {
            return notFound();
        }
    }

    @GetMapping("/works")
    public ResponseEntity<JsonResult<?>> works(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit,
            Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> conditionalError = validateConditional(ifNoneMatch);
        if (conditionalError != null) return conditionalError;
        if (!accessPolicy.allows(authorization.tenantId(), authorization.clientId())) return notFound();
        Integer bounded = pageLimit(limit);
        if (bounded == null) return error(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE_PAGE_LIMIT",
                "Archive page limit must be between 1 and 100");
        try {
            return render(ifNoneMatch, readerService.works(authorization.tenantId(),
                    authorization.clientId(), cursor, bounded));
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE_PAGE_CURSOR",
                    "Archive page cursor is invalid for this reader scope");
        }
    }

    @GetMapping("/works/{workId}/catalog")
    public ResponseEntity<JsonResult<?>> workCatalog(
            @PathVariable String workId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) {
        return catalogById(workId, ifNoneMatch, authentication, true);
    }

    @GetMapping("/editions/{editionId}/catalog")
    public ResponseEntity<JsonResult<?>> editionCatalog(
            @PathVariable String editionId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) {
        return catalogById(editionId, ifNoneMatch, authentication, false);
    }

    private ResponseEntity<JsonResult<?>> catalogById(String id, String ifNoneMatch,
                                                       Authentication authentication, boolean work) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> conditionalError = validateConditional(ifNoneMatch);
        if (conditionalError != null) return conditionalError;
        if (!validIdentifier(id, work ? 64 : 96)) return notFound();
        if (!accessPolicy.allows(authorization.tenantId(), authorization.clientId())) return notFound();
        try {
            return render(ifNoneMatch, work ? readerService.workCatalog(id) : readerService.editionCatalog(id));
        } catch (ArchiveResourceGoneException withdrawn) {
            return gone();
        } catch (ArchiveResourceNotFoundException unavailable) {
            return notFound();
        }
    }

    @GetMapping("/editions/{editionId}/preface")
    public ResponseEntity<JsonResult<?>> preface(
            @PathVariable String editionId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> conditionalError = validateConditional(ifNoneMatch);
        if (conditionalError != null) return conditionalError;
        if (!validIdentifier(editionId, 96)) return notFound();
        if (!accessPolicy.allows(authorization.tenantId(), authorization.clientId())) return notFound();
        try {
            return render(ifNoneMatch, readerService.preface(editionId));
        } catch (ArchiveResourceGoneException withdrawn) {
            return gone();
        } catch (ArchiveResourceNotFoundException unavailable) {
            return notFound();
        }
    }

    @GetMapping("/editions/{editionId}/chapters/{chapterId}")
    public ResponseEntity<JsonResult<?>> chapter(
            @PathVariable String editionId,
            @PathVariable String chapterId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> conditionalError = validateConditional(ifNoneMatch);
        if (conditionalError != null) return conditionalError;
        if (!validIdentifier(editionId, 96) || !validIdentifier(chapterId, 128)) return notFound();
        if (!accessPolicy.allows(authorization.tenantId(), authorization.clientId())) return notFound();
        try {
            return render(ifNoneMatch, readerService.chapter(editionId, chapterId));
        } catch (ArchiveResourceGoneException withdrawn) {
            return gone();
        } catch (ArchiveResourceNotFoundException unavailable) {
            return notFound();
        }
    }


    private Integer pageLimit(String value) {
        if (value == null) return 100;
        if (!value.matches("[1-9][0-9]{0,2}")) return null;
        int parsed = Integer.parseInt(value);
        return parsed <= 100 ? parsed : null;
    }

    private ResponseEntity<JsonResult<?>> validateConditional(String ifNoneMatch) {
        try {
            IfNoneMatch.validate(ifNoneMatch);
            return null;
        } catch (InvalidConditionalHeaderException malformed) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_CONDITIONAL_HEADER",
                    "If-None-Match header is malformed");
        }
    }

    private ResponseEntity<JsonResult<?>> render(String ifNoneMatch, ArchiveRepresentation<?> representation) {
        try {
            IfNoneMatch.validate(ifNoneMatch);
            if (IfNoneMatch.matches(ifNoneMatch, representation.etag())) {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.ETAG, representation.etag());
                headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
                return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
            }
        } catch (InvalidConditionalHeaderException malformed) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_CONDITIONAL_HEADER",
                    "If-None-Match header is malformed");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, representation.etag())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(JsonResult.success(representation.data()));
    }

    private Authorization authorize(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
            return Authorization.failure(authIncomplete());
        }
        Object tenantClaim = jwtAuthenticationToken.getToken().getClaims().get("jiacn");
        Object clientClaim = jwtAuthenticationToken.getToken().getClaims().get("client_id");
        if (!(tenantClaim instanceof String tenantId) || !(clientClaim instanceof String clientId)
                || !ArchiveReaderAccessPolicy.validRequestClaim(tenantId)
                || !ArchiveReaderAccessPolicy.validRequestClaim(clientId)) {
            return Authorization.failure(authIncomplete());
        }
        return new Authorization(tenantId, clientId, null);
    }

    private boolean validIdentifier(String value, int maxUtf8Bytes) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.getBytes(StandardCharsets.UTF_8).length <= maxUtf8Bytes
                && value.chars().noneMatch(Character::isISOControl);
    }

    private ResponseEntity<JsonResult<?>> authIncomplete() {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_CONTEXT_INCOMPLETE",
                "Archive authentication context is incomplete");
    }

    private ResponseEntity<JsonResult<?>> gone() {
        return error(HttpStatus.GONE, "ARCHIVE_EDITION_WITHDRAWN",
                "Archive edition was withdrawn");
    }

    private ResponseEntity<JsonResult<?>> notFound() {
        return error(HttpStatus.NOT_FOUND, "ARCHIVE_RESOURCE_NOT_FOUND",
                "Archive resource is not available");
    }

    private ResponseEntity<JsonResult<?>> error(HttpStatus status, String code, String message) {
        JsonResult<Void> result = JsonResult.failure(code, message);
        result.setStatus(status.value());
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(result);
    }

    private record Authorization(String tenantId, String clientId,
                                 ResponseEntity<JsonResult<?>> error) {
        private static Authorization failure(ResponseEntity<JsonResult<?>> error) {
            return new Authorization(null, null, error);
        }
    }
}
