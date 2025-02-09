package cn.jia.core.context;

import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.ThreeDesUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

public class EsContextHolder {
    private static final ThreadLocal<EsContext> CONTEXT = new ThreadLocal<>();

    public static EsContext getContext() {
        EsContext esContext = CONTEXT.get();
        if (esContext == null) {
            esContext = new EsContext();
            CONTEXT.set(esContext);
        }
        return esContext;
    }

    public static EsContext getContext(HttpServletRequest request) {
        for (Cookie cookie : request.getCookies()) {
            if ("CTX".equals(cookie.getName())) {
                EsContext esContext = JsonUtil.fromJson(ThreeDesUtil.decrypt3Des(cookie.getValue()), EsContext.class);
                CONTEXT.set(esContext);
                return esContext;
            }
        }
        return getContext();
    }

    public static Cookie genCookie() {
        EsContext context = getContext();
        return new Cookie("CTX", ThreeDesUtil.encrypt3Des(JsonUtil.toJson(context)));
    }
}
