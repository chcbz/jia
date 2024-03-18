package cn.jia.core.interceptor;

import cn.jia.core.annotation.Token;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.exception.EsCheckedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.lang.reflect.Method;
import java.util.UUID;

public class TokenInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    	if (handler instanceof HandlerMethod handlerMethod) {
            Method method = handlerMethod.getMethod();
            Token annotation = method.getAnnotation(Token.class);
            if (annotation != null) {
                boolean needSaveSession = annotation.save();
                if (needSaveSession) {
                    request.getSession(false).setAttribute("token", UUID.randomUUID().toString());
                }
                boolean needRemoveSession = annotation.remove();
                if (needRemoveSession) {
                    if (isRepeatSubmit(request)) {
                        throw new EsCheckedException(EsErrorConstants.DUPLICATE_SUBMISSION.getCode());
                    }
                    request.getSession(false).removeAttribute("token");
                }
            }
        }
        return true;
    }

    private boolean isRepeatSubmit(HttpServletRequest request) {
        String serverToken = (String) request.getSession(false).getAttribute("token");
        if (serverToken == null) {
            return true;
        }
        String clinetToken = request.getParameter("token");
        if (clinetToken == null) {
            return true;
        }
        return !serverToken.equals(clinetToken);
    }
}