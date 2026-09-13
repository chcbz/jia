package cn.jia.isp.ldap;

import cn.jia.core.ldap.LdapFailureClassifier;
import cn.jia.core.ldap.LdapRequestBudgetContext;
import cn.jia.core.exception.EsRuntimeException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Applies the LDAP-only budget to existing LDAP controller calls without changing their API surface. */
@Aspect
@Component
public class LdapRequestBudgetAspect {
    private final LdapTimeouts timeouts;

    public LdapRequestBudgetAspect(LdapTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    @Around("within(cn.jia.isp.api.LdapController)")
    public Object boundLdapControllerRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        try (LdapRequestBudgetContext.Scope ignored = LdapRequestBudgetContext.open(
                timeouts.getTotalTimeoutMillis(), timeouts.getSafetyMarginMillis(),
                timeouts.getMaximumOperationTimeoutMillis())) {
            return joinPoint.proceed();
        }
    }

    @Around("execution(* cn.jia.isp.service.Ldap*Service.*(..))")
    public Object guardAndClassifyLdapServiceCall(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            LdapRequestBudgetContext.requireBudgetBeforeNewOperation();
            return joinPoint.proceed();
        } catch (LdapRequestBudgetContext.LdapBudgetExhaustedException exception) {
            throw LdapFailureException.timeout();
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
