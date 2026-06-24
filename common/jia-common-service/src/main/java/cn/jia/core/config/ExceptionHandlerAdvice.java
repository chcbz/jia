package cn.jia.core.config;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.Result;
import cn.jia.core.errcode.ErrCodeHolder;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.StringUtil;
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
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        log.error(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.DEFAULT_ERROR_CODE));
        result.setCode(EsErrorConstants.DEFAULT_ERROR_CODE.getCode());
        return result;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(e.getMessage());
        result.setCode(EsErrorConstants.PARAMETER_INCORRECT.getCode());
        return result;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleMissingServletRequestParameterException(HttpMessageNotReadableException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(e.getMessage());
        result.setCode(EsErrorConstants.PARAMETER_INCORRECT.getCode());
        return result;
    }

    @ExceptionHandler(EsRuntimeException.class)
    public Result handleEsRuntimeException(EsRuntimeException e) {
        log.warn(e.getMessageKey(), e);
        JsonResult<Object> result = new JsonResult<>();
        if (StringUtil.isNotEmpty(e.getMessage())) {
            result.setMsg(e.getMessage());
        } else {
            result.setMsg(ErrCodeHolder.getMessage(e.getMessageKey()));
        }
        result.setCode(e.getMessageKey());
        return result;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result> handleAccessDeniedException(AccessDeniedException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.UNAUTHORIZED));
        result.setCode(EsErrorConstants.UNAUTHORIZED.getCode());
        result.setStatus(HttpStatus.UNAUTHORIZED.value());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.NOT_FOUND));
        result.setCode(EsErrorConstants.NOT_FOUND.getCode());
        result.setStatus(HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
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
