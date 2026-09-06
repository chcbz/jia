package cn.jia.agent.hosting;

import cn.jia.core.entity.JsonResult;
import cn.jia.economy.hosting.HostingRentException;
import cn.jia.economy.exception.EconomyPostingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

public final class HostingRentErrors {
    private HostingRentErrors() { }
    public static ResponseEntity<JsonResult<Void>> response(RuntimeException exception) {
        int status = 503;
        String code = "HOSTING_RENT_UNAVAILABLE";
        if (exception instanceof HostingRentApplicationException application) {
            status = application.status(); code = application.code();
        } else if (exception instanceof IllegalArgumentException) {
            status = 400; code = "BAD_REQUEST";
        } else if (exception instanceof cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException) {
            status = 404; code = "HOSTING_RENT_NOT_FOUND_OR_FORBIDDEN";
        } else if (exception instanceof HostingRentException domain) {
            status = switch (domain.reason()) {
                case INVALID_COMMAND -> 400;
                case NOT_FOUND_OR_FORBIDDEN -> 404;
                case NOT_CONFIGURED, DATA_CORRUPT -> 503;
                default -> 409;
            };
            code = switch (domain.reason()) {
                case IDEMPOTENCY_CONFLICT -> "IDEMPOTENCY_CONFLICT";
                case DATA_CORRUPT -> "HOSTING_RENT_UNAVAILABLE";
                case PROVISIONING_OUTCOME_UNKNOWN, REFUND_NOT_ALLOWED -> "HOSTING_RENT_CONFLICT";
                default -> "HOSTING_RENT_" + domain.reason().name();
            };
        } else if (exception instanceof EconomyPostingException posting) {
            switch (posting.reason()) {
                case INSUFFICIENT_FUNDS, ACCOUNT_NOT_FOUND -> { status = 409; code = "INSUFFICIENT_SILVER"; }
                case IDEMPOTENCY_CONFLICT -> { status = 409; code = "IDEMPOTENCY_CONFLICT"; }
                case CONCURRENCY_CONFLICT, ESCROW_CONFLICT -> { status = 409; code = "HOSTING_RENT_CONFLICT"; }
                case FEATURE_DISABLED -> { status = 403; code = "ECONOMY_PREVIEW_DISABLED"; }
                default -> { }
            }
        }
        JsonResult<Void> body = JsonResult.failure(code, code);
        body.setStatus(status);
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }
}
