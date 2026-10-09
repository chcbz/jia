package cn.jia.oauth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

/** Force a server-owned pre-authorization before every code, including prior consents. */
public final class TenantAuthorizationRequestProvider implements AuthenticationProvider {
    public static final OAuth2TokenType CONSENT_STATE = new OAuth2TokenType("state");
    private static final String SESSION_PREFIX = TenantAuthorizationRequestProvider.class.getName() + ".";

    private final OAuth2AuthorizationCodeRequestAuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;

    public TenantAuthorizationRequestProvider(OAuth2AuthorizationCodeRequestAuthenticationProvider delegate,
            OAuth2AuthorizationService authorizations) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authorizations = Objects.requireNonNull(authorizations);
        // An existing consent must not bypass regional selection.
        delegate.setAuthorizationConsentRequired(context -> true);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        HttpSession session = currentSession();
        Authentication result = delegate.authenticate(authentication);
        if (!(result instanceof OAuth2AuthorizationConsentAuthenticationToken consent)) {
            throw invalidRequest();
        }
        OAuth2Authorization saved = authorizations.findByToken(consent.getState(), CONSENT_STATE);
        if (saved == null || !(consent.getPrincipal() instanceof Authentication principal)
                || !saved.getPrincipalName().equals(principal.getName())) {
            throw invalidRequest();
        }
        session.setAttribute(key(consent.getState()), saved.getId() + "|" + System.currentTimeMillis());
        return result;
    }

    public static HttpSession currentSession() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw invalidRequest();
        }
        HttpServletRequest request = attributes.getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) throw invalidRequest();
        return session;
    }

    public static void requireSessionAuthorization(HttpSession session, String state, OAuth2Authorization authorization) {
        if (session == null || state == null || state.isBlank() || authorization == null) throw invalidRequest();
        Object bound = session.getAttribute(key(state));
        if (!(bound instanceof String value)) throw invalidRequest();
        int separator = value.lastIndexOf('|');
        if (separator < 0 || !authorization.getId().equals(value.substring(0, separator))) throw invalidRequest();
        try {
            long age = Math.subtractExact(System.currentTimeMillis(),
                    Long.parseLong(value.substring(separator + 1)));
            if (age < 0 || age > 600_000L) throw invalidRequest();
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw invalidRequest();
        }
    }

    private static String key(String state) {
        return SESSION_PREFIX + state;
    }

    public static OAuth2AuthenticationException invalidRequest() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
