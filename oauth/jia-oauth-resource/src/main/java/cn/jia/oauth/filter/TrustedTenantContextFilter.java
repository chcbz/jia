package cn.jia.oauth.filter;

import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.core.tenant.UserAdmissionScope;
import cn.jia.oauth.security.AccountSecurityJwtClaims;
import cn.jia.oauth.security.TenantAdmissionPolicy;
import cn.jia.oauth.security.UserAdmissionScopeResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * Binds a verified user JWT to the common tenant context for one synchronous request.
 *
 * <p>This filter is installed only in resource security chains after bearer authentication.
 * Machine and anonymous requests keep an empty tenant context, and servlet async work does not
 * inherit the request thread value.</p>
 */
public final class TrustedTenantContextFilter extends OncePerRequestFilter {
    private final UserAdmissionScopeResolver admissionScopeResolver;
    private final TenantAdmissionPolicy admissionPolicy;

    public TrustedTenantContextFilter(UserAdmissionScopeResolver admissionScopeResolver,
                                      TenantAdmissionPolicy admissionPolicy) {
        this.admissionScopeResolver = Objects.requireNonNull(admissionScopeResolver,
                "admissionScopeResolver");
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Servlet threads are pooled. Never nest a new HTTP identity under an accidental stale value.
        TrustedTenantContext.clear();
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                    || !authentication.isAuthenticated()) {
                chain.doFilter(request, response);
                return;
            }

            AccountSecurityJwtClaims claims;
            try {
                claims = AccountSecurityJwtClaims.parse(jwtAuthentication.getToken());
            } catch (RuntimeException invalidIdentity) {
                if (admissionPolicy.requiresIsolatedResourceLane()) {
                    deny(response);
                } else {
                    chain.doFilter(request, response);
                }
                return;
            }

            boolean legacyUser = claims.tokenClass() == AccountSecurityJwtClaims.TokenClass.LEGACY_USER;
            if (claims.tokenClass() != AccountSecurityJwtClaims.TokenClass.USER && !legacyUser) {
                chain.doFilter(request, response);
                return;
            }
            if (legacyUser && !admissionPolicy.allowsLegacyUser()) {
                if (admissionPolicy.requiresIsolatedResourceLane()) {
                    deny(response);
                } else {
                    chain.doFilter(request, response);
                }
                return;
            }

            UserAdmissionScope admission;
            try {
                admission = admissionScopeResolver.resolve(authentication);
            } catch (RuntimeException unavailable) {
                if (legacyUser) {
                    deny(response);
                    return;
                }
                // Compatibility: the default non-strict platform lane may still authenticate an
                // old unbound user token. It receives no SQL tenant context, so tenant tables fail
                // closed later, while unrelated legacy/global endpoints retain their old behavior.
                if (admissionPolicy.requiresIsolatedResourceLane()) {
                    deny(response);
                } else {
                    chain.doFilter(request, response);
                }
                return;
            }

            try {
                TrustedTenantContext.runWithScopeChecked(admission.businessScope(),
                        () -> chain.doFilter(request, response));
            } catch (IOException | ServletException known) {
                throw known;
            } catch (RuntimeException unchecked) {
                throw unchecked;
            } catch (Exception impossible) {
                throw new ServletException(impossible);
            }
        } finally {
            TrustedTenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    private static void deny(HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "private, no-store");
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
