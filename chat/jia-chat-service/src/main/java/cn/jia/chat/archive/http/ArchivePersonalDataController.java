package cn.jia.chat.archive.http;

import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.dto.ArchiveBookmarkDTO;
import cn.jia.chat.archive.dto.ArchiveNoteDTO;
import cn.jia.chat.archive.dto.ArchivePageDTO;
import cn.jia.chat.archive.dto.ArchiveProgressDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.service.ArchiveMutationResult;
import cn.jia.chat.archive.service.ArchivePersonalDataException;
import cn.jia.chat.archive.service.ArchivePersonalDataService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/archive/v1/me")
public class ArchivePersonalDataController {
    private static final String CACHE_CONTROL = "private, no-store";
    private final ArchivePersonalDataService service;
    private final ArchiveReaderAccessPolicy accessPolicy;

    public ArchivePersonalDataController(ArchivePersonalDataService service,
                                         ArchiveReaderAccessPolicy accessPolicy) {
        this.service = Objects.requireNonNull(service, "service");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @GetMapping("/progress/{editionId}")
    public ResponseEntity<JsonResult<?>> progress(@PathVariable String editionId,
                                                   Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        if (!identifier(editionId, 96)) return notFound();
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(JsonResult.success(service.progress(authorization.owner(), editionId)));
    }

    @PutMapping("/progress/{editionId}")
    public ResponseEntity<?> putProgress(@PathVariable String editionId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestBody(required = false) byte[] body,
                                         HttpServletRequest request,
                                         Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> syntax = mutationSyntax(request, key, identifier(editionId, 96));
        if (syntax != null) return syntax;
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        return mutate(() -> service.putProgress(authorization.owner(), editionId,
                "/archive/v1/me/progress/" + editionId, key, body));
    }

    @GetMapping("/bookmarks")
    public ResponseEntity<JsonResult<?>> bookmarks(@RequestParam String editionId,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) String limit,
                                                    Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        Integer parsedLimit = limit(limit);
        if (!identifier(editionId, 96)) return notFound();
        if (parsedLimit == null || !canonicalDecimal(cursor)) return invalidListSyntax();
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        try {
            ArchivePageDTO<ArchiveBookmarkDTO> page = service.bookmarks(
                    authorization.owner(), editionId, cursor, parsedLimit);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(JsonResult.success(page));
        } catch (ArchivePersonalDataException failure) {
            return personalError(failure);
        }
    }

    @PutMapping("/bookmarks/{bookmarkId}")
    public ResponseEntity<?> putBookmark(@PathVariable String bookmarkId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestBody(required = false) byte[] body,
                                         HttpServletRequest request,
                                         Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> syntax = mutationSyntax(request, key, lowercaseUuid(bookmarkId));
        if (syntax != null) return syntax;
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        return mutate(() -> service.putBookmark(authorization.owner(), bookmarkId,
                "/archive/v1/me/bookmarks/" + bookmarkId, key, body));
    }

    @DeleteMapping("/bookmarks/{bookmarkId}")
    public ResponseEntity<?> deleteBookmark(@PathVariable String bookmarkId,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            HttpServletRequest request,
                                            Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        if (!lowercaseUuid(bookmarkId)) return notFound();
        String expected = ifMatch(ifMatch);
        if (expected == null) return invalidVersion();
        ResponseEntity<JsonResult<?>> syntax = mutationSyntax(request, key, true);
        if (syntax != null) return syntax;
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        return mutate(() -> service.deleteBookmark(authorization.owner(), bookmarkId, expected,
                "/archive/v1/me/bookmarks/" + bookmarkId, key));
    }

