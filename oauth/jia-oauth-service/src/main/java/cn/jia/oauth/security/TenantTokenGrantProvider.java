package cn.jia.oauth.security;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/** Serializes user token issuance with tenant, grant and membership revocation. */
public final class TenantTokenGrantProvider implements AuthenticationProvider {
    private static final OAuth2TokenType CODE = new OAuth2TokenType("code");
    private static final String USER_ID = "cyf.oauth.userId";
    private static final String REVISION = "cyf.oauth.authorizationRevision";

    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final JdbcTenantAuthorizationRepository eligibility;
    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    public TenantTokenGrantProvider(AuthenticationProvider delegate, OAuth2AuthorizationService authorizations,
            JdbcTenantAuthorizationRepository eligibility, JdbcOperations jdbc) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!(jdbc instanceof JdbcTemplate template) || template.getDataSource() == null) {
            throw new IllegalArgumentException("Token endpoint requires a transactional DataSource");
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token;
        OAuth2TokenType tokenType;
        if (authentication instanceof OAuth2AuthorizationCodeAuthenticationToken code) {
            token = code.getCode();
            tokenType = CODE;
        } else if (authentication instanceof OAuth2RefreshTokenAuthenticationToken refresh) {
            token = refresh.getRefreshToken();
            tokenType = OAuth2TokenType.REFRESH_TOKEN;
        } else {
            throw new IllegalArgumentException("Unsupported token grant");
        }
        // Preflight only discovers the lock key. Never trust its contents as an
        // authorization decision; all decisions are re-read under ordered locks.
        OAuth2Authorization hint = authorizations.findByToken(token, tokenType);
        if (hint == null) throw invalidGrant();
        TenantAuthorizationAttributes tenant = hint.getAttribute(TenantAuthorizationAttributes.attributeName());
        long uid = userId(hint);
        if (tenant == null || !(authentication.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
                || !client.isAuthenticated() || client.getRegisteredClient() == null) throw invalidGrant();
        RegisteredClient registered = client.getRegisteredClient();
        if (!registered.getId().equals(hint.getRegisteredClientId())) throw invalidGrant();
        GrantOutcome outcome = transactions.execute(status -> {
            String tenantId = tenant.tenantId().value();
            String clientId = registered.getClientId();
            String domain = tenant.dataDomainId().value();
            requireRow("SELECT tenant_id FROM core_tenant_control WHERE tenant_id = ? FOR UPDATE",
                    tenantId, tenantId);
            var saved = UserIssuanceAccountLock.savedUser(hint);
            if (saved == null || saved.getUserId() != uid || !UserIssuanceAccountLock.current(jdbc, saved)) {
                throw invalidGrant();
            }
            requireRow("SELECT client_id FROM oauth_client WHERE id = ? FOR UPDATE",
                    clientId, registered.getId());
            requireRow("SELECT client_id FROM oauth_client_tenant_grant WHERE client_id = ? AND tenant_id = ? FOR UPDATE",
                    clientId, clientId, tenantId);
            requireRow("SELECT data_domain_id FROM oauth_client_tenant_domain_grant "
                    + "WHERE client_id = ? AND tenant_id = ? AND data_domain_id = ? FOR UPDATE",
                    domain, clientId, tenantId, domain);
            requireRow("SELECT tenant_id FROM core_tenant_membership WHERE tenant_id = ? AND uid = ? FOR UPDATE",
                    tenantId, tenantId, uid);
            requireRow("SELECT id FROM oauth2_authorization WHERE id = ? FOR UPDATE", hint.getId(), hint.getId());
            OAuth2Authorization current = authorizations.findByToken(token, tokenType);
            if (current == null || !hint.getId().equals(current.getId())
                    || !Objects.equals(hint.getAttribute(REVISION), current.getAttribute(REVISION))
                    || !tenant.equals(current.getAttribute(TenantAuthorizationAttributes.attributeName()))
                    || uid != userId(current)
                    || !registered.getId().equals(current.getRegisteredClientId())
                    || !eligibility.isEligibleForIssuance(tenant, uid, clientId)) throw invalidGrant();
            // Provider token generation, the customizer's revalidation and the
            // authorization save all run while the same control locks are held.
            try {
                return new GrantOutcome(delegate.authenticate(authentication), null);
            } catch (OAuth2AuthenticationException failure) {
                // On code replay Spring revokes the existing refresh/access token,
                // saves the authorization, then throws invalid_grant. Commit only
                // that verified revocation before propagating the error.
                if (CODE.equals(tokenType) && OAuth2ErrorCodes.INVALID_GRANT.equals(failure.getError().getErrorCode())
                        && replayRevokedIssuedToken(current)) {
                    return new GrantOutcome(null, failure);
                }
                throw failure;
            }
        });
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.response();
    }

    private boolean replayRevokedIssuedToken(OAuth2Authorization previous) {
        OAuth2Authorization.Token<OAuth2AuthorizationCode> code = previous.getToken(OAuth2AuthorizationCode.class);
        if (code == null || !code.isInvalidated()) return false;
        var oldToken = previous.getRefreshToken() != null ? previous.getRefreshToken() : previous.getAccessToken();
        if (oldToken == null || !oldToken.isActive()) return false;
        OAuth2Authorization updated = authorizations.findById(previous.getId());
        if (updated == null || Objects.equals(previous.getAttribute(REVISION), updated.getAttribute(REVISION))) {
            return false;
        }
        var revoked = previous.getRefreshToken() != null ? updated.getRefreshToken() : updated.getAccessToken();
        return revoked != null && revoked.isInvalidated()
                && oldToken.getToken().getTokenValue().equals(revoked.getToken().getTokenValue());
    }

    private record GrantOutcome(Authentication response, OAuth2AuthenticationException failure) {}

    private void requireRow(String sql, String expected, Object... parameters) {
        List<String> rows = jdbc.queryForList(sql, String.class, parameters);
        if (rows.size() != 1 || !expected.equals(rows.get(0))) throw invalidGrant();
    }

    private static long userId(OAuth2Authorization authorization) {
        Object value = authorization.getAttribute(USER_ID);
        if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) throw invalidGrant();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw invalidGrant();
        }
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
