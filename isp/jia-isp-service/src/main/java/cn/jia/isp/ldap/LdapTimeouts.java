package cn.jia.isp.ldap;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded LDAP connection, read, and per-request operation budgets. */
@Component
public class LdapTimeouts {
    @Value("${spring.ldap.base-environment[com.sun.jndi.ldap.connect.timeout]:500}")
    private int connectTimeoutMillis = 500;

    @Value("${spring.ldap.base-environment[com.sun.jndi.ldap.read.timeout]:1750}")
    private int readTimeoutMillis = 1750;

    @Value("${ldap.timeout.total-ms:2500}")
    private int totalTimeoutMillis = 2500;

    @Value("${ldap.timeout.safety-margin-ms:100}")
    private int safetyMarginMillis = 100;

    @PostConstruct
    public void validate() {
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0 || totalTimeoutMillis <= 0
                || safetyMarginMillis < 0 || safetyMarginMillis >= totalTimeoutMillis) {
            throw new IllegalStateException("LDAP timeouts must leave time for an operation");
        }
        if ((long) connectTimeoutMillis + readTimeoutMillis > totalTimeoutMillis - safetyMarginMillis) {
            throw new IllegalStateException("LDAP connection and read timeouts exceed the total budget");
        }
    }

    public int getMaximumOperationTimeoutMillis() {
        return connectTimeoutMillis + readTimeoutMillis;
    }

    public int getTotalTimeoutMillis() {
        return totalTimeoutMillis;
    }

    public int getSafetyMarginMillis() {
        return safetyMarginMillis;
    }
}
