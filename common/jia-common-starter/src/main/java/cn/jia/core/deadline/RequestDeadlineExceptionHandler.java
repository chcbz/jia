package cn.jia.core.deadline;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


/** Maps only explicitly safe timeout failures to a bounded 503/504 JsonResult contract. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDeadlineExceptionHandler {

    @ExceptionHandler(SafeRequestTimeoutException.class)
    public ResponseEntity<JsonResult<TimeoutErrorContract>> handle(
            SafeRequestTimeoutException exception, HttpServletRequest request) {
        String requestId = requestId(request);
        SafeRequestTimeoutException.Failure failure = exception.failure();
        TimeoutErrorContract detail = new TimeoutErrorContract(
                requestId, failure.name(), exception.dependency().name(), exception.retryable());
        JsonResult<TimeoutErrorContract> result = new JsonResult<>(
                detail, failure.safeMessage(), failure.code());
        result.setRequestId(requestId);
        return ResponseEntity.status(failure.httpStatus())
                .header(RequestIdFilter.HEADER_NAME, requestId)
                .body(result);
    }

    private static String requestId(HttpServletRequest request) {
        return RequestIdFilter.current(request);
    }
}
