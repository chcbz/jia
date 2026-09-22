package cn.jia.agent.api;

import cn.jia.agent.service.HallPrivateMarkService;
import cn.jia.agent.service.HallRequestDraftService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/agent/hall/items/{sourceType}/{sourceId}/mark")
public class HallPrivateMarkController {
    private final HallPrivateMarkService service;
    public HallPrivateMarkController(HallPrivateMarkService service) {this.service=Objects.requireNonNull(service);}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallPrivateMarkService.View> get(@PathVariable String sourceType,@PathVariable String sourceId,
            HttpServletRequest request,Authentication authentication) {
        var scope=HallRequestDraftController.scope(authentication);HallRequestDraftController.requireNoQuery(request);
        return HallRequestDraftController.ok(service.get(scope,sourceType,sourceId));
    }
    @PatchMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallPrivateMarkService.View> mark(@PathVariable String sourceType,@PathVariable String sourceId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request,Authentication authentication) {
        var scope=HallRequestDraftController.scope(authentication);HallRequestDraftController.requireNoQuery(request);
        Body body=HallRequestDraftController.parse(HallRequestDraftController.readBounded(request),Body.class);
        if (body.expectedRevision()==null || body.archived()==null) throw new HallRequestDraftController.RequestFailure();
        return HallRequestDraftController.ok(service.mark(scope,sourceType,sourceId,
                new HallPrivateMarkService.Command(body.expectedRevision(),body.archived(),body.viewedResultRef()),key));
    }
    @ExceptionHandler(HallRequestDraftController.AuthenticationFailure.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> authentication(HallRequestDraftController.AuthenticationFailure failure) {
        return HallRequestDraftController.error(failure.forbidden?HttpStatus.FORBIDDEN:HttpStatus.UNAUTHORIZED,
                failure.forbidden?"HALL_FORBIDDEN":"HALL_UNAUTHENTICATED","Hall access is unavailable",false,Map.of());
    }
    @ExceptionHandler(HallRequestDraftService.Failure.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> failure(HallRequestDraftService.Failure failure) {
        HttpStatus status=switch(failure.reason()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REVISION_CHANGED -> HttpStatus.PRECONDITION_FAILED;
            case IDEMPOTENCY_CONFLICT,STATE_CONFLICT,EXECUTION_CONFLICT -> HttpStatus.CONFLICT;
            case SOURCE_UNAVAILABLE,SUBMISSION_UNAVAILABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return HallRequestDraftController.error(status,"HALL_MARK_"+failure.reason().name(),
                "Private mark request is unavailable",status==HttpStatus.SERVICE_UNAVAILABLE,failure.safeDetails());
    }
    @ExceptionHandler({HallRequestDraftController.RequestFailure.class,IllegalArgumentException.class})
    public ResponseEntity<HallRequestDraftController.ErrorBody> malformed(Exception ignored) {
        return failure(new HallRequestDraftService.Failure(HallRequestDraftService.Reason.BAD_REQUEST));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<HallRequestDraftController.ErrorBody> unexpected(Exception ignored) {
        return failure(new HallRequestDraftService.Failure(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE));
    }
    public record Body(Long expectedRevision,Boolean archived,HallPrivateMarkService.ResultRef viewedResultRef) { }
}
