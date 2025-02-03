package cn.jia.base.service.impl;

import cn.jia.base.dao.LogDao;
import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.LogService;
import cn.jia.core.common.EsRequestWrapper;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.HttpUtil;
import cn.jia.core.util.JsonUtil;
import jakarta.inject.Named;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Named
public class LogServiceImpl extends BaseServiceImpl<LogDao, LogEntity> implements LogService {
    @Override
    public LogEntity addLog(EsRequestWrapper esRequestWrapper) {
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
        if ("GET".equals(esRequestWrapper.getMethod())) {
            logEntity.setParam(HttpUtil.requestParams(esRequestWrapper));
        } else if ("POST".equals(esRequestWrapper.getMethod())) {
            String body = esRequestWrapper.getBody();
            logEntity.setParam(body);
        }

        logEntity.setJiacn(EsContextHolder.getContext().getJiacn());
        logEntity.setUsername(EsContextHolder.getContext().getUsername());
        return create(logEntity);
    }
}
