package cn.jia.core.config;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.errcode.ErrCodeHolder;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局异常处理配置类，主要将自定义异常信息以JSON格式返回给前端
 *
 * @author chcbz 2018年4月17日 上午9:37:06
 */
@ControllerAdvice
@ResponseBody
public class ExceptionHandlerAdvice {
    private static final Logger log = LoggerFactory.getLogger(ExceptionHandlerAdvice.class);

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e) {
        if (isClientAbort(e)) {
            log.warn("Client disconnected before response completed: {}", findRootCauseMessage(e));
            return null; // The peer is gone; do not fabricate a successful HTTP terminal state.
        }
        log.error(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.DEFAULT_ERROR_CODE));
        result.setCode(EsErrorConstants.DEFAULT_ERROR_CODE.getCode());
        return response(result, e instanceof org.springframework.web.ErrorResponse error ? error.getStatusCode().value() : 500, 200);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Object handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.PARAMETER_INCORRECT));
        result.setCode(EsErrorConstants.PARAMETER_INCORRECT.getCode());
        return response(result, 400, 200);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleMissingServletRequestParameterException(HttpMessageNotReadableException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.PARAMETER_INCORRECT));
        result.setCode(EsErrorConstants.PARAMETER_INCORRECT.getCode());
        if (java.util.Set.of("PROTOCOL_INVALID_ENVELOPE", "PROTOCOL_UNSUPPORTED_VERSION", "PROTOCOL_REQUEST_ID_MISMATCH").contains(e.getMessage())) {
            result.setCode(e.getMessage());
        }
        return response(result, 400, 200);
    }

    @ExceptionHandler(EsRuntimeException.class)
    public Object handleEsRuntimeException(EsRuntimeException e) {
        log.warn(e.getMessageKey(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setCode(e.getMessageKey());
        result.setMsg("Request rejected");
        int status = switch (e.getMessageKey()) {
            case "E001", "E404" -> 404;
            case "E002" -> 409;
            case "E003" -> 400;
            case "E004" -> 422;
            case "E401" -> 401;
            case "E403" -> 403;
            case "E405" -> 405;
            case "E408" -> 408;
            default -> 500;
        };
        return response(result, status, 200);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDeniedException(AccessDeniedException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.UNAUTHORIZED));
        result.setCode(EsErrorConstants.UNAUTHORIZED.getCode());
        result.setCode(EsErrorConstants.FORBIDDEN.getCode());
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.FORBIDDEN));
        return response(result, 403, 401);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.NOT_FOUND));
        result.setCode(EsErrorConstants.NOT_FOUND.getCode());
        return response(result, 404, 404);
    }

    private Object response(JsonResult<?> body, int status, int previousExternalStatus) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                ? servlet.getRequest() : null;
        if (ApiExternalCallbacks.matches(request)) {
            // Preserve the pre-migration unhandled-error wire at external entrypoints. Their native
            // controller ACK/challenge handling remains untouched; fixing it is a separate change.
            java.util.Map<String, Object> external = new java.util.LinkedHashMap<>();
            external.put("code", previousExternalStatus == 401 ? "E401" : body.getCode());
            if (body.getMsg() != null) external.put("msg", body.getMsg());
            external.put("status", previousExternalStatus);
            return ResponseEntity.status(previousExternalStatus).body(external);
        }
        if (request != null) body.setRequestId(cn.jia.core.filter.RequestIdFilter.current(request));
        return ResponseEntity.status(status).body(body);
    }

    private boolean isClientAbort(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String className = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(className)) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("broken pipe")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String findRootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current != null ? current.getMessage() : throwable.getMessage();
    }
}
