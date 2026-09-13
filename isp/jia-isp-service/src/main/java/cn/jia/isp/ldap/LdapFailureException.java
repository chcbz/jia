package cn.jia.isp.ldap;

import cn.jia.core.exception.EsRuntimeException;

/** Safe LDAP dependency failures preserving the existing JsonResult error shape. */
public final class LdapFailureException extends EsRuntimeException {
    private static final String TIMEOUT_CODE = "EISP002";
    private static final String UNAVAILABLE_CODE = "EISP003";

    private LdapFailureException(String code, String message) {
        super(code, message);
    }

    public static LdapFailureException timeout() {
        return new LdapFailureException(TIMEOUT_CODE, "LDAP服务响应超时");
    }

    public static LdapFailureException unavailable() {
        return new LdapFailureException(UNAVAILABLE_CODE, "LDAP服务不可用");
    }
}
