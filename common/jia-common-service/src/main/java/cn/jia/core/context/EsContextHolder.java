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
        EsContext fresh = new EsContext();
        CONTEXT.set(fresh);
        for (Cookie cookie : Optional.ofNullable(request.getCookies()).orElse(new Cookie[0])) {
            if (!"CTX".equals(cookie.getName())) {
                continue;
            }
            try {
                EsContext decoded = JsonUtil.fromJson(ThreeDesUtil.decrypt3Des(cookie.getValue()), EsContext.class);
                if (decoded != null) {
                    CONTEXT.set(decoded);
                }
            } catch (RuntimeException ignored) {
                CONTEXT.set(fresh);
            }
            break;
        }
        return getContext();
    }

    public static Cookie genCookie() {
        EsContext context = getContext();
        Cookie ctx = new Cookie("CTX", ThreeDesUtil.encrypt3Des(JsonUtil.toJson(context)));
        ctx.setHttpOnly(true);
        ctx.setSecure(true);
        ctx.setPath("/");
        ctx.setAttribute("SameSite", "Lax");
        return ctx;
    }

    public static void setContext(EsContext esContext) {
        CONTEXT.set(esContext);
    }

    public static void clearContext() {
        CONTEXT.remove();
    }
}
