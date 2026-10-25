package cn.jia.agent.skill;

import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.service.AgentHostedBindingTransaction;
import cn.jia.agent.service.AgentService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.security.TenantClaimPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public final class SkillRosterProjection {
    private static final String LEGACY_SKILL_TENANT = "0";

    private final HostingRentOwnerResolver owners;
    private final ObjectProvider<AgentService> agents;
    private final SkillAgentVersions versions;
    private final SkillMarketplaceService skills;

    public SkillRosterProjection(HostingRentOwnerResolver owners, ObjectProvider<AgentService> agents,
            SkillAgentVersions versions, SkillMarketplaceService skills) {
        this.owners = owners;
        this.agents = agents;
        this.versions = versions;
        this.skills = skills;
    }

    public void project(Authentication authentication, List<AgentRuntimeDTO> rows) {
        AgentHostedBindingTransaction.Scope scope = trustedScope(authentication);
        HostingRentHttp.Actor legacyActor = null;
        if (scope != null && LEGACY_SKILL_TENANT.equals(scope.tenantId())) {
            try {
                legacyActor = HostingRentHttp.actor(authentication);
                if (!scope.clientId().equals(legacyActor.clientId())
                        || !scope.ownerJiacn().equals(owners.requireOwner(legacyActor))) {
                    scope = null;
                    legacyActor = null;
                }
            } catch (RuntimeException denied) {
                scope = null;
                legacyActor = null;
            }
        }

        for (AgentRuntimeDTO row : rows) {
            row.setVersion(null);
            row.setBoundToMe(false);
            row.setCanOperate(false);
            if (scope == null || row.getAgentId() == null || Boolean.TRUE.equals(row.getSystemAgent())
                    || !scope.ownerJiacn().equals(row.getOwnerJiacn())) {
                continue;
            }
            try {
                AgentRuntimeDTO trusted = LEGACY_SKILL_TENANT.equals(scope.tenantId())
                        ? agents.getObject().requireApiKeyOwnedAgent(
                                scope.clientId(), scope.ownerJiacn(), row.getAgentId())
                        : agents.getObject().requireRosterOwnedAgent(
                                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), row.getAgentId());
                if (!sameOperableOwner(scope, row, trusted)) {
                    continue;
                }
                row.setBoundToMe(true);
                row.setCanOperate(true);
                if (legacyActor != null) {
                    row.setVersion(Long.toString(versions.requireOwned(
                            legacyActor, row.getAgentId(), null, false)));
                }
            } catch (RuntimeException denied) {
                row.setVersion(null);
            }
        }
    }

    private static boolean sameOperableOwner(AgentHostedBindingTransaction.Scope scope,
            AgentRuntimeDTO projected, AgentRuntimeDTO trusted) {
        return trusted != null
                && Objects.equals(projected.getAgentId(), trusted.getAgentId())
                && Objects.equals(scope.ownerJiacn(), trusted.getOwnerJiacn())
                && !Boolean.TRUE.equals(trusted.getSystemAgent())
                && Boolean.TRUE.equals(trusted.getBoundToMe())
                && Boolean.TRUE.equals(trusted.getCanOperate());
    }

    private static AgentHostedBindingTransaction.Scope trustedScope(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            Map<String, Object> claims = jwt.getToken().getClaims();
            Object rawClientId = claims.get("client_id");
            Object rawOwner = claims.get("jiacn");
            if (!(rawClientId instanceof String clientId) || !(rawOwner instanceof String ownerJiacn)) {
                return null;
            }
            AgentHostedBindingTransaction.Scope scope = new AgentHostedBindingTransaction.Scope(
                    TenantClaimPolicy.resolve(claims, TenantClaimPolicy.defaultSingleTenant()),
                    clientId, ownerJiacn);
            EsContext context = EsContextHolder.getContext();
            if (!Objects.equals(scope.tenantId(), context.getTenantId())
                    || !Objects.equals(scope.clientId(), context.getClientId())
                    || !Objects.equals(scope.ownerJiacn(), context.getJiacn())) {
                return null;
            }
            return scope;
        } catch (RuntimeException denied) {
            return null;
        }
    }
}
