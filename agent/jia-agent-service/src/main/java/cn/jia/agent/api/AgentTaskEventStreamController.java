package cn.jia.agent.api;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.service.AgentTaskEventAccessService;
import cn.jia.agent.service.AgentTaskEventAccessService.AuthorizedSubject;
import cn.jia.agent.service.AgentTaskEventReplayService;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplaySignal;
import cn.jia.agent.service.AgentTaskEventReplayService.TaskScope;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Browser-facing C05 task-event SSE edge. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskEventStreamController {
    static final long SSE_TIMEOUT_MILLIS = 30_000L;
    static final String CACHE_CONTROL_VALUE = "private, no-store";
    static final String X_ACCEL_BUFFERING = "X-Accel-Buffering";
    private static final Pattern CURSOR = Pattern.compile("0|[1-9][0-9]{0,18}");

    private final AgentTaskEventAccessService accessService;
    private final AgentTaskEventReplayService replayService;
    private final AgentTaskEventsGate taskEventsGate;

    public AgentTaskEventStreamController(
            AgentTaskEventAccessService accessService,
            AgentTaskEventReplayService replayService,
            AgentTaskEventsGate taskEventsGate) {
        this.accessService = java.util.Objects.requireNonNull(
                accessService, "accessService");
        this.replayService = java.util.Objects.requireNonNull(
                replayService, "replayService");
        this.taskEventsGate = java.util.Objects.requireNonNull(
                taskEventsGate, "taskEventsGate");
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @PathVariable String taskId,
            HttpServletRequest request,
            Authentication authentication) {
        Scope jwt = requireJwtScope(authentication);
        RawRequest raw = requireRawRequest(taskId, request);
        if (!taskEventsGate.allows(jwt.jiacn(), jwt.clientId())) {
            throw unavailable();
        }
        AuthorizedSubject subject = accessService.authorize(
                jwt.jiacn(), jwt.clientId(), taskId, raw.actorAgentId());
        if (subject == null || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable();
        }

        TaskScope scope;
        Flux<ReplaySignal> source;
        try {
            scope = new TaskScope(subject.tenantId(), subject.clientId(), subject.taskId());
            source = replayService.replay(scope, raw.cursor());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        if (source == null) {
            throw unavailable();
        }

        ManagedSseEmitter emitter = new ManagedSseEmitter(SSE_TIMEOUT_MILLIS);
        StreamConnection connection = new StreamConnection(emitter, subject);
        connection.start(source);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .header(X_ACCEL_BUFFERING, "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    @ExceptionHandler(StreamAuthenticationException.class)
    public ResponseEntity<StreamError> handleAuthentication(
            StreamAuthenticationException exception) {
        HttpStatus status = exception.forbidden
                ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        return error(status,
                exception.forbidden ? "TASK_EVENTS_FORBIDDEN" : "TASK_EVENTS_UNAUTHENTICATED",
                exception.forbidden ? "Task event scope is unavailable"
                        : "Authentication is required");
    }

    @ExceptionHandler(StreamRequestException.class)
    public ResponseEntity<StreamError> handleBadRequest(StreamRequestException ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid task event request");
    }

    @ExceptionHandler(AgentTaskWorkspaceException.class)
    public ResponseEntity<StreamError> handleWorkspaceFailure(
            AgentTaskWorkspaceException exception) {
        if (exception.getReason()
                == AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN) {
            log.warn("Task event request rejected: reason=not_found_or_forbidden");
            return error(HttpStatus.NOT_FOUND, "TASK_EVENTS_NOT_FOUND",
                    "Task event stream is not available in the requested scope");
        }
        log.error("Task event authorization failed: reason=workspace_unavailable");
        return unavailableResponse();
    }

    @ExceptionHandler(StreamUnavailableException.class)
    public ResponseEntity<StreamError> handleUnavailable(StreamUnavailableException ignored) {
        log.error("Task event stream failed: reason=unavailable");
        return unavailableResponse();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StreamError> handleUnexpected(Exception ignored) {
        log.error("Task event stream failed: reason=unexpected");
        return unavailableResponse();
    }

    private static RawRequest requireRawRequest(
            String taskId, HttpServletRequest request) {
        requireExact(taskId, 100);
        String actorAgentId = singleRequiredQuery(request, "actorAgentId");
        requireExact(actorAgentId, 100);
        String queryCursor = singleOptionalQuery(request, "sinceVersion");
        String headerCursor = singleOptionalHeader(request, "Last-Event-ID");
        long query = queryCursor == null ? 0L : parseCursor(queryCursor);
        long header = headerCursor == null ? 0L : parseCursor(headerCursor);
        return new RawRequest(actorAgentId, Math.max(query, header));
    }

    private static String singleRequiredQuery(
            HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) {
            throw badRequest();
        }
        return values[0];
    }

    private static String singleOptionalQuery(
            HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1) {
            throw badRequest();
        }
        return values[0];
    }

    private static String singleOptionalHeader(
            HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw badRequest();
        }
        return values.get(0);
    }

    private static long parseCursor(String value) {
        if (value == null || !CURSOR.matcher(value).matches()) {
            throw badRequest();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw badRequest();
        }
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new StreamAuthenticationException(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object jiacn = claims.get("jiacn");
        Object clientId = claims.get("client_id");
        if (!(jiacn instanceof String tenant) || !(clientId instanceof String client)
                || !validExact(tenant, 50) || !validExact(client, 50)) {
            throw new StreamAuthenticationException(true);
        }
        return new Scope(tenant, client);
    }

    private static void requireExact(String value, int maxLength) {
        if (!validExact(value, maxLength)) {
            throw badRequest();
        }
    }

    private static boolean validExact(String value, int maxLength) {
        return value != null && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && !value.codePoints().allMatch(AgentTaskEventStreamController::isPadding)
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

    private static ResponseEntity<StreamError> unavailableResponse() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TASK_EVENTS_UNAVAILABLE",
                "Task event stream is temporarily unavailable");
    }

    private static ResponseEntity<StreamError> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new StreamError(code, message));
    }

    private static StreamRequestException badRequest() {
        return new StreamRequestException();
    }

    private static StreamUnavailableException unavailable() {
        return new StreamUnavailableException();
    }

    private record Scope(String jiacn, String clientId) {
    }

    private record RawRequest(String actorAgentId, long cursor) {
    }

    public record StreamError(String code, String message) {
    }

    private static final class StreamAuthenticationException extends RuntimeException {
        private final boolean forbidden;

        private StreamAuthenticationException(boolean forbidden) {
            this.forbidden = forbidden;
        }
    }

    private static final class StreamRequestException extends RuntimeException {
    }

    static final class StreamUnavailableException extends RuntimeException {
    }

    static final class StreamConnection {
        private final ManagedSseEmitter emitter;
        private final AuthorizedSubject subject;
        private final Object sendLock = new Object();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicBoolean sendAttempted = new AtomicBoolean();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        StreamConnection(
                ManagedSseEmitter emitter, AuthorizedSubject subject) {
            this.emitter = emitter;
            this.subject = subject;
            emitter.setCleanup(this::externalTermination);
            emitter.onCompletion(this::externalTermination);
            emitter.onTimeout(this::timeout);
            emitter.onError(ignored -> externalTermination());
        }

        void start(Flux<ReplaySignal> source) {
            final Disposable disposable;
            try {
                disposable = source.subscribe(
                        this::signal, this::failure, this::sourceComplete);
            } catch (RuntimeException exception) {
                failure(exception);
                return;
            }
            if (!subscription.compareAndSet(null, disposable)) {
                disposable.dispose();
                return;
            }
            if (cleaned.get() && subscription.compareAndSet(disposable, null)) {
                disposable.dispose();
            }
        }

        private void signal(ReplaySignal signal) {
            boolean close = false;
            try {
                synchronized (sendLock) {
                    if (terminal.get()) {
                        return;
                    }
                    AgentTaskEventProjection.Frame frame =
                            AgentTaskEventProjection.project(subject, signal);
                    SseEmitter.SseEventBuilder builder = SseEmitter.event()
                            .name(frame.eventName());
                    if (frame.id() != null) {
                        builder.id(frame.id());
                    }
                    builder.data(frame.data(), MediaType.APPLICATION_JSON);
                    sendAttempted.set(true);
                    emitter.send(builder);
                    if (frame.terminal() && terminal.compareAndSet(false, true)) {
                        close = true;
                    }
                }
            } catch (IOException | RuntimeException exception) {
                failure(exception);
                return;
            }
            if (close) {
                cleanup();
                emitter.complete();
            }
        }

        private void failure(Throwable ignored) {
            boolean beforeSendAttempt;
            synchronized (sendLock) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                beforeSendAttempt = !sendAttempted.get();
            }
            cleanup();
            if (beforeSendAttempt) {
                emitter.completeWithError(unavailable());
            } else {
                emitter.complete();
            }
        }

        private void sourceComplete() {
            boolean beforeSendAttempt;
            synchronized (sendLock) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                beforeSendAttempt = !sendAttempted.get();
            }
            cleanup();
            if (beforeSendAttempt) {
                emitter.completeWithError(unavailable());
            } else {
                emitter.complete();
            }
        }

        void timeout() {
            synchronized (sendLock) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
            }
            cleanup();
            emitter.complete();
        }

        private void externalTermination() {
            synchronized (sendLock) {
                terminal.set(true);
            }
            cleanup();
        }

        private void cleanup() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            Disposable disposable = subscription.getAndSet(null);
            if (disposable != null) {
                disposable.dispose();
            }
        }
    }

    static class ManagedSseEmitter extends SseEmitter {
        private final AtomicReference<Runnable> cleanup = new AtomicReference<>();

        ManagedSseEmitter(long timeout) {
            super(timeout);
        }

        private void setCleanup(Runnable callback) {
            cleanup.set(callback);
        }

        private void cleanup() {
            Runnable callback = cleanup.get();
            if (callback != null) {
                callback.run();
            }
        }

        @Override
        public void complete() {
            cleanup();
            super.complete();
        }

        @Override
        public void completeWithError(Throwable error) {
            cleanup();
            super.completeWithError(error);
        }
    }
}
