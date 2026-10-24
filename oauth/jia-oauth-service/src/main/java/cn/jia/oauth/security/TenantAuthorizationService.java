package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.user.entity.CustomUserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

import java.security.Principal;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/**
 * Uses the original process-local Spring authorization store, without a database schema.
 * Keeps tenant bindings immutable and rejects stale writes; credentials are not retained.
 * Authorization codes and refresh state deliberately expire when this instance restarts.
 */
public final class TenantAuthorizationService implements OAuth2AuthorizationService {
    private static final String PREFIX = "cyf.oauth.";
    private static final String REVISION = PREFIX + "authorizationRevision";
    private static final String USER_ID = PREFIX + "userId";
    private static final String JIACN = PREFIX + "jiacn";
    private static final String AUTH_EPOCH = PREFIX + "loginAuthEpoch";
    private static final String TENANT_VERSION = PREFIX + "tenantVersion";
    private static final String TENANT_ID = PREFIX + "tenantId";
    private static final String DOMAIN = PREFIX + "dataDomainId";
    private static final String TENANT_NAME = PREFIX + "tenantName";
    private static final String TENANT_KIND = PREFIX + "tenantKind";
    private static final String SESSION_EPOCH = PREFIX + "tenantSessionEpoch";
    private static final String MEMBER_REV = PREFIX + "membershipRevision";
    private static final String GRANT_REV = PREFIX + "clientTenantGrantRevision";
    private static final String FIXED_VERSION = PREFIX + "fixedTenantBindingVersion";
    private static final String FIXED_TENANT_ID = PREFIX + "fixedTenantId";
    private static final String FIXED_CLIENT_ID = PREFIX + "fixedTenantClientId";

    private final OAuth2AuthorizationService delegate = new InMemoryOAuth2AuthorizationService();

