package cn.jia.core.filter;

import cn.jia.core.common.EsRequestWrapper;
import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.entity.Log;
import cn.jia.core.service.LogService;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.JSONUtil;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class EsLogFilter implements Filter{

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
		Log log = new Log();
		log.setIp(HttpUtil.getIpAddr(esRequestWrapper));
		log.setTime(DateUtil.genTime(new Date()));
		log.setUri(esRequestWrapper.getRequestURI());
		log.setMethod(esRequestWrapper.getMethod());
		log.setUserAgent(esRequestWrapper.getHeader("user-agent"));
		Map<String, Object> header = new HashMap<>();
		Enumeration<String> e1 = esRequestWrapper.getHeaderNames();
		while (e1.hasMoreElements()) {
			String headerName = e1.nextElement();
			header.put(headerName, esRequestWrapper.getHeader(headerName));
		}
		log.setHeader(JSONUtil.toJson(header));
		if("GET".equals(esRequestWrapper.getMethod())){
			log.setParam(HttpUtil.requestParams(esRequestWrapper));
		}else if("POST".equals(esRequestWrapper.getMethod())) {
			String body = esRequestWrapper.getBody();
			log.setParam(body);
		}

		log.setJiacn(EsSecurityHandler.clientId());
		log.setUsername(EsSecurityHandler.username());
		LogService logService = SpringContextHolder.getBean("logServiceImpl");
		logService.insert(log);
	}

}
