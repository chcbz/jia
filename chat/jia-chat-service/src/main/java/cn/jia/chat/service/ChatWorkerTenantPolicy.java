package cn.jia.chat.service;

import cn.jia.core.tenant.TenantId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Process-owned allowlist for chat background workers. */
@Component
public final class ChatWorkerTenantPolicy {
    private static final String ALLOWED_TENANTS = "cyf.worker.allowed-tenants";
    private static final String LOCAL_TEST_PROFILE = "local-test-area";
    private static final String LOCAL_TEST_STAGE = "LOCAL_TEST";
    private static final ConfigurationPropertyName ALLOWED_TENANTS_NAME =
            ConfigurationPropertyName.of(ALLOWED_TENANTS);
    private static final Set<String> LEGACY_DEFAULT = Set.of("0");
    private static final Set<String> LOCAL_TEST_ALLOWED = Set.of("1");

    private final Set<String> allowedTenants;

    /** Creates the process bean exclusively from Spring's current Environment. */
    @Autowired
    public ChatWorkerTenantPolicy(Environment environment) {
        this(resolve(environment));
    }

    /** Creates a policy from an explicit, already selected allowlist for focused callers and tests. */
    public ChatWorkerTenantPolicy(Set<String> allowedTenants) {
        this.allowedTenants = immutableCanonicalSet(allowedTenants);
    }

    public Set<String> allowedTenants() {
        return allowedTenants;
    }

    public boolean allows(String tenantId) {
        return allowedTenants.contains(tenantId);
    }

    private static Set<String> resolve(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        boolean localTestArea = isLocalTestArea(environment);
        validateRawConfiguration(environment);
        BindResult<List<String>> binding;
        try {
            binding = Binder.get(environment).bind(ALLOWED_TENANTS, Bindable.listOf(String.class));
        } catch (RuntimeException ex) {
            throw invalidConfiguration("cannot bind allowlist", ex);
        }

        if (!binding.isBound()) {
            if (hasExplicitEmptyValue(environment)) {
                throw invalidConfiguration("allowlist is explicitly empty");
            }
            if (localTestArea) {
                throw invalidConfiguration("local TEST requires tenant 1");
            }
            return LEGACY_DEFAULT;
        }

        Set<String> configured = immutableCanonicalList(binding.get());
        if (localTestArea && !LOCAL_TEST_ALLOWED.equals(configured)) {
            throw invalidConfiguration("local TEST allowlist must contain only tenant 1");
        }
        return configured;
    }

    private static void validateRawConfiguration(Environment environment) {
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            ConfigurationProperty scalar = source.getConfigurationProperty(ALLOWED_TENANTS_NAME);
            if (scalar != null) {
                validateRawScalar(String.valueOf(scalar.getValue()));
                return;
            }
            ConfigurationProperty first = indexedProperty(source, 0);
            if (first != null) {
                LinkedHashSet<String> raw = new LinkedHashSet<>();
                int index = 0;
                ConfigurationProperty property = first;
                while (property != null) {
                    String canonical = canonicalTenant(String.valueOf(property.getValue()));
                    if (!raw.add(canonical)) {
                        throw invalidConfiguration("allowlist contains a duplicate tenant");
                    }
                    property = indexedProperty(source, ++index);
                }
                return;
            }
        }
    }

    private static void validateRawScalar(String rawScalar) {
        immutableCanonicalList(List.of(rawScalar.split(",", -1)));
    }

    private static ConfigurationProperty indexedProperty(
            ConfigurationPropertySource source, int index) {
        return source.getConfigurationProperty(
                ConfigurationPropertyName.of(ALLOWED_TENANTS + "[" + index + "]"));
    }

    private static boolean isLocalTestArea(Environment environment) {
        return List.of(environment.getActiveProfiles()).contains(LOCAL_TEST_PROFILE)
                || LOCAL_TEST_STAGE.equals(environment.getProperty("cyf.deployment.stage"));
    }

    private static boolean hasExplicitEmptyValue(Environment environment) {
        return environment.containsProperty(ALLOWED_TENANTS)
                || environment.containsProperty(ALLOWED_TENANTS + "[0]");
    }

    private static Set<String> immutableCanonicalList(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            throw invalidConfiguration("allowlist is empty");
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String tenantId : configured) {
            String canonical = canonicalTenant(tenantId);
            if (!copy.add(canonical)) {
                throw invalidConfiguration("allowlist contains a duplicate tenant");
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Set<String> immutableCanonicalSet(Set<String> configured) {
        Objects.requireNonNull(configured, "allowedTenants");
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("allowedTenants must not be empty");
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String tenantId : configured) {
            try {
                copy.add(TenantId.of(tenantId).value());
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("allowedTenants must contain canonical tenant ids", ex);
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String canonicalTenant(String tenantId) {
        try {
            return TenantId.of(tenantId).value();
        } catch (RuntimeException ex) {
            throw invalidConfiguration("allowlist contains an invalid tenant", ex);
        }
    }

    private static IllegalStateException invalidConfiguration(String reason) {
        return new IllegalStateException("Invalid " + ALLOWED_TENANTS + ": " + reason);
    }

    private static IllegalStateException invalidConfiguration(String reason, Throwable cause) {
        return new IllegalStateException("Invalid " + ALLOWED_TENANTS + ": " + reason, cause);
    }
}
