package cn.jia.tenantcard;

import cn.jia.chat.service.ChatWorkerTenantPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCardW00Test {
    private static final String ALLOWED_TENANTS = "cyf.worker.allowed-tenants";
    private static final List<String> RELAXED_ALLOWED_TENANTS =
            List.of("cyf.worker.allowedTenants", "cyf.worker.allowed_tenants");

    @Test
    void missingNonLocalConfigurationUsesOnlyLegacyTenantZero() {
        ChatWorkerTenantPolicy policy = new ChatWorkerTenantPolicy(new MockEnvironment());

        assertEquals(Set.of("0"), policy.allowedTenants());
        assertTrue(policy.allows("0"));
        assertFalse(policy.allows("1"));
    }

    @Test
    void localProfileBindsExactlyTenantOneAsAnInjectableSpringBean() {
        MockEnvironment environment = profileEnvironment();
        environment.withProperty(ALLOWED_TENANTS, "1");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(ChatWorkerTenantPolicy.class);
            context.refresh();

            ChatWorkerTenantPolicy policy = context.getBean(ChatWorkerTenantPolicy.class);
            assertEquals(Set.of("1"), policy.allowedTenants());
            assertTrue(policy.allows("1"));
            assertFalse(policy.allows("0"));
        }
    }

    @Test
    void localStageBindsExactlyTenantOne() {
        MockEnvironment environment = stageEnvironment();
        addTenants(environment, "1");

        ChatWorkerTenantPolicy policy = new ChatWorkerTenantPolicy(environment);

        assertEquals(Set.of("1"), policy.allowedTenants());
    }

    @Test
    void localProfileRejectsMissingZeroMixedEmptyDuplicateAndInvalidConfiguration() {
        assertAll("local-test-area profile must fail closed",
                rejectedLocalConfigurations(TenantCardW00Test::profileEnvironment));
    }

    @Test
    void localStageRejectsMissingZeroMixedEmptyDuplicateAndInvalidConfiguration() {
        assertAll("LOCAL_TEST stage must fail closed",
                rejectedLocalConfigurations(TenantCardW00Test::stageEnvironment));
    }

    @Test
    void nonLocalExplicitConfigurationAllowsCanonicalMultipleTenantsOnly() {
        MockEnvironment valid = new MockEnvironment();
        addTenants(valid, "1", "2");
        ChatWorkerTenantPolicy policy = new ChatWorkerTenantPolicy(valid);
        ChatWorkerTenantPolicy scalarPolicy =
                new ChatWorkerTenantPolicy(environmentWithRootValue("1,2"));

        assertEquals(Set.of("1", "2"), policy.allowedTenants());
        assertEquals(Set.of("1", "2"), scalarPolicy.allowedTenants());
        assertTrue(policy.allows("1"));
        assertTrue(policy.allows("2"));
        assertFalse(policy.allows("0"));

        assertAll(
                () -> assertRejected(environmentWithRootValue("")),
                () -> assertRejected(environmentWithTenants("1", "1")),
                () -> assertRejected(environmentWithTenants("01")),
                () -> assertRejected(environmentWithTenants(" 1")),
                () -> assertRejected(environmentWithTenants("1 ")),
                () -> assertRejected(environmentWithTenants("\t1\n")),
                () -> assertRejected(environmentWithTenants("1\u0000")),
                () -> assertRejected(environmentWithRootValue(" 1")),
                () -> assertRejected(environmentWithRootValue("1 ")),
                () -> assertRejected(environmentWithRootValue("\t1\n")),
                () -> assertRejected(environmentWithRootValue("1\u0000")));
    }

    @Test
    void explicitSetIsDefensivelyCopiedCanonicalAndExposedAsImmutable() {
        LinkedHashSet<String> source = new LinkedHashSet<>(List.of("1", "2"));
        ChatWorkerTenantPolicy policy = new ChatWorkerTenantPolicy(source);

        source.clear();
        source.add("0");

        assertEquals(Set.of("1", "2"), policy.allowedTenants());
        assertFalse(policy.allows("0"));
        assertThrows(UnsupportedOperationException.class, () -> policy.allowedTenants().add("3"));
        assertThrows(IllegalArgumentException.class, () -> new ChatWorkerTenantPolicy(Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatWorkerTenantPolicy(Set.of("01")));
    }

    @Test
    void relaxedScalarNamesPreserveRawValidationForOrdinaryProfileAndStageEnvironments() {
        List<Supplier<MockEnvironment>> environments =
                List.of(MockEnvironment::new,
                        TenantCardW00Test::profileEnvironment,
                        TenantCardW00Test::stageEnvironment);
        Stream.Builder<Executable> assertions = Stream.builder();
        for (String propertyName : RELAXED_ALLOWED_TENANTS) {
            for (Supplier<MockEnvironment> environmentFactory : environments) {
                assertions.add(() -> assertEquals(Set.of("1"),
                        policyWithProperty(environmentFactory.get(), propertyName, "1")
                                .allowedTenants()));
                for (String invalid : List.of(" 1", "1 ", "\t1\n", "1\u0000")) {
                    assertions.add(() -> assertRejected(
                            environmentFactory.get().withProperty(propertyName, invalid)));
                }
            }
        }
        assertAll("relaxed names must not bypass raw tenant validation", assertions.build());
    }

    private static Stream<Executable> rejectedLocalConfigurations(
            Supplier<MockEnvironment> environmentFactory) {
        return Stream.of(
                () -> assertRejected(environmentFactory.get()),
                () -> assertRejected(withTenants(environmentFactory.get(), "0")),
                () -> assertRejected(withTenants(environmentFactory.get(), "0", "1")),
                () -> assertRejected(withRootValue(environmentFactory.get(), "")),
                () -> assertRejected(withTenants(environmentFactory.get(), "1", "1")),
                () -> assertRejected(withTenants(environmentFactory.get(), "01")),
                () -> assertRejected(withRootValue(environmentFactory.get(), " 1")),
                () -> assertRejected(withRootValue(environmentFactory.get(), "1 ")),
                () -> assertRejected(withRootValue(environmentFactory.get(), "\t1\n")),
                () -> assertRejected(withRootValue(environmentFactory.get(), "1\u0000")));
    }

    private static MockEnvironment profileEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local-test-area");
        return environment;
    }

    private static MockEnvironment stageEnvironment() {
        return new MockEnvironment().withProperty("cyf.deployment.stage", "LOCAL_TEST");
    }

    private static MockEnvironment environmentWithTenants(String... tenants) {
        return withTenants(new MockEnvironment(), tenants);
    }

    private static MockEnvironment environmentWithRootValue(String value) {
        return withRootValue(new MockEnvironment(), value);
    }

    private static MockEnvironment withTenants(MockEnvironment environment, String... tenants) {
        addTenants(environment, tenants);
        return environment;
    }

    private static void addTenants(MockEnvironment environment, String... tenants) {
        for (int index = 0; index < tenants.length; index++) {
            environment.withProperty(ALLOWED_TENANTS + "[" + index + "]", tenants[index]);
        }
    }

    private static MockEnvironment withRootValue(MockEnvironment environment, String value) {
        return environment.withProperty(ALLOWED_TENANTS, value);
    }

    private static void assertRejected(MockEnvironment environment) {
        assertThrows(IllegalStateException.class, () -> new ChatWorkerTenantPolicy(environment));
    }

    private static ChatWorkerTenantPolicy policyWithProperty(
            MockEnvironment environment, String propertyName, String value) {
        return new ChatWorkerTenantPolicy(environment.withProperty(propertyName, value));
    }
}