    @GetMapping("/notes")
    public ResponseEntity<JsonResult<?>> notes(@RequestParam String editionId,
                                                @RequestParam(required = false) String chapterId,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) String limit,
                                                Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        Integer parsedLimit = limit(limit);
        if (!identifier(editionId, 96) || (chapterId != null && !identifier(chapterId, 128))) return notFound();
        if (parsedLimit == null || !canonicalDecimal(cursor)) return invalidListSyntax();
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        try {
            ArchivePageDTO<ArchiveNoteDTO> page = service.notes(
                    authorization.owner(), editionId, chapterId, cursor, parsedLimit);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(JsonResult.success(page));
        } catch (ArchivePersonalDataException failure) {
            return personalError(failure);
        }
    }

    @PutMapping("/notes/{noteId}")
    public ResponseEntity<?> putNote(@PathVariable String noteId,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestBody(required = false) byte[] body,
                                     HttpServletRequest request,
                                     Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> syntax = mutationSyntax(request, key, lowercaseUuid(noteId));
        if (syntax != null) return syntax;
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        return mutate(() -> service.putNote(authorization.owner(), noteId,
                "/archive/v1/me/notes/" + noteId, key, body));
    }

    @DeleteMapping("/notes/{noteId}")
    public ResponseEntity<?> deleteNote(@PathVariable String noteId,
                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        HttpServletRequest request,
                                        Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        if (!lowercaseUuid(noteId)) return notFound();
        String expected = ifMatch(ifMatch);
        if (expected == null) return invalidVersion();
        ResponseEntity<JsonResult<?>> syntax = mutationSyntax(request, key, true);
        if (syntax != null) return syntax;
        if (!accessPolicy.allows(authorization.owner().tenantId(), authorization.owner().clientId())) return notFound();
        return mutate(() -> service.deleteNote(authorization.owner(), noteId, expected,
                "/archive/v1/me/notes/" + noteId, key));
    }

    private ResponseEntity<?> mutate(Mutation mutation) {
        try {
            ArchiveMutationResult result = mutation.run();
            return ResponseEntity.status(result.status())
                    .contentType(MediaType.parseMediaType(result.contentType()))
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .body(result.body());
        } catch (ArchivePersonalDataException failure) {
            return personalError(failure);
        }
    }

    private Authorization authorize(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) return Authorization.failure(authIncomplete());
        Object ownerClaim = jwt.getToken().getClaims().get("jiacn");
        Object clientClaim = jwt.getToken().getClaims().get("client_id");
        if (!(ownerClaim instanceof String jiacn) || !(clientClaim instanceof String clientId)
                || !ArchiveReaderAccessPolicy.validRequestClaim(jiacn)
                || !ArchiveReaderAccessPolicy.validRequestClaim(clientId)) {
            return Authorization.failure(authIncomplete());
        }
        return new Authorization(new ArchiveOwnerScope(jiacn, clientId, jiacn), null);
    }

    private ResponseEntity<JsonResult<?>> mutationSyntax(HttpServletRequest request, String key, boolean pathValid) {
        if (!pathValid) return notFound();
        if (request.getQueryString() != null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST_JSON",
                    "Archive mutation routes do not accept query strings", null);
        }
        if (!visibleAsciiKey(key)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1..128 visible ASCII bytes", null);
        }
        return null;
    }

    private String ifMatch(String value) {
        if (value == null || value.length() < 4 || value.charAt(0) != '"' || value.charAt(1) != 'v'
                || value.charAt(value.length() - 1) != '"') return null;
        String decimal = value.substring(2, value.length() - 1);
        return canonicalDecimal(decimal) ? decimal : null;
    }

    private boolean canonicalDecimal(String value) {
        if (value == null) return true;
        if (!value.matches("0|[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) >= 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    private Integer limit(String value) {
        if (value == null) return 100;
        if (!value.matches("[1-9][0-9]{0,2}")) return null;
        int parsed = Integer.parseInt(value);
        return parsed <= 100 ? parsed : null;
    }

    private boolean identifier(String value, int maxBytes) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.getBytes(StandardCharsets.UTF_8).length <= maxBytes
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean lowercaseUuid(String value) {
        return value != null && value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    private boolean visibleAsciiKey(String value) {
        if (value == null || value.length() < 1 || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x21 || value.charAt(i) > 0x7e) return false;
        return true;
    }

    private ResponseEntity<JsonResult<?>> invalidVersion() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VERSION",
                "If-Match must be an exact quoted archive version", null);
    }
    private ResponseEntity<JsonResult<?>> invalidListSyntax() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CURSOR",
                "Archive list cursor or limit is invalid", null);
    }
    private ResponseEntity<JsonResult<?>> authIncomplete() {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_CONTEXT_INCOMPLETE",
                "Archive authentication context is incomplete", null);
    }
    private ResponseEntity<JsonResult<?>> notFound() {
        return error(HttpStatus.NOT_FOUND, "ARCHIVE_RESOURCE_NOT_FOUND",
                "Archive resource is not available", null);
    }
    private ResponseEntity<JsonResult<?>> personalError(ArchivePersonalDataException failure) {
        return error(HttpStatus.valueOf(failure.status()), failure.code(), failure.getMessage(), failure.currentVersion());
    }
    private ResponseEntity<JsonResult<?>> error(HttpStatus status, String code, String message, String currentVersion) {
        JsonResult<Object> result = JsonResult.failure(code, message);
        if (currentVersion != null) result.setData(Map.of("currentVersion", currentVersion));
        result.setStatus(status.value());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(result);
    }

    private record Authorization(ArchiveOwnerScope owner, ResponseEntity<JsonResult<?>> error) {
        static Authorization failure(ResponseEntity<JsonResult<?>> error) { return new Authorization(null, error); }
    }
    @FunctionalInterface private interface Mutation { ArchiveMutationResult run(); }
}
