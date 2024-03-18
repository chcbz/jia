package cn.jia.wx.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractPayOrderParseService implements PayOrderParseService {
    static final Map<String, String> HANDLER = new ConcurrentHashMap<>();
    protected abstract String getType();

    protected void register() {
        HANDLER.put(getType(), getClass().getName());
    }
}
