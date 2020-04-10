package cn.jia.core.interceptor;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import cn.jia.core.common.EsConstants;
import cn.jia.core.common.EsHandler;
import cn.jia.core.entity.Result;
import cn.jia.core.exception.EsException;
import cn.jia.core.util.CookieUtil;
import cn.jia.core.util.MD5Util;
import cn.jia.core.util.StringUtils;


public class ValidateInterceptor extends HandlerInterceptorAdapter {
	protected final static Logger logger = LoggerFactory.getLogger(ValidateInterceptor.class);
	
	@Value("${cookie.key}")
	private String cookieKey;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws EsException {
		Cookie cookies[] = request.getCookies();
		String userId = null;
		String cookieId = null;
		
		if(null!=cookies){
			userId = CookieUtil.getCookie(request, EsConstants.COOKIE_USERID);
			cookieId = CookieUtil.getCookie(request, EsConstants.COOKIE_COOKIEID);
			if(StringUtils.isNotBlank(userId) && StringUtils.isNotBlank(cookieId)){
				String MD5CookieId = MD5Util.str2Base32MD5(userId+cookieKey);
				if(MD5CookieId.equals(cookieId)){
					return true;
				}
			}
		}
		Result result = new Result();
		result.setCode("401");
		result.setMsg(EsHandler.getMessage(request, "login.dochk.nologin"));
		responseOutWithJson(response, result);
		
		return false;
	}
	
	/** 
	 * 以JSON格式输出 
	 * @param response 
	 */
	protected void responseOutWithJson(HttpServletResponse response,  
			Result result) {
	    //将实体对象转换为JSON Object转换  
	    JSONObject responseJSONObject = new JSONObject(result);  
	    response.setCharacterEncoding("UTF-8");  
	    response.setContentType("application/json; charset=utf-8");  
	    PrintWriter out = null;  
	    try {
	        out = response.getWriter();  
	        out.append(responseJSONObject.toString());  
	        logger.debug("返回是\n");  
	        logger.debug(responseJSONObject.toString());  
	    } catch (IOException e) {
	        e.printStackTrace();
	    } finally {
	        if (out != null) {
	            out.close();  
	        }
	    }
	}
}
