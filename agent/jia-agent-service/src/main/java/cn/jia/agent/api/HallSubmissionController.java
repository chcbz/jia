package cn.jia.agent.api;

import cn.jia.agent.service.HallRequestDraftService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Read-only Hall submission reconciliation and private-case recovery adapter. */
@RestController
@RequestMapping("/agent/hall")
public class HallSubmissionController {
    private final HallRequestDraftService service;

    public HallSubmissionController(HallRequestDraftService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(value = "/submissions/request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallRequestDraftService.SubmissionReceipt> submission(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        HallRequestDraftController.requireNoQuery(request);
        return HallRequestDraftController.ok(service.getSubmissionByIdempotencyKey(
                HallRequestDraftController.scope(authentication), idempotencyKey));
    }

    @GetMapping(value = "/cases/{caseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallRequestDraftService.CaseView> caseView(@PathVariable String caseId,
            HttpServletRequest request, Authentication authentication) {
        HallRequestDraftController.requireNoQuery(request);
        return HallRequestDraftController.ok(service.getCase(
                HallRequestDraftController.scope(authentication), caseId));
    }

    @ExceptionHandler(HallRequestDraftController.AuthenticationFailure.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> authentication(
            HallRequestDraftController.AuthenticationFailure failure) {
        return HallRequestDraftController.error(
                failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "HALL_FORBIDDEN" : "HALL_UNAUTHENTICATED",
                "Hall access is unavailable", false, Map.of());
    }

    @ExceptionHandler({HallRequestDraftController.RequestFailure.class,
            IllegalArgumentException.class})
    public ResponseEntity<HallRequestDraftController.ErrorBody> malformed(Exception ignored) {
        return HallRequestDraftController.error(HttpStatus.BAD_REQUEST,
                "HALL_BAD_REQUEST", "Invalid Hall request", false, Map.of());
    }

    @ExceptionHandler(HallRequestDraftService.Failure.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> failure(
            HallRequestDraftService.Failure failure) {
        return switch (failure.reason()) {
            case BAD_REQUEST -> HallRequestDraftController.error(HttpStatus.BAD_REQUEST,
                    "HALL_BAD_REQUEST", "Invalid Hall request", false, failure.safeDetails());
            case NOT_FOUND -> HallRequestDraftController.error(HttpStatus.NOT_FOUND,
                    "HALL_RESOURCE_NOT_FOUND", "Hall resource is unavailable", false, Map.of());
            case IDEMPOTENCY_CONFLICT -> HallRequestDraftController.error(HttpStatus.CONFLICT,
                    "HALL_SUBMISSION_IDEMPOTENCY_CONFLICT",
                    "Operation key conflicts with a different request", false, Map.of());
            case STATE_CONFLICT -> HallRequestDraftController.error(HttpStatus.CONFLICT,
                    "HALL_SUBMISSION_STATE_CONFLICT",
                    "Hall submission is not available in its current state", false, Map.of());
            case REVISION_CHANGED -> HallRequestDraftController.error(
                    HttpStatus.PRECONDITION_FAILED, "HALL_DRAFT_REVISION_CHANGED",
                    "Hall draft changed; reload before submitting", false, Map.of());
            case SOURCE_UNAVAILABLE -> HallRequestDraftController.error(
                    HttpStatus.UNPROCESSABLE_ENTITY, "HALL_DRAFT_SOURCE_UNAVAILABLE",
                    "Referenced source is unavailable", false, failure.safeDetails());
            case SUBMISSION_UNAVAILABLE -> HallRequestDraftController.error(
                    HttpStatus.UNPROCESSABLE_ENTITY, "HALL_SUBMISSION_KIND_UNAVAILABLE",
                    "This Hall submission kind is not available", false, Map.of());
            case EXECUTION_CONFLICT -> HallRequestDraftController.error(HttpStatus.CONFLICT,
                    "HALL_SUBMISSION_EXECUTION_CONFLICT",
                    "The referenced execution changed", false, Map.of());
            case STORAGE_UNAVAILABLE -> HallRequestDraftController.error(
                    HttpStatus.SERVICE_UNAVAILABLE, "HALL_STORAGE_UNAVAILABLE",
                    "Hall storage is temporarily unavailable", true, Map.of());
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> unexpected(Exception ignored) {
        return HallRequestDraftController.error(HttpStatus.SERVICE_UNAVAILABLE,
                "HALL_STORAGE_UNAVAILABLE", "Hall storage is temporarily unavailable",
                true, Map.of());
    }
}
