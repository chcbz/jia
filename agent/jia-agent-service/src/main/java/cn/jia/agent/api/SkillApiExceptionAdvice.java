package cn.jia.agent.api;

import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.economy.exception.EconomyPostingException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** HTTP translation for skill management and package transport only. Unknown failures use
 * the existing common advice; ownership checks remain in the application services. */
@RestControllerAdvice(assignableTypes = {SkillMarketplaceController.class, SkillPackageController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class SkillApiExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(SkillApiExceptionAdvice.class);

    @ExceptionHandler(AgentBizException.class)
    public ResponseEntity<JsonResult<Void>> agent(AgentBizException error, HttpServletRequest request) {
        return switch (error.getCode()) {
            case AgentErrorConstants.AGENT_FORBIDDEN -> failure(403, "AGENT_FORBIDDEN", error, request);
            case AgentErrorConstants.AGENT_NOT_FOUND -> failure(404, "AGENT_NOT_FOUND", error, request);
            default -> failure(500, "INTERNAL_ERROR", error, request);
        };
    }

    @ExceptionHandler(SkillMarketplaceException.class)
    public ResponseEntity<JsonResult<Void>> skill(SkillMarketplaceException error, HttpServletRequest request) {
        return switch (error.code()) {
            case "AGENT_VERSION_CONFLICT" -> failure(409, "SKILL_STATE_CONFLICT", error, request);
            case "SKILL_MARKETPLACE_DISABLED", "SKILL_PACKAGE_UNAVAILABLE", "SKILL_PACKAGE_PREPARE_IN_TRANSACTION" ->
                    failure(503, "SKILL_STATE_UNAVAILABLE", error, request);
            default -> failure(error.status(), error.code(), error, request);
        };
    }

    @ExceptionHandler(HostingRentApplicationException.class)
    public ResponseEntity<JsonResult<Void>> hosting(HostingRentApplicationException error, HttpServletRequest request) {
        String code = switch (error.status()) {
            case 401 -> "UNAUTHENTICATED";
            case 400 -> "BAD_REQUEST";
            default -> "AGENT_FORBIDDEN";
        };
        return failure(error.status(), code, error, request);
    }

    @ExceptionHandler(EconomyPostingException.class)
    public ResponseEntity<JsonResult<Void>> economy(EconomyPostingException error, HttpServletRequest request) {
        // Preserve the existing skill API money-error contract, not a new ledger policy.
        return switch (error.reason()) {
            case INSUFFICIENT_FUNDS -> failure(422, "INSUFFICIENT_FUNDS", error, request);
            case IDEMPOTENCY_CONFLICT -> failure(409, "IDEMPOTENCY_CONFLICT", error, request);
            case FEATURE_DISABLED -> failure(403, "AGENT_FORBIDDEN", error, request);
            case ACCOUNT_NOT_FOUND, ACCOUNT_NOT_ACTIVE, JOURNAL_ID_CONFLICT, CONCURRENCY_CONFLICT, ESCROW_CONFLICT ->
                    failure(409, "SKILL_STATE_CONFLICT", error, request);
            default -> failure(500, "INTERNAL_ERROR", error, request);
        };
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<JsonResult<Void>> unavailable(RuntimeException error, HttpServletRequest request) {
        return failure(503, "SKILL_STATE_UNAVAILABLE", error, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonResult<Void>> argument(IllegalArgumentException error, HttpServletRequest request) {
        return "AGENT_RUNTIME_UNAUTHENTICATED".equals(error.getMessage())
                ? failure(401, "UNAUTHENTICATED", error, request)
                : failure(400, "BAD_REQUEST", error, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<JsonResult<Void>> unreadable(HttpMessageNotReadableException error, HttpServletRequest request) {
        return failure(400, "BAD_REQUEST", error, request);
    }

    private ResponseEntity<JsonResult<Void>> failure(int status, String code, RuntimeException error,
            HttpServletRequest request) {
        String requestId = RequestIdFilter.current(request);
        if (status >= 500) {
            log.error("Skill API failure: request_id={}, route={}, code={}", requestId, request.getRequestURI(), code, error);
        } else {
            log.warn("Skill API rejected: request_id={}, route={}, code={}", requestId, request.getRequestURI(), code);
        }
        JsonResult<Void> body = JsonResult.failure(code, "Skill operation unavailable");
        body.setRequestId(requestId);
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }
}
