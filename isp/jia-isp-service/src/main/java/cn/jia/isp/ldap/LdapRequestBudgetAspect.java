package cn.jia.isp.ldap;

import cn.jia.core.ldap.LdapFailureClassifier;
import cn.jia.core.exception.EsRuntimeException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Classifies failures reported by the LDAP transport without rejecting calls merely because a performance target is
 * already late. Connection and read protection remain the responsibility of explicitly configured LDAP provider
 * properties.
 */
@Aspect
@Component
public class LdapRequestBudgetAspect {

    @Around("execution(* cn.jia.isp.service.Ldap*Service.*(..))")
    public Object guardAndClassifyLdapServiceCall(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (RuntimeException exception) {
            if (exception instanceof EsRuntimeException) {
                throw exception;
            }
            LdapFailureClassifier.Failure failure = LdapFailureClassifier.classify(exception);
            if (failure == LdapFailureClassifier.Failure.TIMEOUT) {
                throw LdapFailureException.timeout();
            }
            if (failure == LdapFailureClassifier.Failure.UNAVAILABLE) {
                throw LdapFailureException.unavailable();
            }
            throw exception;
        }
    }
}
