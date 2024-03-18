package cn.jia.base.filter;

import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.impl.LogServiceImpl;
import cn.jia.core.common.EsRequestWrapper;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.JsonUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class EsLogFilter implements Filter {

	@Override
	public void destroy() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
			throws IOException, ServletException {
		EsRequestWrapper esRequestWrapper = new EsRequestWrapper((HttpServletRequest) request);
		addLog(esRequestWrapper);
		filterChain.doFilter(esRequestWrapper, response);
	}

	@Override
	public void init(FilterConfig arg0) {
		// TODO Auto-generated method stub
		
	}

	private void addLog(EsRequestWrapper esRequestWrapper){
		LogEntity logEntity = new LogEntity();
		logEntity.setIp(HttpUtil.getIpAddr(esRequestWrapper));
		logEntity.setUri(esRequestWrapper.getRequestURI());
		logEntity.setMethod(esRequestWrapper.getMethod());
		logEntity.setUserAgent(esRequestWrapper.getHeader("user-agent"));
		Map<String, Object> header = new HashMap<>();
		Enumeration<String> e1 = esRequestWrapper.getHeaderNames();
		while (e1.hasMoreElements()) {
			String headerName = e1.nextElement();
			header.put(headerName, esRequestWrapper.getHeader(headerName));
		}
		logEntity.setHeader(JsonUtil.toJson(header));
		if("GET".equals(esRequestWrapper.getMethod())){
			logEntity.setParam(HttpUtil.requestParams(esRequestWrapper));
		}else if("POST".equals(esRequestWrapper.getMethod())) {
			String body = esRequestWrapper.getBody();
			logEntity.setParam(body);
		}

		logEntity.setJiacn(EsSecurityHandler.clientId());
		logEntity.setUsername(EsSecurityHandler.username());
		LogServiceImpl logService = SpringContextHolder.getBean("logServiceImpl");
		logService.create(logEntity);
	}

}
