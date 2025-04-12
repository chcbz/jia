package cn.jia.core.interceptor;

import cn.jia.core.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NamedThreadLocal;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class HttpRequestLogInterceptor implements HandlerInterceptor {
    private static final ThreadLocal<Map<String, Long>> startTimeThreadLocal =
            new NamedThreadLocal<>("HttpRequestLog StartTime");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        final long beginTime = System.currentTimeMillis();
        startTimeThreadLocal.set(Optional.ofNullable(startTimeThreadLocal.get()).map(map -> {
            map.put(handler.getClass().getName(), beginTime);
            return map;
        }).orElseGet(() -> {
            Map<String, Long> map = new HashMap<>();
            map.put(handler.getClass().getName(), beginTime);
            return map;
        }));
        log.info("URI: {}  参数: {}  开始计时: {}",
                request.getRequestURI(),
                requestParams(request),
                TIME_FORMATTER.format(Instant.ofEpochMilli(beginTime)));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            final long beginTime = Optional.ofNullable(startTimeThreadLocal.get()).map(map ->
                    map.get(handler.getClass().getName())).orElse(0L);
            final long endTime = System.currentTimeMillis();
            final Runtime runtime = Runtime.getRuntime();

            log.info("URI: {}  计时结束：{}  耗时：{}ms  内存状态[最大:{}m 已分配:{}m 剩余可用:{}m]",
                    request.getRequestURI(),
                    TIME_FORMATTER.format(Instant.ofEpochMilli(endTime)),
                    endTime - beginTime,
                    runtime.maxMemory() / 1048576L,
                    runtime.totalMemory() / 1048576L,
                    (runtime.freeMemory() + (runtime.maxMemory() - runtime.totalMemory())) / 1048576L);
        } finally {
            Optional.ofNullable(startTimeThreadLocal.get()).ifPresent(map -> {
                map.remove(handler.getClass().getName());
                if (map.isEmpty()) {
                    startTimeThreadLocal.remove();
                }
            });
        }
    }

    private String requestParams(HttpServletRequest httpRequest) {
        try {
            Map<String, String> paramMap = new HashMap<>();
            Enumeration<String> enums = httpRequest.getParameterNames();
            while (enums.hasMoreElements()) {
                String paramName = enums.nextElement();
                paramMap.put(paramName, httpRequest.getParameter(paramName));
            }
            return JsonUtil.toJson(Map.of("param", paramMap));
        } catch (Exception e) {
            log.warn("请求参数序列化失败", e);
            return "{}";
        }
    }
}
