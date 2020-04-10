package cn.jia.workflow.config;

import org.apache.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import cn.jia.core.common.EsConstants;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.Result;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.DictService;
import cn.jia.workflow.common.ErrorConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理配置类，主要将自定义异常信息以JSON格式返回给前端
 * @author chcbz
 * @date 2018年4月17日 上午9:37:06
 */
@ControllerAdvice
@ResponseBody
@Slf4j
public class ExceptionHandlerAdvice {

	@Autowired
	private DictService dictService;

	@ExceptionHandler(Exception.class)
	public Result handleException(Exception e) {
		log.error(e.getMessage(), e);
		JSONResult<Object> result = new JSONResult<>();
		result.setMsg(dictService.selectByDictTypeAndDictValue(EsConstants.DICT_TYPE_ERROR_CODE, ErrorConstants.DEFAULT_ERROR_CODE,
				LocaleContextHolder.getLocale().toString()).getName());
		result.setCode(ErrorConstants.DEFAULT_ERROR_CODE);
		return result;
	}
	
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public Result handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
		log.warn(e.getMessage(), e);
		JSONResult<Object> result = new JSONResult<>();
		result.setMsg(e.getMessage());
		result.setCode(ErrorConstants.DEFAULT_ERROR_CODE);
		return result;
	}
	
	@ExceptionHandler(AccessDeniedException.class)
	public Result handleAccessDeniedException(AccessDeniedException e) {
		log.warn(e.getMessage(), e);
		JSONResult<Object> result = new JSONResult<>();
		result.setMsg(e.getMessage());
		result.setCode(ErrorConstants.METHOD_NOT_ALLOWED);
		result.setStatus(HttpStatus.SC_METHOD_NOT_ALLOWED);
		return result;
	}
	
	@ExceptionHandler(EsRuntimeException.class)
	public Result handleEsRuntimeException(EsRuntimeException e) {
		log.warn(e.getMessageKey(), e);
		JSONResult<Object> result = new JSONResult<>();
		result.setMsg(dictService.selectByDictTypeAndDictValue(EsConstants.DICT_TYPE_ERROR_CODE, e.getMessageKey(),
				LocaleContextHolder.getLocale().toString()).getName());
		result.setCode(e.getMessageKey());
		return result;
	}
}