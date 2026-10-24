package cn.jia.oauth.security;

import cn.jia.user.entity.CustomUserDetails;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.util.List;
import java.util.Objects;

/** Issues a code only from the same browser login and still-current fixed client policy. */
public final class FixedTenantConsentGrantProvider implements AuthenticationProvider {
    private static final String REVISION = "cyf.oauth.authorizationRevision";

    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final FixedTenantAuthorizationPolicy policy;
    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    public FixedTenantConsentGrantProvider(AuthenticationProvider delegate,
            OAuth2AuthorizationService authorizations, RegisteredClientRepository clients,
            FixedTenantAuthorizationPolicy policy, JdbcOperations jdbc) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.clients = Objects.requireNonNull(clients);
        this.policy = Objects.requireNonNull(policy);
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!policy.isFixedTenant()) throw new IllegalArgumentException("Fixed tenant mode is required");
        if (!(jdbc instanceof JdbcTemplate template) || template.getDataSource() == null) {
            throw new IllegalArgumentException("Consent grant requires a transactional DataSource");
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthorizationConsentAuthenticationToken consent)) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        HttpSession session = FixedTenantAuthorizationRequestProvider.currentSession();
        OAuth2Authorization hint = authorizations.findByToken(
                consent.getState(), TenantAuthorizationRequestProvider.CONSENT_STATE);
        FixedTenantAuthorizationRequestProvider.requireSessionAuthorization(session, consent.getState(), hint);
        FixedTenantAuthorizationBinding binding = fixedBinding(hint);
        RegisteredClient client = clients.findById(hint.getRegisteredClientId());
        CustomUserDetails user = UserIssuanceAccountLock.savedUser(hint);
        if (client == null || user == null || !client.getClientId().equals(consent.getClientId())
                || !policy.permits(binding, client.getClientId())) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        return transactions.execute(status -> {
            if (!UserIssuanceAccountLock.current(jdbc, user)) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            requireClient(client);
            OAuth2Authorization current = authorizations.findByToken(
                    consent.getState(), TenantAuthorizationRequestProvider.CONSENT_STATE);
            FixedTenantAuthorizationRequestProvider.requireSessionAuthorization(session, consent.getState(), current);
            if (current == null || !hint.getId().equals(current.getId())
                    || !Objects.equals(hint.getAttribute(REVISION), current.getAttribute(REVISION))
                    || !binding.equals(fixedBinding(current))
                    || !client.getId().equals(current.getRegisteredClientId())
                    || !(consent.getPrincipal() instanceof Authentication principal)
                    || !sameLogin(current, principal, user)
                    || !policy.permits(binding, client.getClientId())) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            return delegate.authenticate(authentication);
        });
    }

    private void requireClient(RegisteredClient client) {
        List<String> rows = jdbc.queryForList("SELECT client_id FROM oauth_client WHERE id = ? FOR UPDATE",
                String.class, client.getId());
        if (rows.size() != 1 || !client.getClientId().equals(rows.getFirst())) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
    }

    static FixedTenantAuthorizationBinding fixedBinding(OAuth2Authorization authorization) {
        if (authorization == null
                || authorization.getAttribute(TenantAuthorizationAttributes.attributeName()) != null) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        Object value = authorization.getAttribute(FixedTenantAuthorizationBinding.attributeName());
        if (!(value instanceof FixedTenantAuthorizationBinding binding)) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        return binding;
    }

    static boolean sameLogin(OAuth2Authorization authorization, Authentication principal,
            CustomUserDetails saved) {
        if (principal == null || !principal.isAuthenticated()
                || !(principal.getPrincipal() instanceof CustomUserDetails active)
                || !(authorization.getAttribute(Principal.class.getName()) instanceof Authentication savedLogin)
                || !(savedLogin.getPrincipal() instanceof CustomUserDetails restored)) return false;
        return active.getUserId() == saved.getUserId() && restored.getUserId() == saved.getUserId()
                && active.getLoginAuthEpoch() == saved.getLoginAuthEpoch()
                && restored.getLoginAuthEpoch() == saved.getLoginAuthEpoch()
                && active.getJiacn().equals(saved.getJiacn()) && restored.getJiacn().equals(saved.getJiacn())
                && active.getUsername().equals(saved.getUsername())
                && restored.getUsername().equals(saved.getUsername())
                && authorization.getPrincipalName().equals(saved.getUsername());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
