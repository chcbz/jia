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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Named
public class LogServiceImpl extends BaseServiceImpl<LogDao, LogEntity> implements LogService {
    private static final String REDACTED_CREDENTIAL = "[REDACTED_CREDENTIAL]";
    private static final Set<String> CREDENTIAL_ENDPOINTS = Set.of(
            "/login", "/oauth2/token", "/oauth2/authorize", "/oauth/confirm_access",
            "/oauth2/device_authorization", "/oauth2/device_verification", "/oauth2/introspect",
            "/oauth2/revoke", "/connect/logout", "/userinfo"
    );
    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization", "proxyauthorization", "xauthorization", "xforwardedauthorization",
            "cookie", "setcookie", "xapikey", "apikey", "xauthtoken", "xaccesstoken",
            "xrefreshtoken", "xidtoken", "xclientsecret", "xcsrftoken", "csrftoken"
    );
    private static final Set<String> SENSITIVE_PARAMETER_NAMES = Set.of(
            "password", "passwd", "pwd", "code", "authorizationcode", "devicecode", "usercode",
            "codeverifier", "accesstoken", "refreshtoken", "idtoken", "token", "clientsecret",
            "clientassertion", "apikey", "xapikey", "secret", "credential", "otp", "totp", "smscode",
            "verificationcode", "openid", "weixinid", "phone", "mobile",
            "authorization", "proxyauthorization", "cookie", "setcookie", "idtokenhint",
            "state", "nonce", "bearer"
    );
    private static final List<Pattern> CREDENTIAL_VALUE_PATTERNS = List.of(
            Pattern.compile("(?i)wx(?:-|%2d)[A-Za-z0-9_-]{6,128}"),
            Pattern.compile("(?i)mb(?:-|%2d)\\+?[0-9_-]{6,32}"),
            Pattern.compile("(?i)(?:openid|weixinid)\\s*(?:=|:|%3d|%3a)\\s*[A-Za-z0-9_-]{6,128}"),
            Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")
    );

    @Override
    public LogEntity captureLog(EsRequestWrapper esRequestWrapper) {
        LogEntity logEntity = new LogEntity();
        logEntity.setIp(sanitizePersistedText(HttpUtil.getIpAddr(esRequestWrapper)));
        logEntity.setUri(sanitizePersistedText(esRequestWrapper.getRequestURI()));
        logEntity.setMethod(sanitizePersistedText(esRequestWrapper.getMethod()));
        logEntity.setUserAgent(sanitizePersistedText(esRequestWrapper.getHeader("user-agent")));
        logEntity.setHeader(sanitizeHeaders(esRequestWrapper));
        logEntity.setParam(sanitizeParameters(esRequestWrapper));
        logEntity.setJiacn(sanitizePersistedText(EsContextHolder.getContext().getJiacn()));
        logEntity.setUsername(sanitizePersistedText(EsContextHolder.getContext().getUsername()));
        return logEntity;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LogEntity persistLog(LogEntity logEntity) {
        return create(logEntity);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LogEntity addLog(EsRequestWrapper esRequestWrapper) {
        return persistLog(captureLog(esRequestWrapper));
    }

    private String sanitizeHeaders(EsRequestWrapper request) {
        Map<String, Object> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return safeJson(headers);
        }
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName != null && !SENSITIVE_HEADER_NAMES.contains(normalizeName(headerName))) {
                headers.put(headerName, request.getHeader(headerName));
            }
        }
        return safeJson(headers);
    }

    private String sanitizeParameters(EsRequestWrapper request) {
        if (isCredentialEndpoint(request)) {
            return null;
        }

        String body = request.getBody();
        Map<String, Object> query;
        if (request.getQueryString() != null) {
            query = parseUrlEncoded(request.getQueryString());
        } else if (body == null || body.isBlank() || "GET".equalsIgnoreCase(request.getMethod())) {
            query = sanitizeServletParameters(request);
        } else {
            query = Map.of();
        }
        if (body == null || body.isBlank() || "GET".equalsIgnoreCase(request.getMethod())) {
            return toJsonOrNull(query);
        }

        String mediaType = mediaType(request.getContentType());
        Object sanitizedBody = null;
        if ("application/json".equals(mediaType) || mediaType.endsWith("+json")) {
            sanitizedBody = parseJson(body);
        } else if ("application/x-www-form-urlencoded".equals(mediaType)) {
            sanitizedBody = parseUrlEncoded(body);
        }

        if (sanitizedBody == null) {
            return toJsonOrNull(query);
        }
        if (query.isEmpty()) {
            return safeJson(sanitizedBody);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("body", sanitizedBody);
        return safeJson(payload);
    }

    private boolean isCredentialEndpoint(EsRequestWrapper request) {
        String requestUri = stripMatrixParameters(request.getRequestURI());
        String contextPath = stripMatrixParameters(request.getContextPath());
        if (contextPath != null && !contextPath.isEmpty() && requestUri != null
                && requestUri.startsWith(contextPath)
                && (requestUri.length() == contextPath.length() || requestUri.charAt(contextPath.length()) == '/')) {
            requestUri = requestUri.substring(contextPath.length());
        }
        return CREDENTIAL_ENDPOINTS.contains(requestUri);
    }

    private String stripMatrixParameters(String path) {
        return path == null ? null : path.replaceAll(";[^/]*", "");
    }

    private Map<String, Object> sanitizeServletParameters(EsRequestWrapper request) {
        Map<String, String[]> parameterMap;
        try {
            parameterMap = request.getParameterMap();
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (parameterMap == null) {
            return sanitized;
        }
        parameterMap.forEach((name, values) -> {
            if (isSensitiveParameter(name)) {
                return;
            }
            if (values == null || values.length == 0) {
                sanitized.put(name, null);
            } else if (values.length == 1) {
                sanitized.put(name, values[0]);
            } else {
                List<String> repeatedValues = new ArrayList<>(values.length);
                for (String value : values) {
                    repeatedValues.add(value);
                }
                sanitized.put(name, repeatedValues);
            }
        });
        return sanitized;
    }

    private Map<String, Object> parseUrlEncoded(String encoded) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return sanitized;
        }
        try {
            for (String pair : encoded.split("&", -1)) {
                int separator = pair.indexOf('=');
                String name = decode(separator < 0 ? pair : pair.substring(0, separator));
                if (name.isEmpty() || isSensitiveParameter(name)) {
                    continue;
                }
                String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
                putParameter(sanitized, name, value);
            }
            return sanitized;
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }

    private Object parseJson(String body) {
        try {
            Object parsed = JsonUtil.getMapper().readValue(body, Object.class);
            if (!(parsed instanceof Map<?, ?>) && !(parsed instanceof Iterable<?>)) {
                return null;
            }
            return sanitizeJsonValue(parsed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object sanitizeJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (!isSensitiveParameter(name)) {
                    sanitized.put(name, sanitizeJsonValue(item));
                }
            });
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            iterable.forEach(item -> sanitized.add(sanitizeJsonValue(item)));
            return sanitized;
        }
        return value;
    }

    private boolean isSensitiveParameter(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (SENSITIVE_PARAMETER_NAMES.contains(normalizeName(name))) {
            return true;
        }
        for (String segment : name.split("[.\\[\\]/]+")) {
            if (SENSITIVE_PARAMETER_NAMES.contains(normalizeName(segment))) {
                return true;
            }
        }
        return false;
    }

    private String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                normalized.append(Character.toLowerCase(current));
            }
        }
        return normalized.toString();
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void putParameter(Map<String, Object> parameters, String name, String value) {
        Object existing = parameters.get(name);
        if (existing == null) {
            parameters.put(name, value);
        } else if (existing instanceof List<?> values) {
            List<Object> combined = new ArrayList<>(values);
            combined.add(value);
            parameters.put(name, combined);
        } else {
            parameters.put(name, new ArrayList<>(List.of(existing, value)));
        }
    }

    private String toJsonOrNull(Map<String, Object> value) {
        return value.isEmpty() ? null : safeJson(value);
    }

    private String safeJson(Object value) {
        return sanitizePersistedText(JsonUtil.toJson(value));
    }

    private String sanitizePersistedText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String sanitized = value;
        for (Pattern pattern : CREDENTIAL_VALUE_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll(Matcher.quoteReplacement(REDACTED_CREDENTIAL));
        }
        return sanitized;
    }
}
