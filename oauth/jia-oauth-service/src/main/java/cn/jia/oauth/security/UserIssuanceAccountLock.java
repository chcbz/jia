package cn.jia.oauth.security;

import cn.jia.user.entity.CustomUserDetails;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

import java.security.Principal;
import java.util.List;

/** Check the live account under a row lock held through authorization issuance. */
final class UserIssuanceAccountLock {
    private UserIssuanceAccountLock() {}

    static CustomUserDetails savedUser(OAuth2Authorization authorization) {
        Object principal = authorization.getAttribute(Principal.class.getName());
        if (!(principal instanceof Authentication login)
                || !(login.getPrincipal() instanceof CustomUserDetails user)
                || !authorization.getPrincipalName().equals(user.getUsername())) return null;
        return user;
    }

    static boolean current(JdbcOperations jdbc, CustomUserDetails user) {
        if (user == null || user.getUserId() <= 0 || user.getLoginAuthEpoch() < 0) return false;
        List<Account> rows = jdbc.query("""
                SELECT id, jiacn, account_state, auth_epoch FROM user_info
                WHERE id = ? FOR UPDATE
                """, (rs, row) -> {
            long id = rs.getLong("id");
            String owner = rs.getString("jiacn");
            String state = rs.getString("account_state");
            long epoch = rs.getLong("auth_epoch");
            return new Account(id, owner, state, epoch, rs.wasNull());
        }, user.getUserId());
        return rows.size() == 1 && rows.getFirst().id() == user.getUserId()
                && user.getJiacn().equals(rows.getFirst().jiacn())
                && "ACTIVE".equals(rows.getFirst().state())
                && !rows.getFirst().epochNull() && rows.getFirst().epoch() == user.getLoginAuthEpoch();
    }

    private record Account(long id, String jiacn, String state, long epoch, boolean epochNull) {}
}
