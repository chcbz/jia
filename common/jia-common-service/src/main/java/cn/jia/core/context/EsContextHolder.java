package cn.jia.core.context;

import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.ThreeDesUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

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
        for (Cookie cookie : Optional.ofNullable(request.getCookies()).orElse(new Cookie[0])) {
            if ("CTX".equals(cookie.getName())) {
                EsContext esContext = JsonUtil.fromJson(ThreeDesUtil.decrypt3Des(cookie.getValue()), EsContext.class);
                CONTEXT.set(esContext);
                return getContext();
            }
        }
        return getContext();
    }

    public static Cookie genCookie() {
        EsContext context = getContext();
        return new Cookie("CTX", ThreeDesUtil.encrypt3Des(JsonUtil.toJson(context)));
    }

    public static void setContext(EsContext esContext) {
    	CONTEXT.set(esContext);
    }
}
