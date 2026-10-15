package cn.jia.core.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/** Exact external entrypoints only; self-owned wx/oauth management operations are not exempt. */
final class ApiExternalCallbacks {
    private static final Set<String> ENTRIES = Set.of(
            "GET /oauth/third-party/wxmp",
            "GET /oauth/third-party/weixin",
            "GET /oauth/third-party/weibo",
            "GET /oauth/third-party/github",
            "GET /sms/receive",
            "GET /wx/mp/checksignature",
            "POST /wx/mp/checksignature",
            "POST /wx/pay/parseOrderNotifyResult",
            "POST /wx/pay/parseRefundNotifyResult",
            "POST /wx/pay/parseScanPayNotifyResult");
    private ApiExternalCallbacks() {}
    static boolean matches(HttpServletRequest request) {
        if (request == null) return false;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ENTRIES.contains(request.getMethod() + " " + path);
    }
}
