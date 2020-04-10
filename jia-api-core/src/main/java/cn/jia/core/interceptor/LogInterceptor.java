package cn.jia.core.interceptor;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NamedThreadLocal;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import cn.jia.core.exception.EsException;
import cn.jia.core.util.JSONUtil;

/**
 * 日志拦截器，获取所有请求的日志
 * @author chcbz
 * @date 2017年3月22日 下午12:19:03
 */
public class LogInterceptor extends HandlerInterceptorAdapter {
	protected final static Logger logger = LoggerFactory.getLogger(LogInterceptor.class);
	
	private static final ThreadLocal<Long> startTimeThreadLocal = new NamedThreadLocal<Long>("ThreadLocal StartTime");

	
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws EsException, IOException {
		logger.info("HttpServletRequest: " + request.getRequestURI() + " " + requestParams(request));
		long beginTime = System.currentTimeMillis();// 1、开始时间
		startTimeThreadLocal.set(beginTime); // 线程绑定变量（该数据只有当前请求的线程可见）
		logger.info("开始计时: {}  URI: {}", new SimpleDateFormat("hh:mm:ss.SSS").format(beginTime),
				request.getRequestURI());
		return true;
	}
    
	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
			throws Exception {
		// 打印JVM信息。
		long beginTime = startTimeThreadLocal.get();// 得到线程绑定的局部变量（开始时间）
		long endTime = System.currentTimeMillis(); // 2、结束时间
		logger.info("计时结束：{}  耗时：{}  URI: {}  最大内存: {}m  已分配内存: {}m  已分配内存中的剩余空间: {}m  最大可用内存: {}m",
				new SimpleDateFormat("hh:mm:ss.SSS").format(endTime), endTime - beginTime, request.getRequestURI(),
				Runtime.getRuntime().maxMemory() / 1024 / 1024, Runtime.getRuntime().totalMemory() / 1024 / 1024,
				Runtime.getRuntime().freeMemory() / 1024 / 1024, (Runtime.getRuntime().maxMemory()
						- Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory()) / 1024 / 1024);
	}

    
    private String requestParams(HttpServletRequest httpRequest) {
        // 请求参数日志信息
        Map<String, Object> params = new HashMap<String, Object>();
        Enumeration<?> enume = httpRequest.getParameterNames();
        if (null != enume) {
            Map<String, String> hpMap = new HashMap<String, String>();
            while (enume.hasMoreElements()) {
                Object element = enume.nextElement();
                if (null != element) {
                    String paramName = (String) element;
                    String paramValue = httpRequest.getParameter(paramName);
                    hpMap.put(paramName, paramValue);
                }
            }
            params.put("param", hpMap);
        }
        String paramsStr = JSONUtil.toJson(params);
        return paramsStr;
    }
}