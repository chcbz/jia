package cn.jia.oauth.security;

import cn.jia.user.entity.CustomUserDetails;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@ConditionalOnExpression("'${cyf.oauth.authorization-server.enabled:true}' == 'true' && "
        + "'${cyf.tenant.admission-mode:platform}' == 'fixed-tenant'")
public final class FixedTenantConsentService {
    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final FixedTenantAuthorizationPolicy policy;

    public FixedTenantConsentService(OAuth2AuthorizationService authorizations,
            RegisteredClientRepository clients, FixedTenantAuthorizationPolicy policy) {
        this.authorizations = Objects.requireNonNull(authorizations);
        this.clients = Objects.requireNonNull(clients);
        this.policy = Objects.requireNonNull(policy);
    }

    public Consent view(HttpSession session, String state, Authentication principal) {
        OAuth2Authorization authorization = authorizations.findByToken(
                state, TenantAuthorizationRequestProvider.CONSENT_STATE);
        FixedTenantAuthorizationRequestProvider.requireSessionAuthorization(session, state, authorization);
        OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
        FixedTenantAuthorizationBinding binding = FixedTenantConsentGrantProvider.fixedBinding(authorization);
        CustomUserDetails saved = UserIssuanceAccountLock.savedUser(authorization);
        if (request == null || client == null || saved == null
                || !client.getClientId().equals(request.getClientId())
                || !policy.permits(binding, client.getClientId())
                || !FixedTenantConsentGrantProvider.sameLogin(authorization, principal, saved)
                || !request.getScopes().containsAll(authorization.getAuthorizedScopes())) {
            throw TenantAuthorizationRequestProvider.invalidRequest();
        }
        return new Consent(client.getClientId(), state, List.copyOf(request.getScopes()));
    }

    public record Consent(String clientId, String state, List<String> scopes) {}
}
