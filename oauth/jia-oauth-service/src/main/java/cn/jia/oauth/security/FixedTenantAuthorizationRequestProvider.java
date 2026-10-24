package cn.jia.oauth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

/** Binds a trusted fixed tenant to staged consent state before any code can be issued. */
public final class FixedTenantAuthorizationRequestProvider implements AuthenticationProvider {
    private static final String SESSION_PREFIX = FixedTenantAuthorizationRequestProvider.class.getName() + ".";

    private final OAuth2AuthorizationCodeRequestAuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final FixedTenantAuthorizationPolicy policy;

    public FixedTenantAuthorizationRequestProvider(OAuth2AuthorizationCodeRequestAuthenticationProvider delegate,
            OAuth2AuthorizationService authorizations, FixedTenantAuthorizationPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.policy = Objects.requireNonNull(policy);
        if (!policy.isFixedTenant()) throw new IllegalArgumentException("Fixed tenant mode is required");
        delegate.setAuthorizationConsentRequired(context -> true);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        HttpSession session = currentSession();
        Authentication result = delegate.authenticate(authentication);
        if (!(result instanceof OAuth2AuthorizationConsentAuthenticationToken consent)) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        OAuth2Authorization saved = authorizations.findByToken(
                consent.getState(), TenantAuthorizationRequestProvider.CONSENT_STATE);
        OAuth2AuthorizationRequest request = saved == null ? null
                : saved.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (saved == null || request == null || !(consent.getPrincipal() instanceof Authentication principal)
                || !principal.isAuthenticated() || !saved.getPrincipalName().equals(principal.getName())
                || !consent.getClientId().equals(request.getClientId())
                || saved.getAttribute(TenantAuthorizationAttributes.attributeName()) != null
                || saved.getAttribute(FixedTenantAuthorizationBinding.attributeName()) != null) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        FixedTenantAuthorizationBinding binding = policy.bind(consent.getClientId());
        authorizations.save(OAuth2Authorization.from(saved)
                .attribute(FixedTenantAuthorizationBinding.attributeName(), binding).build());
        session.setAttribute(key(consent.getState()), saved.getId() + "|" + System.currentTimeMillis());
        return result;
    }

    public static HttpSession currentSession() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        HttpServletRequest request = attributes.getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) throw TenantAuthorizationRequestProvider.invalidRequest();
        return session;
    }

    public static void requireSessionAuthorization(HttpSession session, String state,
            OAuth2Authorization authorization) {
        if (session == null || state == null || state.isBlank() || authorization == null) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        Object bound = session.getAttribute(key(state));
        if (!(bound instanceof String value)) throw TenantAuthorizationRequestProvider.invalidRequest();
        int separator = value.lastIndexOf('|');
        if (separator < 0 || !authorization.getId().equals(value.substring(0, separator))) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        try {
            long age = Math.subtractExact(System.currentTimeMillis(),
                    Long.parseLong(value.substring(separator + 1)));
            if (age < 0 || age > 600_000L) throw TenantAuthorizationRequestProvider.invalidRequest();
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
    }

    private static String key(String state) {
        return SESSION_PREFIX + state;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
