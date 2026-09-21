package cn.jia.agent.api;

import cn.jia.agent.service.HallReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/agent/hall")
public class HallReadController {
    private final HallReadService service;
    public HallReadController(HallReadService service) { this.service = Objects.requireNonNull(service); }

    @GetMapping(value = "/overview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallReadService.Overview> overview(HttpServletRequest request,
            Authentication authentication) {
        var scope = HallRequestDraftController.scope(authentication);
        HallRequestDraftController.requireNoQuery(request);
        return HallRequestDraftController.ok(service.overview(scope));
    }

    @GetMapping(value = "/items", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallReadService.Items> items(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String view, @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor, HttpServletRequest request,
            Authentication authentication) {
        var scope = HallRequestDraftController.scope(authentication);
        if (request == null || request.getParameterMap().keySet().stream()
                .anyMatch(key -> !Set.of("kind", "view", "q", "cursor").contains(key))
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) {
            throw new HallRequestDraftController.RequestFailure();
        }
        return HallRequestDraftController.ok(service.items(scope, kind, view, q, cursor));
    }

    @ExceptionHandler(HallRequestDraftController.AuthenticationFailure.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> authentication(
            HallRequestDraftController.AuthenticationFailure failure) {
        return HallRequestDraftController.error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "HALL_FORBIDDEN" : "HALL_UNAUTHENTICATED",
                "Hall access is unavailable", false, Map.of());
    }
    @ExceptionHandler(HallReadService.Failure.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> failure(HallReadService.Failure failure) {
        if (failure.reason() == HallReadService.Reason.VIEW_UNAVAILABLE) {
            return HallRequestDraftController.error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "HALL_READ_VIEW_UNAVAILABLE", "Private archive marks are not available", false, Map.of());
        }
        return malformed(failure);
    }
    @ExceptionHandler({HallRequestDraftController.RequestFailure.class, IllegalArgumentException.class})
    public ResponseEntity<HallRequestDraftController.ErrorBody> malformed(Exception ignored) {
        return HallRequestDraftController.error(HttpStatus.BAD_REQUEST, "HALL_READ_BAD_REQUEST",
                "Invalid Hall read request", false, Map.of());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> unexpected(Exception ignored) {
        return HallRequestDraftController.error(HttpStatus.SERVICE_UNAVAILABLE, "HALL_READ_UNAVAILABLE",
                "Hall read is temporarily unavailable", true, Map.of());
    }
}
