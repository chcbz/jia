package cn.jia.economy.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyPreviewConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(EconomyConfiguration.class);

    @Test
    void absentConfigurationStartsDisabledWithoutJdbcOrSchemaMutation() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(EconomyPreviewGate.class).allows("tenant-a", "client-a"));
            assertTrue(context.getBeansOfType(EconomySchemaInitializer.class).isEmpty());
        });
    }

    @Test
    void applicationContextBindsCanonicalConstructorFields() {
        RUNNER.withPropertyValues(
                        "economy.preview.enabled=true",
                        "economy.preview.test-issuance-enabled=true",
                        "economy.preview.allowed-scopes[0].tenant-id=Tenant-A",
                        "economy.preview.allowed-scopes[0].client-id=Client-A")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    EconomyPreviewProperties properties = context.getBean(EconomyPreviewProperties.class);
                    assertTrue(properties.enabled());
                    assertTrue(properties.testIssuanceEnabled());
                    assertEquals(List.of(new EconomyPreviewProperties.AllowedScope("Tenant-A", "Client-A")),
                            properties.allowedScopes());
                });

        RUNNER.withPropertyValues(
                        "economy.preview.enabled=false",
                        "economy.preview.test-issuance-enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(EconomyPreviewProperties.class).testIssuanceEnabled());
                });
    }

    @Test
    void exactAllowlistIsCaseSensitiveAndEnabledRequiresAtLeastOneScope() {
        EconomyPreviewGate gate = new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(
                new EconomyPreviewProperties.AllowedScope("Tenant-A", "Client-A"))));
        assertTrue(gate.allows("Tenant-A", "Client-A"));
        assertFalse(gate.allows("tenant-a", "Client-A"));
        assertFalse(gate.allows("Tenant-A", "client-a"));
        assertThrows(IllegalStateException.class,
                () -> new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of())));
    }

    @Test
    void malformedEnabledConfigurationFailsBeforeSchemaBeanCanResolveJdbc() {
        RUNNER.withPropertyValues(
                        "economy.preview.enabled=true",
                        "economy.preview.allowed-scopes[0].tenant-id=tenant-*",
                        "economy.preview.allowed-scopes[0].client-id=client-a")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(failureChain(failure).contains("Invalid economy.preview configuration"),
                            failureChain(failure));
                });
    }

    @Test
    void exactScopeConfigurationRejectsControlNulAndUnpairedSurrogateIdentities() {
        for (String invalid : List.of("tenant\u0000a", "tenant\u001fa", "tenant\ud800", "tenant\udc00")) {
            assertThrows(IllegalStateException.class, () -> new EconomyPreviewGate(
                    new EconomyPreviewProperties(true, List.of(
                            new EconomyPreviewProperties.AllowedScope(invalid, "Client-A")))), invalid);
        }
    }

    @Test
    void checkNormalizationPreservesBooleanMeaningAndCompoundTriggerDdlIsBounded() {
        String canonical = "allow_negative in (0,1) and owner_type='SYSTEM'";
        String expected = EconomySchemaInitializer.normalizeCheckClause(
                "allow_negative IN (0,1) AND owner_type = 'SYSTEM'");
        String mysql = EconomySchemaInitializer.normalizeCheckClause(
                " ( ( `allow_negative` in ( 0 , 1 ) ) and "
                        + "( `owner_type` = _utf8mb4 'SYSTEM' ) ) ");
        String regrouped = EconomySchemaInitializer.normalizeCheckClause(
                "(allow_negative IN (0,1) AND owner_type = 'SYSTEM') OR purpose = 'PROVIDER_VARIANCE'");

        assertEquals(canonical, expected);
        assertEquals(canonical, mysql);
        assertFalse(expected.equals(regrouped));
        assertFalse(EconomySchemaInitializer.normalizeCheckClause("currency = 'SILVER'")
                .equals(EconomySchemaInitializer.normalizeCheckClause("currency = 'silver'")));
        assertEquals("label='A ( B, C <> D ) and O''Brien'",
                EconomySchemaInitializer.normalizeCheckClause(
                        " ( label = _utf8mb4 'A ( B, C <> D ) and O''Brien' ) "));

        String mysqlCatalog = "((`currency` = _utf8mb4\\'SILVER\\') AND "
                + "(LENGTH(`request_hash`) = 32))";
        String mysqlCatalogCaseDrift = "((`currency` = _utf8mb4\\'silver\\') AND "
                + "(OCTET_LENGTH(`request_hash`) = 32))";
        assertEquals("currency='SILVER' and octet_length(request_hash)=32",
                EconomySchemaInitializer.normalizeCheckClause(mysqlCatalog));
        assertEquals("label='O''Brien'", EconomySchemaInitializer.normalizeCheckClause(
                "(`label` = _utf8mb4\\'O\\'\\'Brien\\')"));
        assertFalse(EconomySchemaInitializer.normalizeCheckClause(mysqlCatalog)
                .equals(EconomySchemaInitializer.normalizeCheckClause(mysqlCatalogCaseDrift)));
        assertEquals(5, EconomySchemaInitializer.tableDdlStatements().size());
    }

    @Test
    void triggerNormalizationPreservesLiteralBytesWhileCanonicalizingOutsideFormatting() {
        String literal = " (( BEGIN\n SIGNAL SQLSTATE _utf8mb4 '45000' "
                + "SET `MESSAGE_TEXT` = _UTF8MB4 '_utf8mb4 MiXeD (A = B)  O''Brien\\'s'; END )) ";
        String changedLiteral = " (( BEGIN\n SIGNAL SQLSTATE _utf8mb4 '45000' "
                + "SET `MESSAGE_TEXT` = _UTF8MB4 '_utf8mb4 miXeD (A = B)  O''Brien\\'s'; END )) ";
        String canonical = "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable'; END";
        String formatted = " (( BEGIN\n SIGNAL SQLSTATE _utf8mb4 '45000' "
                + "SET `MESSAGE_TEXT` = _UTF8MB4 'immutable'; END )) ";

        assertEquals("begin signal sqlstate '45000' set message_text='_utf8mb4 MiXeD (A = B)  O''Brien\\'s'; end",
                EconomySchemaInitializer.normalizeTriggerSql(literal));
        assertNotEquals(EconomySchemaInitializer.normalizeTriggerSql(literal),
                EconomySchemaInitializer.normalizeTriggerSql(changedLiteral));
        assertEquals(EconomySchemaInitializer.normalizeTriggerSql(canonical),
                EconomySchemaInitializer.normalizeTriggerSql(formatted));
    }

    @Test
    void allBundledProfilesExplicitlyRemainDisabled() throws Exception {
        Path root = apiRoot();
        for (String profile : List.of(
                "starter/src/main/resources/application-dev.properties",
                "starter/src/main/resources/application-grey.properties",
                "starter/src/main/resources/application-prod.properties",
                "starter/src/test/resources/application-test.properties")) {
            List<String> declarations = Files.readAllLines(root.resolve(profile), StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith("economy.preview.enabled="))
                    .toList();
            assertEquals(List.of("economy.preview.enabled=false"), declarations, profile);
        }
    }

    private static String failureChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private static Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("economy"))) return candidate;
        }
        throw new IllegalStateException("Cannot locate API worktree root from " + current);
    }
}
