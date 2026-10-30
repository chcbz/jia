package cn.jia.point.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.point.dao.PointAccountDao;
import cn.jia.point.service.PointAccountService;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-process point partition. No OAuth-client-to-tenant mapping and no JWT or
 * thread-local tenant lookup. HTTP and background operations use the same scope.
 */
@Service
public class PointAccountServiceImpl implements PointAccountService {
    private final PointAccountDao accounts;
    private final String tenantId;

    public PointAccountServiceImpl(PointAccountDao accounts,
            FixedTenantAdmissionPolicy processScope) {
        this.accounts = java.util.Objects.requireNonNull(accounts);
        this.tenantId = processScope.businessTenantId();
    }

    @Override public String tenantId() { return tenantId; }

    @Override public int balance(String jiacn) {
        requireIdentity(jiacn);
        Integer value = accounts.balance(tenantId, jiacn);
        if (value != null && value < 0) throw new IllegalStateException("Invalid point balance");
        return value == null ? 0 : value;
    }

    @Override
    public java.util.Map<String, Integer> balances(java.util.Collection<String> jiacns) {
        java.util.Objects.requireNonNull(jiacns, "point account identities");
        java.util.Map<String, Integer> balances = new java.util.LinkedHashMap<>();
        for (String jiacn : jiacns) {
            requireIdentity(jiacn);
            balances.put(jiacn, 0);
        }
        if (balances.isEmpty()) return balances;
        for (var row : accounts.balances(tenantId, balances.keySet())) {
            if (row.balance() < 0 || !balances.containsKey(row.jiacn())) {
                throw new IllegalStateException("Invalid point account projection");
            }
            balances.put(row.jiacn(), row.balance());
        }
        return balances;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public int changeAndGet(String jiacn, int delta) {
        requireIdentity(jiacn);
        long now = System.currentTimeMillis();
        accounts.ensureAccount(tenantId, jiacn, now);
        Integer before = accounts.lockBalance(tenantId, jiacn);
        if (before == null || before < 0) throw new IllegalStateException("Invalid point account");
        long after = (long) before + delta;
        if (after < 0) throw new EsRuntimeException(UserErrorConstants.POINT_NO_ENOUGH);
        // Keep the existing int wire/database contract, without silent overflow.
        if (after > Integer.MAX_VALUE) throw new IllegalArgumentException("Point balance overflow");
        if (accounts.change(tenantId, jiacn, delta, now) != 1) {
            throw new IllegalStateException("Point balance update did not affect exactly one account");
        }
        return (int) after;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockAccounts(java.util.Collection<String> jiacns) {
        java.util.Objects.requireNonNull(jiacns, "point account identities");
        // Validate every identity before acquiring any lock. All multi-user point
        // operations must use this order, including account creation/upsert locks.
        jiacns.forEach(PointAccountServiceImpl::requireIdentity);
        for (String jiacn : new java.util.TreeSet<>(jiacns)) {
            accounts.ensureAccount(tenantId, jiacn, System.currentTimeMillis());
            Integer balance = accounts.lockBalance(tenantId, jiacn);
            if (balance == null || balance < 0) throw new IllegalStateException("Invalid point account");
        }
    }

    private static void requireIdentity(String jiacn) {
        if (jiacn == null || jiacn.isBlank() || !jiacn.equals(jiacn.strip())
                || jiacn.codePointCount(0, jiacn.length()) > 50
                || jiacn.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid point account identity");
        }
        for (int i = 0; i < jiacn.length(); i++) {
            char c = jiacn.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= jiacn.length() || !Character.isLowSurrogate(jiacn.charAt(i))) {
                    throw new IllegalArgumentException("Invalid point account identity");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Invalid point account identity");
            }
        }
    }
}
