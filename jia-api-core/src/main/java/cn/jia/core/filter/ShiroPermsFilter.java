package cn.jia.core.filter;

import java.io.IOException;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.StringUtils;
import org.apache.shiro.web.filter.authz.PermissionsAuthorizationFilter;
import org.apache.shiro.web.util.WebUtils;

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
public class ShiroPermsFilter extends PermissionsAuthorizationFilter {

	protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws IOException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		Subject subject = getSubject(request, response);

		if (subject.getPrincipal() == null) {
			if (HttpUtil.isAjaxRequest(httpRequest)) {
				HttpUtil.responseJson(httpResponse, JSONUtil.toJson(new JSONResult<>(null, "您没有足够的权限执行该操作!", "403")));
			} else {
				saveRequestAndRedirectToLogin(request, response);
			}
		} else {
			if (HttpUtil.isAjaxRequest(httpRequest)) {
				HttpUtil.responseJson(httpResponse, JSONUtil.toJson(new JSONResult<>(null, "您没有足够的权限执行该操作!", "403")));
			} else {
				String unauthorizedUrl = getUnauthorizedUrl();
				if (StringUtils.hasText(unauthorizedUrl)) {
					WebUtils.issueRedirect(request, response, unauthorizedUrl);
				} else {
					WebUtils.toHttp(response).sendError(401);
				}
			}
		}
		return false;
	}
}