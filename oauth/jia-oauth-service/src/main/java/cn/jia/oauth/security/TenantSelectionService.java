package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.user.entity.CustomUserDetails;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.util.List;
import java.util.Objects;

/** Only server-side consent state, the original browser session and a live grant may bind a region. */
@Service
@ConditionalOnExpression("'${cyf.oauth.authorization-server.enabled:true}' == 'true' && "
        + "'${cyf.tenant.admission-mode:platform}' == 'platform'")
public final class TenantSelectionService {
    private static final DataDomainId CYF = DataDomainId.of("cyf");

    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final JdbcTenantAuthorizationRepository eligibility;
    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    public TenantSelectionService(OAuth2AuthorizationService authorizations, RegisteredClientRepository clients,
            JdbcTenantAuthorizationRepository eligibility, JdbcOperations jdbc) {
        this.authorizations = Objects.requireNonNull(authorizations);
        this.clients = Objects.requireNonNull(clients);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!(jdbc instanceof JdbcTemplate template) || template.getDataSource() == null) {
            throw new IllegalArgumentException("Tenant selection requires a transactional DataSource");
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Selection options(HttpSession session, String state, Authentication principal) {
        AuthorizationRequest request = requireRequest(session, state, principal);
        List<TenantAuthorizationAttributes> choices = eligibility.listEligibleSelections(
                CYF, request.user().getUserId(), request.client().getClientId());
        if (request.hint() != null) {
            choices = choices.stream().filter(choice -> choice.tenantId().equals(request.hint())).toList();
        }
        return new Selection(request.client().getClientId(), state,
                List.copyOf(request.request().getScopes()), choices,
                request.authorization().getAttribute(TenantAuthorizationAttributes.attributeName()));
    }

    public Selection select(HttpSession session, String state, String tenantId, Authentication principal) {
        TenantId requested;
        try {
            requested = TenantId.of(tenantId);
        } catch (RuntimeException invalid) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        // Only a lock key, not an authorization decision; verify the whole request under the locks.
        AuthorizationRequest hint = requireRequest(session, state, principal);
        return transactions.execute(status -> {
            String id = requested.value();
            String clientId = hint.client().getClientId();
            long uid = hint.user().getUserId();
            requireRow("SELECT tenant_id FROM core_tenant_control WHERE tenant_id = ? FOR UPDATE", id, id);
            if (!UserIssuanceAccountLock.current(jdbc, hint.user())) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            requireRow("SELECT client_id FROM oauth_client WHERE id = ? FOR UPDATE", clientId,
                    hint.client().getId());
            requireRow("SELECT client_id FROM oauth_client_tenant_grant WHERE client_id = ? AND tenant_id = ? FOR UPDATE",
                    clientId, clientId, id);
            requireRow("SELECT data_domain_id FROM oauth_client_tenant_domain_grant "
                    + "WHERE client_id = ? AND tenant_id = ? AND data_domain_id = ? FOR UPDATE",
                    CYF.value(), clientId, id, CYF.value());
            requireRow("SELECT tenant_id FROM core_tenant_membership WHERE tenant_id = ? AND uid = ? FOR UPDATE",
                    id, id, uid);
            AuthorizationRequest current = requireRequest(session, state, principal);
            if (!hint.authorization().getId().equals(current.authorization().getId())
                    || current.authorization().getToken(OAuth2AuthorizationCode.class) != null
                    || current.authorization().getAttribute(TenantAuthorizationAttributes.attributeName()) != null
                    || current.hint() != null && !current.hint().equals(requested)) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
            TenantAuthorizationAttributes selected = eligibility.findEligibleSelection(
                    requested, CYF, uid, clientId).orElseThrow(TenantAuthorizationRequestProvider::invalidRequest);
            authorizations.save(OAuth2Authorization.from(current.authorization())
                    .attribute(TenantAuthorizationAttributes.attributeName(), selected).build());
            return new Selection(clientId, state, List.copyOf(current.request().getScopes()),
                    List.of(selected), selected);
        });
    }

    private AuthorizationRequest requireRequest(HttpSession session, String state, Authentication principal) {
        if (principal == null || !principal.isAuthenticated()
                || !(principal.getPrincipal() instanceof CustomUserDetails user)) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        OAuth2Authorization authorization = authorizations.findByToken(
                state, TenantAuthorizationRequestProvider.CONSENT_STATE);
        TenantAuthorizationRequestProvider.requireSessionAuthorization(session, state, authorization);
        OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
        if (request == null || client == null
                || !authorization.getPrincipalName().equals(principal.getName())
                || !user.getUsername().equals(authorization.getPrincipalName())
                || !client.getClientId().equals(request.getClientId())
                || !request.getScopes().containsAll(authorization.getAuthorizedScopes())) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        Object original = authorization.getAttribute(Principal.class.getName());
        if (!(original instanceof Authentication login)
                || !(login.getPrincipal() instanceof CustomUserDetails saved)
                || saved.getUserId() != user.getUserId()
                || saved.getLoginAuthEpoch() != user.getLoginAuthEpoch()
                || !saved.getJiacn().equals(user.getJiacn())) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        TenantId expected = null;
        Object hint = request.getAdditionalParameters().get("tenant_hint");
        if (hint != null) {
            if (!(hint instanceof String text)) throw TenantAuthorizationRequestProvider.invalidRequest();
            try {
                expected = TenantId.of(text);
            } catch (RuntimeException invalid) {
                throw TenantAuthorizationRequestProvider.invalidRequest();
            }
        }
        return new AuthorizationRequest(authorization, request, client, user, expected);
    }

    private void requireRow(String sql, String expected, Object... parameters) {
        List<String> rows = jdbc.queryForList(sql, String.class, parameters);
        if (rows.size() != 1 || !expected.equals(rows.get(0))) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
    }

    private record AuthorizationRequest(OAuth2Authorization authorization, OAuth2AuthorizationRequest request,
                                        RegisteredClient client, CustomUserDetails user, TenantId hint) {}

    public record Selection(String clientId, String authorizationRequestId, List<String> scopes,
                            List<TenantAuthorizationAttributes> options, TenantAuthorizationAttributes selected) {}
}
