package cn.jia.base.config;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.Result;
import cn.jia.core.errcode.ErrCodeHolder;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 全局异常处理配置类，主要将自定义异常信息以JSON格式返回给前端
 *
 * @author chcbz 2018年4月17日 上午9:37:06
 */
@ControllerAdvice
@ResponseBody
@Slf4j
public class ExceptionHandlerAdvice {
    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
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
    public Result handleAccessDeniedException(AccessDeniedException e) {
        log.warn(e.getMessage(), e);
        JsonResult<Object> result = new JsonResult<>();
        result.setMsg(ErrCodeHolder.getMessage(EsErrorConstants.UNAUTHORIZED));
        result.setCode(EsErrorConstants.UNAUTHORIZED.getCode());
        return result;
    }
}