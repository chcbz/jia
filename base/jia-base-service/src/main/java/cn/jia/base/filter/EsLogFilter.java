package cn.jia.base.filter;

import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.LogService;
import cn.jia.core.common.EsRequestWrapper;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.JsonUtil;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@WebFilter(filterName = "logFilter", urlPatterns = "/*",
		initParams = {
				@WebInitParam(name = "exclusions", value = "*.js,*.gif,*.jpg,*.bmp,*.png,*.css,*.ico,/druid/*")//忽略资源
		}
)
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

		logEntity.setJiacn(EsContextHolder.getContext().getJiacn());
		logEntity.setUsername(EsContextHolder.getContext().getUsername());
		LogService logService = SpringContextHolder.getBean(LogService.class);
		logService.create(logEntity);
	}

}
