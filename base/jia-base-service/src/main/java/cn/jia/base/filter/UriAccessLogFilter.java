package cn.jia.base.filter;

import cn.jia.base.service.LogService;
import cn.jia.core.common.EsRequestWrapper;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.util.HttpUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

public class UriAccessLogFilter implements Filter {
	private String[] exclusions;

	@Override
	public void destroy() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
			throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		String requestURI = httpRequest.getRequestURI().toLowerCase();

		// Check if the request URI matches any of the exclusions
		if (HttpUtil.matchUrlPatterns(requestURI, exclusions)) {
			filterChain.doFilter(request, response);
			return;
		}

		EsRequestWrapper esRequestWrapper = new EsRequestWrapper((HttpServletRequest) request);
		LogService logService = SpringContextHolder.getBean(LogService.class);
		logService.addLog(esRequestWrapper);
		filterChain.doFilter(esRequestWrapper, response);
	}

	@Override
	public void init(FilterConfig filterConfig) {
		String exclusionsParam = filterConfig.getInitParameter("exclusions");
		if (exclusionsParam != null) {
			exclusions = exclusionsParam.split(",");
		}
	}
}
