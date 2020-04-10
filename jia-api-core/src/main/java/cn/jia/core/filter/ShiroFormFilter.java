package cn.jia.core.filter;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.web.filter.authc.FormAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.jia.core.entity.JSONResult;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.JSONUtil;

/**
 * 
 * 1.自定义角色鉴权过滤器(满足其中一个角色则认证通过) 2.扩展异步请求认证提示功能;
 * 
 * @author shadow
 * 
 */
public class ShiroFormFilter extends FormAuthenticationFilter {
	private static final Logger log = LoggerFactory.getLogger(FormAuthenticationFilter.class);

	protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;
		
		if (isLoginRequest(request, response)) {
			if (isLoginSubmission(request, response)) {
				if (log.isTraceEnabled()) {
					log.trace("Login submission detected.  Attempting to execute login.");
				}
				return executeLogin(request, response);
			}
			if (log.isTraceEnabled()) {
				log.trace("Login page view.");
			}

			return true;
		}

		if (log.isTraceEnabled()) {
			log.trace(
					"Attempting to access a path which requires authentication.  Forwarding to the Authentication url ["
							+ getLoginUrl() + "]");
		}
		log.trace("Page path: " + getPathWithinApplication(request));
		if (HttpUtil.isAjaxRequest(httpRequest) && getPathWithinApplication(request).endsWith(".json")) {
			JSONResult<String> result = new JSONResult<>("", "您尚未登录或登录时间过长,请重新登录!", "302", 302);
			result.setLocation(getLoginUrl());
			HttpUtil.responseJson(httpResponse, JSONUtil.toJson(result));
		} else {
			saveRequestAndRedirectToLogin(request, response);
		}
		
		return false;
	}
}