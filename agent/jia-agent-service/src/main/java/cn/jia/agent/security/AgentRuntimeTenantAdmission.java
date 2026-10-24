package cn.jia.agent.security;

import cn.jia.core.tenant.TenantId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Server-owned deployment admission for non-JWT Runtime credentials. */
@Component
public final class AgentRuntimeTenantAdmission {
    private final TenantId fixedTenant;
    private final Set<TenantId> allowedPlatformTenants;
    private final Map<String, TenantId> fixedClientTenants;

    @Autowired
    public AgentRuntimeTenantAdmission(Environment environment) {
        this(environment.getProperty("cyf.tenant.admission-mode", "platform"),
                environment.getProperty("cyf.tenant.fixed-tenant-id"),
                allowed(environment),
                environment.getProperty("cyf.tenant.require-bound-identity", Boolean.class, false),
                Binder.get(environment).bind("cyf.tenant.fixed-client-tenants",
                        Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
    }

    AgentRuntimeTenantAdmission(String mode, String fixedTenantId,
            Set<String> allowedTenants, boolean requireBoundIdentity) {
        this(mode, fixedTenantId, allowedTenants, requireBoundIdentity, Map.of());
    }

    AgentRuntimeTenantAdmission(String mode, String fixedTenantId,
            Set<String> allowedTenants, boolean requireBoundIdentity,
            Map<String, String> configuredClientTenants) {
        if ("fixed-tenant".equals(mode)) {
            if (requireBoundIdentity || !allowedTenants.isEmpty()) throw invalidConfiguration();
            fixedTenant = canonical(fixedTenantId);
            allowedPlatformTenants = Set.of();
            Map<String, TenantId> mappings = new LinkedHashMap<>();
            configuredClientTenants.forEach((client, tenantText) -> {
                if (client == null || client.isBlank() || !client.equals(client.strip())
                        || client.chars().anyMatch(Character::isISOControl)
                        || !fixedTenant.equals(canonical(tenantText))
                        || mappings.put(client, fixedTenant) != null) throw invalidConfiguration();
            });
            if (mappings.isEmpty()) throw invalidConfiguration();
            fixedClientTenants = Map.copyOf(mappings);
        } else if ("platform".equals(mode)) {
            if (fixedTenantId != null && !fixedTenantId.isBlank()
                    || !configuredClientTenants.isEmpty()) throw invalidConfiguration();
            Set<TenantId> canonical = new LinkedHashSet<>();
            for (String tenant : allowedTenants) {
                if (!canonical.add(canonical(tenant))) throw invalidConfiguration();
            }
            if (requireBoundIdentity != !canonical.isEmpty()) throw invalidConfiguration();
            fixedTenant = null;
            allowedPlatformTenants = Set.copyOf(canonical);
            fixedClientTenants = Map.of();
        } else {
            throw invalidConfiguration();
        }
    }

    static AgentRuntimeTenantAdmission unrestrictedPlatform() {
        return new AgentRuntimeTenantAdmission("platform", null, Set.of(), false, Map.of());
    }

    TenantId requireAdmitted(String tenantId, String clientId) {
        TenantId tenant = canonical(tenantId);
        if (clientId == null || clientId.isBlank() || !clientId.equals(clientId.strip())
                || clientId.chars().anyMatch(Character::isISOControl)) {
            throw AgentRuntimeAuthenticationService.denied();
        }
        if (fixedTenant != null ? !fixedTenant.equals(tenant)
                : !allowedPlatformTenants.isEmpty() && !allowedPlatformTenants.contains(tenant)) {
            throw AgentRuntimeAuthenticationService.denied();
        }
        if (fixedTenant != null && !tenant.equals(fixedClientTenants.get(clientId))) {
            throw AgentRuntimeAuthenticationService.denied();
        }
        return tenant;
    }

    private static Set<String> allowed(Environment environment) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; ; index++) {
            String value = environment.getProperty("cyf.tenant.allowed-tenants[" + index + "]");
            if (value == null) break;
            result.add(value);
        }
        String commaSeparated = environment.getProperty("cyf.tenant.allowed-tenants");
        if (commaSeparated != null && !commaSeparated.isBlank()) {
            Arrays.stream(commaSeparated.split(",", -1)).forEach(result::add);
        }
        return result;
    }

    private static TenantId canonical(String value) {
        try {
            if (value == null || !value.equals(value.strip())) throw new IllegalArgumentException();
            return TenantId.of(value);
        } catch (RuntimeException invalid) {
            throw invalidConfiguration();
        }
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Invalid Runtime tenant admission configuration");
    }
}
