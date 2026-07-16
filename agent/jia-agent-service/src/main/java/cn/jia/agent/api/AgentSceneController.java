package cn.jia.agent.api;

import cn.jia.agent.common.AgentSceneConstants;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/agent/scenes")
@RequiredArgsConstructor
public class AgentSceneController {
    private final AgentSceneService sceneService;

    @GetMapping("/{sceneId}/snapshot")
    public JsonResult<AgentSceneSnapshotDTO> snapshot(@PathVariable String sceneId) {
        return JsonResult.success(sceneService.snapshot(requireSceneId(sceneId)));
    }

    @GetMapping(value = "/{sceneId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> events(
            @PathVariable String sceneId,
            @RequestParam(defaultValue = "0") long sinceVersion) {
        String requiredSceneId = requireSceneId(sceneId);
        if (sinceVersion < 0) {
            throw new SceneRequestException("sinceVersion must be nonnegative");
        }
        return sceneService.events(requiredSceneId, sinceVersion)
                .map(AgentSceneController::formatSse);
    }

    @PostMapping("/{sceneId}/phases")
    public JsonResult<AgentScenePhaseResultDTO> phase(
            @PathVariable String sceneId,
            @RequestBody(required = false) AgentScenePhaseReportDTO request) {
        return JsonResult.success(sceneService.reportPhase(
                requireSceneId(sceneId), requirePhaseReport(request)));
    }

    @ExceptionHandler(SceneRequestException.class)
    public JsonResult<Void> handleSceneRequestException(SceneRequestException exception) {
        JsonResult<Void> result = JsonResult.failure("BAD_REQUEST", exception.getMessage());
        result.setStatus(400);
        return result;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public JsonResult<Void> handleUnreadableBody() {
        JsonResult<Void> result = JsonResult.failure("BAD_REQUEST", "Invalid scene request body");
        result.setStatus(400);
        return result;
    }

    static String formatSse(AgentSceneEventDTO source) {
        AgentSceneEventDTO event = safeEvent(source);
        String json = JsonUtil.toSafeJson(event);
        if (json == null || json.indexOf('\r') >= 0 || json.indexOf('\n') >= 0) {
            throw new IllegalStateException("Unable to serialize safe scene event");
        }
        return "id: " + event.getSceneVersion()
                + "\nevent: " + event.getEventType()
                + "\ndata: " + json + "\n\n";
    }

    private static AgentSceneEventDTO safeEvent(AgentSceneEventDTO source) {
        if (source == null || source.getSceneVersion() == null || source.getSceneVersion() < 0) {
            throw new IllegalArgumentException("Invalid scene event version");
        }
        String eventType = source.getEventType();
        if (eventType == null || !eventType.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid scene event type");
        }
        AgentSceneEventDTO safe = new AgentSceneEventDTO();
        safe.setSceneVersion(source.getSceneVersion());
        safe.setEventType(eventType);
        safe.setState(AgentSceneStateDTO.copyOf(source.getState()));
        safe.setOccurredAt(source.getOccurredAt());
        return safe;
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

    static final class SceneRequestException extends IllegalArgumentException {
        private SceneRequestException(String message) {
            super(message);
        }
    }
}