    @Override
    public synchronized void save(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        OAuth2Authorization.Builder builder = OAuth2Authorization.from(authorization);
        builder.attributes(attrs -> {
            Object selected = attrs.remove(TenantAuthorizationAttributes.attributeName());
            Object fixedSelected = attrs.remove(FixedTenantAuthorizationBinding.attributeName());
            if (selected != null && fixedSelected != null) {
                throw new IllegalArgumentException("Authorization cannot mix platform and fixed tenant bindings");
            }
            if (selected != null && attrs.containsKey(FIXED_VERSION)
                    || fixedSelected != null && attrs.containsKey(TENANT_VERSION)) {
                throw new IllegalArgumentException("Authorization cannot replace its tenant binding mode");
            }
            if (selected != null) {
                if (!(selected instanceof TenantAuthorizationAttributes tenant)) {
                    throw new IllegalArgumentException("Invalid tenant authorization binding");
                }
                attrs.put(TENANT_VERSION, "1");
                attrs.put(TENANT_ID, tenant.tenantId().value());
                attrs.put(DOMAIN, tenant.dataDomainId().value());
                attrs.put(TENANT_NAME, tenant.tenantName());
                attrs.put(TENANT_KIND, tenant.tenantKind().name());
                attrs.put(SESSION_EPOCH, tenant.tenantSessionEpoch());
                attrs.put(MEMBER_REV, tenant.membershipRevision());
                attrs.put(GRANT_REV, tenant.clientTenantGrantRevision());
            }
            if (fixedSelected != null) {
                if (!(fixedSelected instanceof FixedTenantAuthorizationBinding fixed)) {
                    throw new IllegalArgumentException("Invalid fixed tenant authorization binding");
                }
                OAuth2AuthorizationRequest request = authorization.getAttribute(
                        OAuth2AuthorizationRequest.class.getName());
                if (request == null || !fixed.clientId().equals(request.getClientId())) {
                    throw new IllegalArgumentException("Fixed tenant binding does not match the OAuth client");
                }
                attrs.put(FIXED_VERSION, "1");
                attrs.put(FIXED_TENANT_ID, fixed.tenantId().value());
                attrs.put(FIXED_CLIENT_ID, fixed.clientId());
            }
            Object original = attrs.get(Principal.class.getName());
            if (original instanceof Authentication auth && auth.getPrincipal() instanceof CustomUserDetails user) {
                if (!authorization.getPrincipalName().equals(user.getUsername())) {
                    throw new IllegalArgumentException("Authorization principal mismatch");
                }
                attrs.put(USER_ID, Long.toString(user.getUserId()));
                attrs.put(JIACN, user.getJiacn());
                attrs.put(AUTH_EPOCH, Long.toString(user.getLoginAuthEpoch()));
                attrs.put(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        user.getUsername(), null, auth.getAuthorities()));
            } else if (original instanceof CustomUserDetails user) {
                if (!authorization.getPrincipalName().equals(user.getUsername())) {
                    throw new IllegalArgumentException("Authorization principal mismatch");
                }
                attrs.put(USER_ID, Long.toString(user.getUserId()));
                attrs.put(JIACN, user.getJiacn());
                attrs.put(AUTH_EPOCH, Long.toString(user.getLoginAuthEpoch()));
                attrs.put(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        user.getUsername(), null, user.getAuthorities()));
            } else if (attrs.containsKey(USER_ID)) {
                throw new IllegalArgumentException("Persisted user identity requires a verified login principal");
            }
            if ((attrs.containsKey(TENANT_VERSION) || attrs.containsKey(FIXED_VERSION))
                    && !attrs.containsKey(USER_ID)) {
                throw new IllegalArgumentException("A bound tenant requires an authenticated user");
            }
            // A machine principal may be an Authentication carrying a credential;
            // never retain arbitrary principals in authorization state.
            if (original != null && !attrs.containsKey(USER_ID)) {
                throw new IllegalArgumentException("Unsupported persisted user principal");
            }
        });
        OAuth2Authorization persisted = builder.build();
        OAuth2Authorization existing = delegate.findById(persisted.getId());
        String expected = persisted.getAttribute(REVISION);
        String current = existing == null ? null : existing.getAttribute(REVISION);
        if (existing == null ? expected != null : !Objects.equals(expected, current)) {
            throw staleAuthorization();
        }
        requireFrozenBinding(existing, persisted);
        // Selection must precede issuance, not be attached to an already issued code.
        if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(persisted.getAuthorizationGrantType())
                && persisted.getToken(OAuth2AuthorizationCode.class) != null
                && !persisted.getAttributes().containsKey(TENANT_VERSION)
                && !persisted.getAttributes().containsKey(FIXED_VERSION)) {
            throw new IllegalArgumentException("An authorization code requires a selected tenant");
        }
        long revision = existing == null ? 0L : Math.addExact(parseRevision(current), 1L);
        OAuth2Authorization next = OAuth2Authorization.from(persisted)
                .attribute(REVISION, Long.toString(revision)).build();
        // Spring separates staged and completed authorizations internally. Remove
        // the old entry before a transition so logout cannot expose an old code.
        // All access to the delegate holds this service's monitor.
        if (existing != null) delegate.remove(existing);
        delegate.save(next);
    }

    private static long parseRevision(String revision) {
        if (revision == null || !revision.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalStateException("Missing or invalid authorization revision");
        }
        try {
            return Long.parseLong(revision);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid authorization revision", exception);
        }
    }

    private static OAuth2AuthenticationException staleAuthorization() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
    }

    /** State synchronization does not replace ordered account/client/control locks. */
    private static void requireFrozenBinding(OAuth2Authorization existing, OAuth2Authorization next) {
        if (existing == null) return;
        if (!existing.getRegisteredClientId().equals(next.getRegisteredClientId())
                || !existing.getPrincipalName().equals(next.getPrincipalName())
                || !existing.getAuthorizationGrantType().equals(next.getAuthorizationGrantType())) {
            throw new IllegalArgumentException("Authorization identity cannot be replaced");
        }
        // The selected region must remain bound to the original OAuth request's
        // client, redirect, client-state and PKCE challenge, even after consent.
        Object originalRequest = existing.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (originalRequest != null && !originalRequest.equals(
                next.getAttribute(OAuth2AuthorizationRequest.class.getName()))) {
            throw new IllegalArgumentException("Original authorization request cannot be replaced");
        }
        boolean issued = existing.getToken(OAuth2AuthorizationCode.class) != null
                || existing.getToken(OAuth2AccessToken.class) != null
                || existing.getRefreshToken() != null;
        for (String key : List.of(TENANT_VERSION, TENANT_ID, DOMAIN, TENANT_NAME, TENANT_KIND,
                SESSION_EPOCH, MEMBER_REV, GRANT_REV, FIXED_VERSION, FIXED_TENANT_ID,
                FIXED_CLIENT_ID, USER_ID, JIACN, AUTH_EPOCH)) {
            Object previous = existing.getAttribute(key);
            Object updated = next.getAttribute(key);
            if (previous != null && !previous.equals(updated)) {
                throw new IllegalArgumentException("Authorization binding cannot be changed: " + key);
            }
            if (previous == null && updated != null && issued) {
                throw new IllegalArgumentException("Issued authorization cannot acquire a new binding: " + key);
            }
        }
    }

    @Override
    public synchronized void remove(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        OAuth2Authorization current = delegate.findById(authorization.getId());
        if (current == null) return;
        if (!Objects.equals(authorization.getAttribute(REVISION), current.getAttribute(REVISION))) {
            throw staleAuthorization();
        }
        delegate.remove(current);
    }

    @Override
    public synchronized OAuth2Authorization findById(String id) {
        return restore(delegate.findById(id));
    }

    @Override
    public synchronized OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        return restore(delegate.findByToken(token, type));
    }

    private static OAuth2Authorization restore(OAuth2Authorization stored) {
        if (stored == null) return null;
        Map<String, Object> attrs = stored.getAttributes();
        parseRevision((String) attrs.get(REVISION));
        OAuth2Authorization.Builder builder = OAuth2Authorization.from(stored);
        if (attrs.containsKey(TENANT_VERSION)) {
            if (!"1".equals(attrs.get(TENANT_VERSION))) throw new IllegalStateException("Unknown tenant binding version");
            try {
                TenantAuthorizationAttributes tenant = new TenantAuthorizationAttributes(
                        TenantId.of(text(attrs, TENANT_ID)), DataDomainId.of(text(attrs, DOMAIN)),
                        text(attrs, TENANT_NAME), TenantAuthorizationAttributes.TenantKind.valueOf(text(attrs, TENANT_KIND)),
                        text(attrs, SESSION_EPOCH), text(attrs, MEMBER_REV), text(attrs, GRANT_REV));
                builder.attribute(TenantAuthorizationAttributes.attributeName(), tenant);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Invalid persisted tenant binding", invalid);
            }
        } else if (attrs.keySet().stream().anyMatch(k -> k.startsWith(PREFIX + "tenant") || k.equals(DOMAIN))) {
            throw new IllegalStateException("Incomplete persisted tenant binding");
        }
        if (attrs.containsKey(FIXED_VERSION)) {
            if (attrs.containsKey(TENANT_VERSION) || !"1".equals(attrs.get(FIXED_VERSION))) {
                throw new IllegalStateException("Invalid fixed tenant binding version");
            }
            try {
                builder.attribute(FixedTenantAuthorizationBinding.attributeName(),
                        new FixedTenantAuthorizationBinding(TenantId.of(text(attrs, FIXED_TENANT_ID)),
                                text(attrs, FIXED_CLIENT_ID)));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Invalid persisted fixed tenant binding", invalid);
            }
        } else if (attrs.containsKey(FIXED_TENANT_ID) || attrs.containsKey(FIXED_CLIENT_ID)) {
            throw new IllegalStateException("Incomplete persisted fixed tenant binding");
        }
        if (attrs.containsKey(USER_ID)) {
            Object saved = attrs.get(Principal.class.getName());
            if (!(saved instanceof Authentication authentication)
                    || !(authentication.getPrincipal() instanceof String username)
                    || !username.equals(stored.getPrincipalName())) {
                throw new IllegalStateException("Invalid persisted user principal");
            }
            try {
                CustomUserDetails user = new CustomUserDetails(Long.parseLong(text(attrs, USER_ID)),
                        text(attrs, JIACN), Long.parseLong(text(attrs, AUTH_EPOCH)), username,
                        "<unavailable>", authentication.getAuthorities());
                builder.attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities()));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Invalid persisted user identity", invalid);
            }
        } else if (attrs.containsKey(JIACN) || attrs.containsKey(AUTH_EPOCH)) {
            throw new IllegalStateException("Incomplete persisted user identity");
        }
        return builder.build();
    }

    private static String text(Map<String, Object> attrs, String key) {
        Object value = attrs.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Missing persisted authorization attribute: " + key);
        }
        return text;
    }
}
