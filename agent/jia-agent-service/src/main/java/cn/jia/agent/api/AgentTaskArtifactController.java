package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated public adapter for exact-version artifact publication and content download.
 * The existing collaboration service remains the sole ACL, lock, transaction, and version boundary.
 */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskArtifactController {
    static final String CACHE_CONTROL = "private, no-store";
    static final String NOSNIFF = "nosniff";
    static final int MAX_MANAGED_CONTENT_BYTES = 16 * 1024 * 1024;
    static final int MAX_REQUEST_BYTES = 24 * 1024 * 1024;
    static final int MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 8192;
    private static final int MAX_INITIAL_BODY_BUFFER_BYTES = 64 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MIME = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentTaskArtifactService artifactService;
    private final AgentTaskArtifactContentService contentService;

    public AgentTaskArtifactController(AgentTaskArtifactService artifactService,
            AgentTaskArtifactContentService contentService) {
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService");
        this.contentService = Objects.requireNonNull(contentService, "contentService");
    }

    @PostMapping(value = "/{taskId}/artifacts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskArtifactPublishResponse> publish(
            @PathVariable String taskId,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100);
        String actorAgentId = actor(request);
        AgentTaskArtifactPublishRequest publicRequest = parseBounded(request);
        byte[] content = publicRequest.getContentBytes();
        if (content == null || content.length > MAX_MANAGED_CONTENT_BYTES) {
            throw new RequestFailure();
        }
        Integer artifactVersion = jsonInteger(publicRequest.getArtifactVersion());
        Integer expectedPreviousVersion = jsonInteger(
                publicRequest.getExpectedPreviousVersion());

        AgentTaskArtifactPublishDTO command = new AgentTaskArtifactPublishDTO();
        command.setArtifactId(publicRequest.getArtifactId());
        command.setWorkItemId(publicRequest.getWorkItemId());
        command.setProducerAgentId(actorAgentId);
        command.setArtifactType(publicRequest.getArtifactType());
        command.setTitle(publicRequest.getTitle());
        command.setContentBytes(content);
        command.setContentMimeType(publicRequest.getContentMimeType());
        command.setContentHash(sha256(content));
        command.setContentByteLength((long) content.length);
        command.setArtifactVersion(artifactVersion);
        command.setExpectedPreviousVersion(expectedPreviousVersion);
        command.setVisibility(publicRequest.getVisibility());
        command.setMetadata(publicRequest.getMetadata());

        AgentTaskArtifactViewDTO published = artifactService.publish(
                scope.tenantId(), scope.clientId(), taskId, actorAgentId, command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(publicationResponse(
                        published, taskId, actorAgentId, command, content.length));
    }

    @GetMapping(value = "/{taskId}/artifacts/{artifactId}/versions/{artifactVersion}/content")
    public ResponseEntity<byte[]> download(
            @PathVariable String taskId,
            @PathVariable String artifactId,
            @PathVariable String artifactVersion,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100);
        requireExact(artifactId, 100);
        int exactVersion = positiveVersion(artifactVersion);
        String actorAgentId = actor(request);
        AgentTaskArtifactContentDTO result = contentService.readContent(
                scope.tenantId(), scope.clientId(), taskId, actorAgentId,
                artifactId, exactVersion);
        Download download = requireDownload(result, artifactId, exactVersion);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"artifact-v" + exactVersion + ".bin\"")
                .header("X-Content-Type-Options", NOSNIFF)
                .contentType(download.mediaType())
                .contentLength(download.content().length)
                .body(download.content());
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "ARTIFACT_FORBIDDEN" : "ARTIFACT_UNAUTHENTICATED",
                "Artifact access is unavailable");
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid artifact request");
    }

    @ExceptionHandler(AgentTaskCollaborationException.class)
    public ResponseEntity<ErrorBody> collaborationFailure(
            AgentTaskCollaborationException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "Invalid artifact request");
            case NOT_FOUND, FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    "ARTIFACT_NOT_FOUND", "Artifact is unavailable");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT,
                    "ARTIFACT_VERSION_CONFLICT", "Artifact version changed");
            case INVALID_TRANSITION -> error(HttpStatus.CONFLICT,
                    "ARTIFACT_CONFLICT", "Artifact publication was rejected");
            case INVALID_PERSISTED_STATE, RESERVED_FOR_LEASE_PROTOCOL ->
                    error(HttpStatus.SERVICE_UNAVAILABLE,
                            "ARTIFACT_UNAVAILABLE", "Artifact service is temporarily unavailable");
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) {
        log.error("Artifact HTTP request failed: uri={}", request.getRequestURI());
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "ARTIFACT_UNAVAILABLE", "Artifact service is temporarily unavailable");
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object tenant = claims.get("jiacn");
        Object client = claims.get("client_id");
        if (!(tenant instanceof String tenantId) || !(client instanceof String clientId)
                || !exact(tenantId, 50) || !exact(clientId, 50)) {
            throw new AuthenticationFailure(true);
        }
        return new Scope(tenantId, clientId);
    }

    private static String actor(HttpServletRequest request) {
        if (!request.getParameterMap().keySet().equals(Set.of("actorAgentId"))) {
            throw new RequestFailure();
        }
        String[] values = request.getParameterValues("actorAgentId");
        if (values == null || values.length != 1 || !exact(values[0], 100)) {
            throw new RequestFailure();
        }
        return values[0];
    }

    private static AgentTaskArtifactPublishRequest parseBounded(HttpServletRequest request) {
        byte[] body = readBounded(request);
        try {
            AgentTaskArtifactPublishRequest parsed = STRICT_JSON.readValue(
                    body, AgentTaskArtifactPublishRequest.class);
            if (parsed == null) {
                throw new RequestFailure();
            }
            return parsed;
        } catch (RequestFailure failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
    }

    private static byte[] readBounded(HttpServletRequest request) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength == 0 || declaredLength > MAX_REQUEST_BYTES) {
            throw new RequestFailure();
        }
        int initialSize = declaredLength > 0
                ? (int) Math.min(declaredLength, MAX_INITIAL_BODY_BUFFER_BYTES)
                : COPY_BUFFER_BYTES;
        try (ByteArrayOutputStream result = new ByteArrayOutputStream(initialSize)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int total = 0;
            int count;
            while ((count = request.getInputStream().read(buffer)) != -1) {
                if (count == 0) {
                    continue;
                }
                if (count > MAX_REQUEST_BYTES - total) {
                    throw new RequestFailure();
                }
                result.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) {
                throw new RequestFailure();
            }
            return result.toByteArray();
        } catch (RequestFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RequestFailure();
        }
    }

    private static AgentTaskArtifactPublishResponse publicationResponse(
            AgentTaskArtifactViewDTO result, String taskId, String actorAgentId,
            AgentTaskArtifactPublishDTO command, long byteLength) {
        if (result == null
                || !Objects.equals(taskId, result.getTaskId())
                || !Objects.equals(actorAgentId, result.getProducerAgentId())
                || !Objects.equals(command.getArtifactId(), result.getArtifactId())
                || !Objects.equals(command.getArtifactVersion(), result.getArtifactVersion())
                || !Objects.equals(command.getContentHash(), result.getContentHash())
                || !Objects.equals(byteLength, result.getContentByteLength())
                || !Objects.equals(command.getContentMimeType(), result.getContentMimeType())
                || !Boolean.TRUE.equals(result.getManagedStorage())
                || result.getContent() != null) {
            throw new IllegalStateException("Artifact publication result is inconsistent");
        }
        if (result.getCreatedAt() == null || result.getCreatedAt() < 0) {
            throw new IllegalStateException("Artifact publication timestamp is inconsistent");
        }
        return new AgentTaskArtifactPublishResponse(
                result.getArtifactId(), result.getArtifactVersion(), result.getContentHash(),
                result.getContentByteLength(), result.getContentMimeType(), result.getCreatedAt());
    }

    private static Download requireDownload(
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
            throw new IllegalStateException("Artifact download result is inconsistent");
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(result.getContentMimeType());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Artifact download MIME type is invalid");
        }
        if (!mediaType.getParameters().isEmpty()) {
            throw new IllegalStateException("Artifact download MIME parameters are unavailable");
        }
        byte[] content = result.getContent();
        if (content == null || content.length != result.getContentByteLength()
                || !result.getContentHash().equals(sha256(content))) {
            throw new IllegalStateException("Artifact download content is inconsistent");
        }
        return new Download(content, mediaType);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Integer jsonInteger(Object value) {
        if (!(value instanceof Integer exact)) {
            throw new RequestFailure();
        }
        return exact;
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

    private static ResponseEntity<ErrorBody> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    private record Scope(String tenantId, String clientId) {
    }

    private record Download(byte[] content, MediaType mediaType) {
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
}
