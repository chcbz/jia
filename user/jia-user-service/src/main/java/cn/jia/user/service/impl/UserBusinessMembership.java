package cn.jia.user.service.impl;

import cn.jia.core.security.TenantClaimPolicy;
import cn.jia.user.dao.UserBusinessMembershipDao;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Process-owned business affiliations; no login credential or role is stored here. */
@Service
public class UserBusinessMembership {
    private final UserBusinessMembershipDao memberships;
    private final String tenantId;

    public UserBusinessMembership(UserBusinessMembershipDao memberships,
            FixedTenantAdmissionPolicy processScope) {
        this.memberships = java.util.Objects.requireNonNull(memberships);
        this.tenantId = processScope.businessTenantId();
    }

    public String tenantId() { return tenantId; }

    /** Global identity deletion removes affiliations, not financial history or permission definitions. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void removeForDeletedIdentity(java.io.Serializable userId) {
        memberships.deleteByUserId(java.util.Objects.requireNonNull(userId));
    }

    /** Called within identity registration/upsert, with the admitted caller's client, never a profile field. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void ensure(Long userId, String admittedClientId) {
        String clientId = admittedClientId == null || admittedClientId.isEmpty()
                ? "" : TenantClaimPolicy.exact(admittedClientId);
        if (userId == null || userId <= 0) throw new IllegalArgumentException("Business membership requires a persisted user");
        memberships.ensure(tenantId, clientId, userId, System.currentTimeMillis());
    }
}
