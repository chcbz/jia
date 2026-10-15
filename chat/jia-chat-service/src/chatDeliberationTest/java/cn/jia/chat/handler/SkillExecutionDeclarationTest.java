package cn.jia.chat.handler;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.jia.chat.handler.SkillExecutionFixtures.*;

class SkillExecutionDeclarationTest {
    @Test void acceptsMultipleMeasuredRunnersAndImmutableEmptyCatalogWithoutPermissionsOrFees() {
        var parsed = SkillExecutionDeclaration.parse(catalog()); assertNotNull(parsed);
        assertEquals(2, parsed.skills().size());
        assertThrows(UnsupportedOperationException.class, () -> parsed.skills().clear());
        var empty = catalog(); empty.put("skills", List.of()); assertNotNull(SkillExecutionDeclaration.parse(empty));
        for (Object version : List.of(1, 1L, new java.math.BigDecimal("1.0"))) {
            var report = catalog(); report.put("schemaVersion", version); assertNotNull(SkillExecutionDeclaration.parse(report));
        }
    }
    @Test void strictlyRejectsUnknownIdentityAndUnsafeRevisionFields() {
        assertNull(SkillExecutionDeclaration.parse(null)); assertNull(SkillExecutionDeclaration.parse(List.of()));
        for (String field : List.of("tenantId", "ownerJiacn", "installationId", "costInfo", "endpoint", "token")) {
            var report = catalog(); report.put(field, "forbidden"); assertNull(SkillExecutionDeclaration.parse(report), field);
        }
        for (Object version : List.of("1", 1.1, 0, true, Double.NaN)) {
            var report = catalog(); report.put("schemaVersion", version); assertNull(SkillExecutionDeclaration.parse(report));
        }
        for (Object revision : List.of(7, "0", "07", "+7", "7\n", "", "9".repeat(101))) {
            var report = catalog(); report.put("catalogRevision", revision); assertNull(SkillExecutionDeclaration.parse(report));
        }
    }
    @Test void rejectsPartialMalformedRowsAndDuplicateVersionsInsteadOfPickingOne() {
        for (String field : List.of("skillKey", "skillVersion", "packageSha256", "manifestSha256", "runner", "readiness",
                "reasonCode", "toolchainDigest", "bindingRevision")) {
            var row = skill("provider-operation", "READY"); row.remove(field);
            var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report), field);
        }
        for (var entry : Map.of("skillKey", "Image_Edit", "skillVersion", "1.0.0\n", "packageSha256", "a".repeat(64)+"\n",
                "runner", "arbitrary-code", "readiness", "AVAILABLE", "toolchainDigest", "BAD", "bindingRevision", "4\n").entrySet()) {
            var row = skill("provider-operation", "READY"); row.put(entry.getKey(), entry.getValue());
            var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report), entry.getKey());
        }
        var row = skill("native-model", "READY"); row.put("costInfo", Map.of());
        var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report));
        report.put("skills", List.of(skill("native-model", "READY"), skill("native-model", "READY")));
        assertNull(SkillExecutionDeclaration.parse(report));
    }
    @Test void readinessMustHaveMeasuredToolAndProviderBindingAndFailureMustHaveReason() {
        for (String field : List.of("toolchainDigest", "bindingRevision")) {
            var row = skill("provider-operation", "READY"); row.put(field, null);
            var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report));
        }
        var row = skill("native-model", "READY"); row.put("reasonCode", "NOT_READY");
        var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report));
        row = skill("native-model", "UNAVAILABLE"); row.put("reasonCode", null); report.put("skills", List.of(row));
        assertNull(SkillExecutionDeclaration.parse(report));
    }
}
