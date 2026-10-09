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

/** Consent state is single-use and cannot issue a code without a live, session-bound selection. */
public final class TenantConsentGrantProvider implements AuthenticationProvider {
    private final AuthenticationProvider delegate;
    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final JdbcTenantAuthorizationRepository eligibility;
    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    public TenantConsentGrantProvider(AuthenticationProvider delegate, OAuth2AuthorizationService authorizations,
            RegisteredClientRepository clients, JdbcTenantAuthorizationRepository eligibility, JdbcOperations jdbc) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authorizations = Objects.requireNonNull(authorizations);
        this.clients = Objects.requireNonNull(clients);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.jdbc = Objects.requireNonNull(jdbc);
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
        HttpSession session = TenantAuthorizationRequestProvider.currentSession();
        OAuth2Authorization hint = authorizations.findByToken(consent.getState(),
                TenantAuthorizationRequestProvider.CONSENT_STATE);
        TenantAuthorizationRequestProvider.requireSessionAuthorization(session, consent.getState(), hint);
        if (hint.getAttribute(TenantAuthorizationAttributes.attributeName()) == null) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        return transactions.execute(status -> {
            TenantAuthorizationAttributes selected = hint.getAttribute(TenantAuthorizationAttributes.attributeName());
            RegisteredClient client = clients.findById(hint.getRegisteredClientId());
            if (client == null || !client.getClientId().equals(consent.getClientId())) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            Object uidValue = hint.getAttribute("cyf.oauth.userId");
            long uid;
            try {
                uid = Long.parseLong((String) uidValue);
            } catch (RuntimeException invalid) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            String tenant = selected.tenantId().value();
            String domain = selected.dataDomainId().value();
            requireRow("SELECT tenant_id FROM core_tenant_control WHERE tenant_id = ? FOR UPDATE", tenant, tenant);
            CustomUserDetails saved = UserIssuanceAccountLock.savedUser(hint);
            if (saved == null || saved.getUserId() != uid || !UserIssuanceAccountLock.current(jdbc, saved)) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            requireRow("SELECT client_id FROM oauth_client WHERE id = ? FOR UPDATE", client.getClientId(), client.getId());
            requireRow("SELECT client_id FROM oauth_client_tenant_grant WHERE client_id = ? AND tenant_id = ? FOR UPDATE",
                    client.getClientId(), client.getClientId(), tenant);
            requireRow("SELECT data_domain_id FROM oauth_client_tenant_domain_grant "
                    + "WHERE client_id = ? AND tenant_id = ? AND data_domain_id = ? FOR UPDATE",
                    domain, client.getClientId(), tenant, domain);
            requireRow("SELECT tenant_id FROM core_tenant_membership WHERE tenant_id = ? AND uid = ? FOR UPDATE",
                    tenant, tenant, uid);
            requireRow("SELECT id FROM oauth2_authorization WHERE id = ? FOR UPDATE", hint.getId(), hint.getId());
            OAuth2Authorization current = authorizations.findByToken(consent.getState(),
                    TenantAuthorizationRequestProvider.CONSENT_STATE);
            TenantAuthorizationRequestProvider.requireSessionAuthorization(session, consent.getState(), current);
            if (!hint.getId().equals(current.getId()) || !hint.getAttributes().get("cyf.oauth.authorizationRevision")
                    .equals(current.getAttributes().get("cyf.oauth.authorizationRevision"))
                    || !selected.equals(current.getAttribute(TenantAuthorizationAttributes.attributeName()))
                    || !(consent.getPrincipal() instanceof Authentication principal)
                    || !current.getPrincipalName().equals(principal.getName())
                    || !sameLogin(current, principal, uid)
                    || !eligibility.isEligibleForIssuance(selected, uid, client.getClientId())) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            return delegate.authenticate(authentication);
        });
    }

    private static boolean sameLogin(OAuth2Authorization authorization, Authentication principal, long uid) {
        if (!principal.isAuthenticated() || !(principal.getPrincipal() instanceof CustomUserDetails active)
                || !(authorization.getAttribute(Principal.class.getName()) instanceof Authentication savedLogin)
                || !(savedLogin.getPrincipal() instanceof CustomUserDetails saved)) {
            return false;
        }
        // The same HTTP session can change its SecurityContext between POSTs.
        // A recycled/non-unique username is not sufficient to bind a new login
        // to the old authorization's user, owner and security epoch.
        return active.getUserId() == uid && saved.getUserId() == uid
                && active.getLoginAuthEpoch() == saved.getLoginAuthEpoch()
                && active.getJiacn().equals(saved.getJiacn())
                && active.getUsername().equals(saved.getUsername())
                && authorization.getPrincipalName().equals(active.getUsername());
    }

    private void requireRow(String sql, String expected, Object... parameters) {
        List<String> rows = jdbc.queryForList(sql, String.class, parameters);
        if (rows.size() != 1 || !expected.equals(rows.get(0))) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
