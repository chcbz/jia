package cn.jia.chat.api;

import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.chat.service.recommendation.AssigneeRecommendationService;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Objects;
import static cn.jia.chat.service.recommendation.AssigneeRecommendation.*;

/** Replaces (does not coexist with) AgentController's rule-scored recommendation endpoint. */
@RestController
@RequestMapping("/agent/tasks")
public final class AssigneeRecommendationController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AssigneeRecommendationController.class);
    private final AssigneeRecommendationService recommendations;
    private final TenantScopeResolver tenants;
    public AssigneeRecommendationController(AssigneeRecommendationService recommendations, TenantScopeResolver tenants) {
        this.recommendations = recommendations; this.tenants = tenants;
    }
    @PostMapping("/{taskId}/recommend")
    public ResponseEntity<JsonResult<Result>> recommend(@PathVariable String taskId,
            @RequestBody JsonRequest<Request> request, Authentication authentication) {
        var actor = HostingRentHttp.actor(authentication);
        var context = EsContextHolder.getContext();
        if (context == null || !Objects.equals(tenants.resolve(authentication), actor.tenantId())
                || !Objects.equals(context.getTenantId(), actor.tenantId())
                || !Objects.equals(context.getClientId(), actor.clientId())
                || !Objects.equals(context.getJiacn(), actor.ownerJiacn())) throw new Failure(404, "RECOMMENDATION_NOT_FOUND");
        if (request == null || request.data() == null) throw new Failure(400, "BAD_REQUEST");
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(JsonResult.success(recommendations.recommend(actor, taskId, request.data())));
    }
    @ExceptionHandler(Failure.class)
    public ResponseEntity<JsonResult<Void>> failure(Failure error) {
        return ResponseEntity.status(error.status()).header("Cache-Control", "private, no-store")
                .body(JsonResult.failure(error.code(), error.code()));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<JsonResult<Void>> malformedRequest() {
        return failure(new Failure(400, "BAD_REQUEST"));
    }
    @ExceptionHandler(HostingRentApplicationException.class)
    public ResponseEntity<JsonResult<Void>> identity(HostingRentApplicationException error) {
        return failure(new Failure(error.status() == 401 ? 401 : 404,
                error.status() == 401 ? "UNAUTHENTICATED" : "RECOMMENDATION_NOT_FOUND"));
    }
    @ExceptionHandler(AgentServiceImpl.AgentBizException.class)
    public ResponseEntity<JsonResult<Void>> task(AgentServiceImpl.AgentBizException error) {
        return failure(new Failure(404, "RECOMMENDATION_NOT_FOUND"));
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(RuntimeException error) {
        LOG.warn("RECOMMENDATION_UNAVAILABLE requestId={} category={}", org.slf4j.MDC.get("request_id"), error.getClass().getSimpleName());
        return failure(new Failure(503, "RECOMMENDATION_UNAVAILABLE"));
    }
}
