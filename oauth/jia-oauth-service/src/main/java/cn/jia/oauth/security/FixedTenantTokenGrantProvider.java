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

/** Serializes fixed-tenant code exchange and refresh with account/client/policy checks. */
public final class FixedTenantTokenGrantProvider implements AuthenticationProvider {
    private static final OAuth2TokenType CODE = new OAuth2TokenType("code");
    private static final String REVISION = "cyf.oauth.authorizationRevision";

    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final FixedTenantAuthorizationPolicy policy;
    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    public FixedTenantTokenGrantProvider(AuthenticationProvider delegate,
            OAuth2AuthorizationService authorizations, FixedTenantAuthorizationPolicy policy,
            JdbcOperations jdbc) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.policy = Objects.requireNonNull(policy);
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!policy.isFixedTenant()) throw new IllegalArgumentException("Fixed tenant mode is required");
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
        OAuth2Authorization hint = authorizations.findByToken(token, tokenType);
        if (hint == null || !(authentication.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
                || !client.isAuthenticated() || client.getRegisteredClient() == null) throw invalidGrant();
        FixedTenantAuthorizationBinding binding = requireBinding(hint);
        RegisteredClient registered = client.getRegisteredClient();
        var user = UserIssuanceAccountLock.savedUser(hint);
        if (user == null || !registered.getId().equals(hint.getRegisteredClientId())
                || !policy.permits(binding, registered.getClientId())) throw invalidGrant();
        GrantOutcome outcome = transactions.execute(status -> {
            if (!UserIssuanceAccountLock.current(jdbc, user)) throw invalidGrant();
            requireClient(registered);
            OAuth2Authorization current = authorizations.findByToken(token, tokenType);
            if (current == null || !hint.getId().equals(current.getId())
                    || !Objects.equals(hint.getAttribute(REVISION), current.getAttribute(REVISION))
                    || !binding.equals(requireBinding(current))
                    || !registered.getId().equals(current.getRegisteredClientId())
                    || !policy.permits(binding, registered.getClientId())) throw invalidGrant();
            try {
                return new GrantOutcome(delegate.authenticate(authentication), null);
            } catch (OAuth2AuthenticationException failure) {
                if (CODE.equals(tokenType) && OAuth2ErrorCodes.INVALID_GRANT.equals(failure.getError().getErrorCode())
                        && replayRevokedIssuedToken(current)) {
                    return new GrantOutcome(null, failure);
                }
                throw failure;
            }
        });
        if (outcome == null) throw invalidGrant();
        if (outcome.failure() != null) throw outcome.failure();
        return outcome.response();
    }

    private FixedTenantAuthorizationBinding requireBinding(OAuth2Authorization authorization) {
        try {
            return FixedTenantConsentGrantProvider.fixedBinding(authorization);
        } catch (RuntimeException invalid) {
            throw invalidGrant();
        }
    }

    private void requireClient(RegisteredClient client) {
        List<String> rows = jdbc.queryForList("SELECT client_id FROM oauth_client WHERE id = ? FOR UPDATE",
                String.class, client.getId());
        if (rows.size() != 1 || !client.getClientId().equals(rows.getFirst())) throw invalidGrant();
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

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
