package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskArtifactService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only browser adapter for existing task artifacts.
 *
 * <p>The existing collaboration services remain the version and content-storage boundary. This
 * adapter binds a browser task-owner read to the exact JWT tenant/client scope, so the task owner
 * can inspect a bounty's deliverables even when no browser persona is a task member. It never
 * accepts a browser-supplied {@code actorAgentId}, storage URI, or artifact metadata.</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskDeliverableController {
    static final String CACHE_CONTROL = "private, no-store";
    static final String NOSNIFF = "nosniff";
    static final int MAX_LIST_LIMIT = 100;
    static final int MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024;
    private static final Set<String> LIST_QUERY_FIELDS = Set.of("workItemId", "limit");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MIME = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");

    private final AgentTaskArtifactService artifactService;
    private final AgentTaskArtifactContentService contentService;
    private final AgentTaskDeliverablePreviewAdapter previewAdapter =
            new AgentTaskDeliverablePreviewAdapter();

    public AgentTaskDeliverableController(AgentTaskArtifactService artifactService,
            AgentTaskArtifactContentService contentService) {
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService");
        this.contentService = Objects.requireNonNull(contentService, "contentService");
    }

    @GetMapping(value = "/{taskId}/deliverables", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeliverableListResponse> list(
            @PathVariable String taskId,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireSafeRequestPath(request);
        requirePathId(taskId, 100);
        ListQuery query = requireListQuery(request);

        AgentTaskArtifactQueryDTO serviceQuery = new AgentTaskArtifactQueryDTO();
        serviceQuery.setWorkItemId(query.workItemId());
        serviceQuery.setLimit(query.limit());
        return ok(new DeliverableListResponse(
                listOwnerDeliverables(scope, taskId, query, serviceQuery), null));
    }

    @GetMapping(value = "/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/content")
    public ResponseEntity<byte[]> download(
            @PathVariable String taskId,
            @PathVariable String artifactId,
            @PathVariable String artifactVersion,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        RequestTarget target = requireTarget(taskId, artifactId, artifactVersion, request);
        VerifiedContent content = readContent(scope, target);
        byte[] bytes = content.bytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"deliverable-v" + target.artifactVersion() + ".bin\"")
                .header("X-Content-Type-Options", NOSNIFF)
                .contentType(content.mediaType())
                .contentLength(bytes.length)
                .body(bytes);
    }

    @GetMapping(value = "/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}/preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResponse> preview(
            @PathVariable String taskId,
            @PathVariable String artifactId,
            @PathVariable String artifactVersion,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        PreviewTarget previewTarget = requirePreviewTarget(
                taskId, artifactId, artifactVersion, request);
        AgentTaskDeliverablePreviewAdapter.Preview rendered =
                renderPreview(scope, previewTarget.target());
        List<AgentTaskDeliverablePreviewAdapter.Part> visibleParts = previewTarget.partsView()
                ? rendered.parts() : rendered.legacyParts();
        String representation = previewTarget.partsView()
                ? rendered.representation() : rendered.legacyRepresentation();
        return ok(new PreviewResponse(rendered.state(), representation,
                visibleParts.stream()
                        .map(part -> new PreviewPartResponse(part.partId(), part.contentMimeType()))
                        .toList(),
                rendered.partial(), rendered.reason()));
    }

    @GetMapping(value = "/{taskId}/deliverables/{artifactId}/versions/{artifactVersion}"
            + "/preview/parts/{partId}")
    public ResponseEntity<byte[]> previewContent(
            @PathVariable String taskId,
            @PathVariable String artifactId,
            @PathVariable String artifactVersion,
            @PathVariable String partId,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        RequestTarget target = requireTarget(taskId, artifactId, artifactVersion, request);
        requirePathId(partId, 100);

        // Resolve the owner-scoped artifact before interpreting a syntactically safe part id.
        // This prevents valid-looking unknown ids from becoming an artifact existence oracle.
        VerifiedContent content = readContent(scope, target);
        AgentTaskDeliverablePreviewAdapter.Preview rendered = renderPreview(content);
        AgentTaskDeliverablePreviewAdapter.PartContent selected = rendered.readPart(partId);
        if (selected == null) {
            if (AgentTaskDeliverablePreviewAdapter.CONTENT_PART_ID.equals(partId)
                    && "UNSUPPORTED".equals(rendered.state())) {
                throw new PreviewPartUnavailable();
            }
            throw new PreviewPartNotFound();
        }
        byte[] bytes = selected.bytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", NOSNIFF)
                .contentType(MediaType.parseMediaType(selected.part().contentMimeType()))
                .contentLength(bytes.length)
                .body(bytes);
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "DELIVERABLE_FORBIDDEN" : "DELIVERABLE_UNAUTHENTICATED",
                "Deliverable access is unavailable");
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(RequestFailure ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid deliverable request");
    }

    @ExceptionHandler(PreviewPartNotFound.class)
    public ResponseEntity<ErrorBody> previewPartNotFound(PreviewPartNotFound ignored) {
        return error(HttpStatus.NOT_FOUND, "DELIVERABLE_PREVIEW_PART_NOT_FOUND",
                "Deliverable preview content is unavailable");
    }

    @ExceptionHandler(PreviewPartUnavailable.class)
    public ResponseEntity<ErrorBody> previewPartUnavailable(PreviewPartUnavailable ignored) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "DELIVERABLE_PREVIEW_UNSUPPORTED",
                "Deliverable preview content is unavailable");
    }

    @ExceptionHandler(AgentTaskCollaborationException.class)
    public ResponseEntity<ErrorBody> collaborationFailure(AgentTaskCollaborationException failure) {
        return switch (failure.getReason()) {
            case NOT_FOUND, FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    "DELIVERABLE_NOT_FOUND", "Deliverable is unavailable");
            case VERSION_CONFLICT, INVALID_TRANSITION -> error(HttpStatus.CONFLICT,
                    "DELIVERABLE_CONFLICT", "Deliverable changed");
            case INVALID_REQUEST, INVALID_PERSISTED_STATE, RESERVED_FOR_LEASE_PROTOCOL -> unavailable();
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) {
        log.error("Task deliverable HTTP request failed: uri={}", request.getRequestURI());
        return unavailable();
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        String ownerJiacn = requiredClaim(claims, "jiacn", 50);
        String clientId = requiredClaim(claims, "client_id", 50);
        String subject = requiredClaim(claims, "sub", 100);
        if ("0".equals(ownerJiacn) || "0".equals(clientId)
                || !byteExact(authentication.getName(), subject)) {
            throw new AuthenticationFailure(true);
        }
        return new Scope("0", clientId, ownerJiacn);
    }

    private static String requiredClaim(Map<String, Object> claims, String name, int maxLength) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || !exact(text, maxLength)) {
            throw new AuthenticationFailure(true);
        }
        return text;
    }

    private List<DeliverableResponse> listOwnerDeliverables(
            Scope scope, String taskId, ListQuery query, AgentTaskArtifactQueryDTO serviceQuery) {
        int limit = effectiveLimit(query.limit());
        List<AgentTaskArtifactViewDTO> result = artifactService.listForTaskOwner(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, serviceQuery);
        if (result == null || result.size() > limit) {
            throw new IllegalStateException("Deliverable result is inconsistent");
        }
        return result.stream()
                .map(artifact -> requireDeliverable(artifact, taskId, query.workItemId()))
                .toList();
    }

    private AgentTaskDeliverablePreviewAdapter.Preview renderPreview(
            Scope scope, RequestTarget target) {
        return renderPreview(readContent(scope, target));
    }

    private AgentTaskDeliverablePreviewAdapter.Preview renderPreview(VerifiedContent content) {
        return previewAdapter.render(content.mediaType().toString(), content.bytes());
    }

    private VerifiedContent readContent(Scope scope, RequestTarget target) {
        AgentTaskArtifactContentDTO result = contentService.readContentForTaskOwner(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), target.taskId(),
                target.artifactId(), target.artifactVersion());
        return requireContent(result, target.artifactId(), target.artifactVersion());
    }

    private static PreviewTarget requirePreviewTarget(String taskId, String artifactId,
            String artifactVersion, HttpServletRequest request) {
        requireSafeRequestPath(request);
        requirePathId(taskId, 100);
        requirePathId(artifactId, 100);
        boolean partsView = requirePartsView(request);
        return new PreviewTarget(
                new RequestTarget(taskId, artifactId, positiveVersion(artifactVersion)), partsView);
    }

    private static boolean requirePartsView(HttpServletRequest request) {
        if (request.getParameterMap().isEmpty()) return false;
        if (request.getParameterMap().size() != 1
                || !request.getParameterMap().containsKey("view")) {
            throw new RequestFailure();
        }
        String[] values = request.getParameterValues("view");
        if (values == null || values.length != 1 || !"parts".equals(values[0])) {
            throw new RequestFailure();
        }
        return true;
    }

    private static RequestTarget requireTarget(String taskId, String artifactId,
            String artifactVersion, HttpServletRequest request) {
        requireSafeRequestPath(request);
        requirePathId(taskId, 100);
        requirePathId(artifactId, 100);
        requireNoQuery(request);
        return new RequestTarget(taskId, artifactId, positiveVersion(artifactVersion));
    }

    private static ListQuery requireListQuery(HttpServletRequest request) {
        if (!LIST_QUERY_FIELDS.containsAll(request.getParameterMap().keySet())) {
            throw new RequestFailure();
        }
        String workItemId = singleOptionalQuery(request, "workItemId");
        if (workItemId != null) {
            requireExact(workItemId, 100);
        }
        String rawLimit = singleOptionalQuery(request, "limit");
        Integer limit = rawLimit == null ? null : positiveLimit(rawLimit);
        return new ListQuery(workItemId, limit);
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new RequestFailure();
        }
    }

    private static String singleOptionalQuery(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1) {
            throw new RequestFailure();
        }
        return values[0];
    }

    private static int positiveLimit(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,2}")) {
            throw new RequestFailure();
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > MAX_LIST_LIMIT || !Integer.toString(parsed).equals(value)) {
                throw new RequestFailure();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new RequestFailure();
        }
    }

    private static int positiveVersion(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) {
            throw new RequestFailure();
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new RequestFailure();
        }
    }

    private static DeliverableResponse requireDeliverable(
            AgentTaskArtifactViewDTO artifact, String taskId, String expectedWorkItemId) {
        if (artifact == null
                || !Objects.equals(taskId, artifact.getTaskId())
                || (expectedWorkItemId != null
                        && !Objects.equals(expectedWorkItemId, artifact.getWorkItemId()))
                || !exact(artifact.getArtifactId(), 100)
                || !exact(artifact.getProducerAgentId(), 100)
                || !exact(artifact.getArtifactType(), 100)
                || !exact(artifact.getTitle(), 255)
                || !exact(artifact.getContentMimeType(), 128)
                || !MIME.matcher(artifact.getContentMimeType()).matches()
                || artifact.getArtifactVersion() == null || artifact.getArtifactVersion() < 1
                || artifact.getContentByteLength() == null || artifact.getContentByteLength() < 0
                || artifact.getContentByteLength() > MAX_DOWNLOAD_BYTES
                || artifact.getContentHash() == null
                || !SHA256.matcher(artifact.getContentHash()).matches()
                || artifact.getCreatedAt() == null || artifact.getCreatedAt() < 0) {
            throw new IllegalStateException("Deliverable result is inconsistent");
        }
        return new DeliverableResponse(artifact.getArtifactId(), artifact.getTaskId(),
                artifact.getWorkItemId(), artifact.getProducerAgentId(), artifact.getArtifactType(),
                artifact.getTitle(), artifact.getContentHash(), artifact.getContentByteLength(),
                artifact.getContentMimeType(), artifact.getArtifactVersion(), artifact.getVisibility(),
                artifact.getCreatedAt());
    }

    private static VerifiedContent requireContent(
            AgentTaskArtifactContentDTO result, String artifactId, int artifactVersion) {
        if (result == null
                || !Objects.equals(artifactId, result.getArtifactId())
                || !Objects.equals(artifactVersion, result.getArtifactVersion())
                || result.getContentByteLength() == null
                || result.getContentByteLength() < 0
                || result.getContentByteLength() > MAX_DOWNLOAD_BYTES
                || result.getContentHash() == null
                || !SHA256.matcher(result.getContentHash()).matches()
                || result.getContentMimeType() == null
                || !MIME.matcher(result.getContentMimeType()).matches()) {
            throw new IllegalStateException("Deliverable content result is inconsistent");
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(result.getContentMimeType());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Deliverable content MIME type is invalid");
        }
        if (!mediaType.getParameters().isEmpty()) {
            throw new IllegalStateException("Deliverable content MIME parameters are unavailable");
        }
        byte[] content = result.getContent();
        if (content == null || content.length != result.getContentByteLength()
                || !result.getContentHash().equals(sha256(content))) {
            throw new IllegalStateException("Deliverable content is inconsistent");
        }
        return new VerifiedContent(content, mediaType);
    }

    private static int effectiveLimit(Integer limit) {
        return limit == null ? MAX_LIST_LIMIT : limit;
    }

    private static boolean byteExact(String left, String right) {
        return left != null && left.equals(right);
    }

    private static void requireSafeRequestPath(HttpServletRequest request) {
        String rawPath = request.getRequestURI();
        if (rawPath == null) {
            throw new RequestFailure();
        }
        String lowerPath = rawPath.toLowerCase(java.util.Locale.ROOT);
        if (rawPath.indexOf(';') >= 0 || rawPath.indexOf('\\') >= 0
                || lowerPath.contains("%00") || lowerPath.contains("%25")
                || lowerPath.contains("%2e") || lowerPath.contains("%2f")
                || lowerPath.contains("%3b") || lowerPath.contains("%5c")) {
            throw new RequestFailure();
        }
    }

    private static void requirePathId(String value, int maxLength) {
        requireExact(value, maxLength);
        if (value.equals(".") || value.equals("..")
                || value.codePoints().anyMatch(AgentTaskDeliverableController::isPathMetaCharacter)) {
            throw new RequestFailure();
        }
    }

    private static boolean isPathMetaCharacter(int codePoint) {
        return codePoint == '/' || codePoint == '\\' || codePoint == '%'
                || codePoint == '?' || codePoint == '#' || codePoint == ';';
    }

    private static void requireExact(String value, int maxLength) {
        if (!exact(value, maxLength)) {
            throw new RequestFailure();
        }
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", NOSNIFF)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(body);
    }

    private static ResponseEntity<ErrorBody> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "DELIVERABLE_UNAVAILABLE", "Deliverable service is temporarily unavailable");
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", NOSNIFF)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    private record Scope(String tenantId, String clientId, String ownerJiacn) {
    }

    private record ListQuery(String workItemId, Integer limit) {
    }

    private record RequestTarget(String taskId, String artifactId, int artifactVersion) {
    }

    private record PreviewTarget(RequestTarget target, boolean partsView) {
    }

    private record VerifiedContent(byte[] bytes, MediaType mediaType) {
    }

    public record DeliverableListResponse(List<DeliverableResponse> items, String nextCursor) {
    }

    public record DeliverableResponse(String artifactId, String taskId, String workItemId,
            String producerAgentId, String artifactType, String title, String contentHash,
            Long contentByteLength, String contentMimeType, Integer artifactVersion,
            String visibility, Long createdAt) {
    }

    public record PreviewResponse(String state, String representation,
            List<PreviewPartResponse> parts, boolean partial, String reason) {
    }

    public record PreviewPartResponse(String partId, String contentMimeType) {
    }

    public record ErrorBody(String code, String message) {
    }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;

        private AuthenticationFailure(boolean forbidden) {
            this.forbidden = forbidden;
        }
    }

    private static final class RequestFailure extends RuntimeException {
    }

    private static final class PreviewPartNotFound extends RuntimeException {
    }

    private static final class PreviewPartUnavailable extends RuntimeException {
    }
}
