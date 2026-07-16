package cn.jia.agent.api;

import cn.jia.agent.common.AgentSceneConstants;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.entity.JsonResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/agent/scenes")
@RequiredArgsConstructor
public class AgentSceneController {
    private static final long SSE_TIMEOUT_MILLIS = 30_000L;
    private static final Pattern SAFE_EVENT_TYPE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final AgentSceneService sceneService;

    @GetMapping("/{sceneId}/snapshot")
    public JsonResult<AgentSceneSnapshotDTO> snapshot(@PathVariable String sceneId) {
        String requiredSceneId = requireSceneId(sceneId);
        try {
            return JsonResult.success(sceneService.snapshot(requiredSceneId));
        } catch (IllegalArgumentException exception) {
            throw new SceneServiceValidationException();
        }
    }

    @GetMapping(value = "/{sceneId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String sceneId,
            @RequestParam(name = "sinceVersion", required = false) Long sinceVersion,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        String requiredSceneId = requireSceneId(sceneId);
        long resumeAfter = resolveResumeVersion(sinceVersion, lastEventId);
        Flux<AgentSceneEventDTO> events;
        try {
            events = sceneService.events(requiredSceneId, resumeAfter);
        } catch (IllegalArgumentException exception) {
            throw new SceneServiceValidationException();
        }
        if (events == null) {
            throw new SceneStreamException();
        }
        return bridge(events);
    }

    @PostMapping("/{sceneId}/phases")
    public JsonResult<AgentScenePhaseResultDTO> phase(
            @PathVariable String sceneId,
            @RequestBody(required = false) AgentScenePhaseReportDTO request) {
        String requiredSceneId = requireSceneId(sceneId);
        AgentScenePhaseReportDTO safeRequest = requirePhaseReport(request);
        try {
            return JsonResult.success(sceneService.reportPhase(requiredSceneId, safeRequest));
        } catch (IllegalArgumentException exception) {
            throw new SceneServiceValidationException();
        }
    }

