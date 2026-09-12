package cn.jia.agent.hosting;

import cn.jia.user.entity.UserEntity;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.service.UserService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Mirrors trusted persisted login identity forms, not legacy Agent context fallback helpers. */
@Service
public final class HostingRentOwnerResolver {
    private final org.springframework.beans.factory.ObjectProvider<AccountSecurityService> accountProvider;
    private final org.springframework.beans.factory.ObjectProvider<UserService> userProvider;
    public HostingRentOwnerResolver(org.springframework.beans.factory.ObjectProvider<AccountSecurityService> accounts,
            org.springframework.beans.factory.ObjectProvider<UserService> users) {
        this.accountProvider = accounts;
        this.userProvider = users;
    }
    public String requireOwner(HostingRentHttp.Actor actor) {
        AccountSecurityService accounts = accountProvider.getIfAvailable();
        UserService users = userProvider.getIfAvailable();
        if (accounts == null || users == null) throw forbidden();
        AccountSecuritySnapshot account = accounts.findUniqueByExactJiacn(actor.tenantId())
                .filter(AccountSecuritySnapshot::isAuthenticatable).orElseThrow(HostingRentOwnerResolver::forbidden);
        UserEntity row = users.get(account.userId());
        if (row == null || !Objects.equals(row.getId(), account.userId())
                || !actor.tenantId().equals(row.getJiacn()) || !matchesLoginSubject(actor.actorId(), row)
                || accounts.findByUserId(account.userId()).filter(current -> current.equals(account)).isEmpty()) {
            throw forbidden();
        }
        return account.jiacn(); // Only after sub-to-user proof. The payer remains actor.actorId().
    }
    static boolean matchesLoginSubject(String sub, UserEntity row) {
        // DefaultSecurityConfig: literal username, wx-<openid>, mb-<phone>.
        if (sub.equals(row.getUsername())) return true;
        if (row.getOpenid() != null && !row.getOpenid().isBlank() && sub.equals("wx-" + row.getOpenid())) return true;
        if (row.getPhone() != null && !row.getPhone().isBlank() && sub.equals("mb-" + row.getPhone())) return true;
        // OauthController.completeThirdPartyLogin uses the FIRST nonempty persisted identity.
        for (String candidate : new String[]{row.getUsername(), row.getJiacn(), row.getOpenid(), row.getWeixinid(), row.getGithubid()}) {
            if (candidate != null && !candidate.isEmpty()) return sub.equals(candidate);
        }
        return false;
    }
    private static HostingRentApplicationException forbidden() {
        return new HostingRentApplicationException(403, "HOSTING_RENT_OWNER_UNPROVEN");
    }
}
