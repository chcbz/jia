package cn.jia.user.entity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.Collection;

/**
 * Immutable login-time account identity. The epoch is deliberately not refreshed after authentication.
 */
public class CustomUserDetails extends User {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String jiacn;
    private final long loginAuthEpoch;

    public CustomUserDetails(long userId, String jiacn, long loginAuthEpoch, String username, String password,
                             Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (jiacn == null || jiacn.isBlank()) {
            throw new IllegalArgumentException("jiacn must be nonblank");
        }
        if (loginAuthEpoch < 0) {
            throw new IllegalArgumentException("loginAuthEpoch must be nonnegative");
        }
        this.userId = userId;
        this.jiacn = jiacn;
        this.loginAuthEpoch = loginAuthEpoch;
    }

    public long getUserId() {
        return userId;
    }

    public String getJiacn() {
        return jiacn;
    }

    public long getLoginAuthEpoch() {
        return loginAuthEpoch;
    }
}