    @ExceptionHandler(SceneRequestException.class)
    public ResponseEntity<JsonResult<Void>> handleSceneRequestException(SceneRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", exception.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<JsonResult<Void>> handleInvalidBinding() {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid scene request");
    }

    @ExceptionHandler(SceneServiceValidationException.class)
    public ResponseEntity<JsonResult<Void>> handleServiceValidation() {
        return error(HttpStatus.UNPROCESSABLE_CONTENT,
                "SCENE_VALIDATION_FAILED", "Scene request was rejected");
    }

    @ExceptionHandler(AgentBizException.class)
    public ResponseEntity<JsonResult<Void>> handleSceneConflict() {
        return error(HttpStatus.CONFLICT, "SCENE_CONFLICT", "Scene request conflicts with current state");
    }

    @ExceptionHandler(SceneStreamException.class)
    public ResponseEntity<JsonResult<Void>> handleSceneStreamFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "SCENE_STREAM_ERROR", "Scene event stream failed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> handleUnexpectedFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "SCENE_ERROR", "Scene request failed");
    }

    private SseEmitter bridge(Flux<AgentSceneEventDTO> source) {
        ManagedSseEmitter emitter = new ManagedSseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean terminated = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (terminated.compareAndSet(false, true)) {
                Disposable disposable = subscription.getAndSet(null);
                if (disposable != null) {
                    disposable.dispose();
                }
            }
        };
        emitter.setCleanup(cleanup);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> cleanup.run());

        Disposable disposable = source.subscribe(
                event -> send(emitter, event),
                error -> emitter.completeWithError(safeStreamError(error)),
                emitter::complete);
        if (!subscription.compareAndSet(null, disposable) || terminated.get()) {
            disposable.dispose();
            subscription.compareAndSet(disposable, null);
        }
        return emitter;
    }

    private void send(ManagedSseEmitter emitter, AgentSceneEventDTO source) {
        AgentSceneEventDTO event;
        try {
            event = safeEvent(source);
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.getSceneVersion()))
                    .name(event.getEventType())
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException exception) {
            emitter.completeWithError(new SceneStreamException());
        }
    }

    private RuntimeException safeStreamError(Throwable error) {
        if (error instanceof AgentBizException) {
            return new AgentBizException("SCENE_CONFLICT", "Scene event stream conflict");
        }
        if (error instanceof IllegalArgumentException) {
            return new SceneServiceValidationException();
        }
        return new SceneStreamException();
    }

    private static AgentSceneEventDTO safeEvent(AgentSceneEventDTO source) {
        if (source == null || source.getSceneVersion() == null || source.getSceneVersion() < 0) {
            throw new IllegalArgumentException("Invalid scene event version");
        }
        String eventType = source.getEventType();
        if (eventType == null || !SAFE_EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("Invalid scene event type");
        }
        AgentSceneEventDTO safe = new AgentSceneEventDTO();
        safe.setSceneVersion(source.getSceneVersion());
        safe.setEventType(eventType);
        safe.setState(AgentSceneStateDTO.copyOf(source.getState()));
        safe.setOccurredAt(source.getOccurredAt());
        return safe;
    }

    private static long resolveResumeVersion(Long sinceVersion, String lastEventId) {
        if (sinceVersion != null) {
            if (sinceVersion < 0) {
                throw new SceneRequestException("sinceVersion must be nonnegative");
            }
        }
        Long headerVersion = null;
        if (lastEventId != null) {
            headerVersion = parseLastEventId(lastEventId);
        }
        long queryCursor = sinceVersion == null ? 0L : sinceVersion;
        long headerCursor = headerVersion == null ? 0L : headerVersion;
        return Math.max(queryCursor, headerCursor);
    }

    private static long parseLastEventId(String lastEventId) {
        String normalized = lastEventId.trim();
        if (normalized.isEmpty() || !normalized.matches("[0-9]+")) {
            throw new SceneRequestException("Last-Event-ID must be a nonnegative version");
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new SceneRequestException("Last-Event-ID must be a nonnegative version");
        }
    }

    private static AgentScenePhaseReportDTO requirePhaseReport(AgentScenePhaseReportDTO source) {
        if (source == null) {
            throw new SceneRequestException("Phase report is required");
        }
        AgentScenePhaseReportDTO report = new AgentScenePhaseReportDTO();
        report.setReportId(requireText(source.getReportId(), "reportId", 100));
        report.setAgentId(requireText(source.getAgentId(), "agentId", 100));
        report.setRegionId(requireText(source.getRegionId(), "regionId", 100));
        report.setPhase(requireText(source.getPhase(), "phase", 20));
        report.setStateVersion(source.getStateVersion());
        report.setOccurredAt(source.getOccurredAt());
        if (!AgentSceneConstants.PHASES.contains(report.getPhase())) {
            throw new SceneRequestException("phase must be arrived or blocked");
        }
        if (report.getStateVersion() == null || report.getStateVersion() <= 0) {
            throw new SceneRequestException("stateVersion must be positive");
        }
        if (report.getOccurredAt() == null || report.getOccurredAt() < 0) {
            throw new SceneRequestException("occurredAt must be nonnegative");
        }
        return report;
    }

    private static String requireSceneId(String sceneId) {
        return requireText(sceneId, "sceneId", 100);
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new SceneRequestException(field + " is required");
        }
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new SceneRequestException(field + " is invalid");
        }
        return normalized;
    }

    private static ResponseEntity<JsonResult<Void>> error(
            HttpStatus status, String code, String message) {
        JsonResult<Void> result = JsonResult.failure(code, message);
        result.setStatus(status.value());
        return ResponseEntity.status(status).body(result);
    }

    static final class SceneRequestException extends IllegalArgumentException {
        private SceneRequestException(String message) {
            super(message);
        }
    }

    static final class SceneServiceValidationException extends IllegalArgumentException {
    }

    static final class SceneStreamException extends RuntimeException {
    }

    private static final class ManagedSseEmitter extends SseEmitter {
        private final AtomicReference<Runnable> cleanup = new AtomicReference<>();

        private ManagedSseEmitter(long timeout) {
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
