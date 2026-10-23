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
        for (Object version : List.of(2, 2L, new java.math.BigDecimal("2.0"))) {
            var report = catalog(); report.put("schemaVersion", version); assertNotNull(SkillExecutionDeclaration.parse(report));
        }
    }
    @Test void strictlyRejectsUnknownIdentityAndUnsafeRevisionFields() {
        assertNull(SkillExecutionDeclaration.parse(null)); assertNull(SkillExecutionDeclaration.parse(List.of()));
        for (String field : List.of("tenantId", "ownerJiacn", "installationId", "costInfo", "endpoint", "token")) {
            var report = catalog(); report.put(field, "forbidden"); assertNull(SkillExecutionDeclaration.parse(report), field);
        }
        for (Object version : List.of("2", 1, 2.1, 0, true, Double.NaN)) {
            var report = catalog(); report.put("schemaVersion", version); assertNull(SkillExecutionDeclaration.parse(report));
        }
        for (Object revision : List.of(7, "0", "07", "+7", "7\n", "", "9".repeat(101))) {
            var report = catalog(); report.put("catalogRevision", revision); assertNull(SkillExecutionDeclaration.parse(report));
        }
    }
    @Test void rejectsPartialMalformedRowsAndDuplicateVersionsInsteadOfPickingOne() {
        for (String field : List.of("skillKey", "skillVersion", "packageSha256", "manifestSha256", "runner", "readiness",
                "reasonCode", "toolchainDigest", "bindingRevision", "skillInstallationId", "productVersionId", "kind", "policyRevision", "enabled", "observedAt")) {
            var row = skill("provider-operation", "READY"); row.remove(field);
            var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report), field);
        }
        for (var entry : Map.of("skillKey", "Image_Edit", "skillVersion", "1.0.0\n", "packageSha256", "a".repeat(64)+"\n",
                "runner", 7, "readiness", "AVAILABLE", "toolchainDigest", "BAD", "bindingRevision", "4\n").entrySet()) {
            var row = skill("provider-operation", "READY"); row.put(entry.getKey(), entry.getValue());
            var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report), entry.getKey());
        }
        var row = skill("native-model", "READY"); row.put("costInfo", Map.of());
        var report = catalog(); report.put("skills", List.of(row)); assertNull(SkillExecutionDeclaration.parse(report));
        report.put("skills", List.of(skill("native-model", "READY"), skill("native-model", "READY")));
        assertNull(SkillExecutionDeclaration.parse(report));
    }
    @Test void schemaTwoReadinessIsAReportNotAnIndependentTaskAuthorization() {
        // SM-C1 permits nullable measurements and free-form runner text. Execution
        // admission separately verifies READY + exact manifest, package, policy and toolchain.
        for(String state:List.of("UNKNOWN","OFFLINE","VERIFYING","READY","DEPENDENCY_MISSING","PROVIDER_UNBOUND","VERSION_MISMATCH")) {
            var row=skill("native-model",state);row.put("reasonCode",null);row.put("toolchainDigest",null);
            var report=catalog();report.put("skills",List.of(row));assertNotNull(SkillExecutionDeclaration.parse(report),state);
        }
        var row=skill("native-model","READY");row.put("manifestSha256",null);
        var report=catalog();report.put("skills",List.of(row));assertNull(SkillExecutionDeclaration.parse(report));
    }
    @Test void differentExactInstallationsMayReportTheSameVersionButDuplicateInstallationIsRejected() {
        var one=skill("native-model","READY");var two=new LinkedHashMap<String,Object>(one);
        two.put("skillInstallationId","si_other");two.put("runner","reported-custom-runner");two.put("reasonCode","DEPENDENCY_MISSING");
        var report=catalog();report.put("skills",List.of(one,two));assertNotNull(SkillExecutionDeclaration.parse(report));
        two.put("skillInstallationId",one.get("skillInstallationId"));two.put("skillVersion","2.0.0");
        assertNull(SkillExecutionDeclaration.parse(report));
    }
    @Test void unchangedFactsMayBeRemeasuredAtSameRevisionButObservationsCannotGoBackward() {
        var registry=new SkillExecutionSessionRegistry();var scope=new cn.jia.agent.service.SkillCapabilityLookup.Scope("0","client","owner","agent");
        var fence=new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence("rti_"+"1".repeat(32),"host","runtime",1);
        var report=catalog();var first=skill("native-model","READY");first.put("observedAt",100L);report.put("skills",List.of(first));
        registry.register("socket",scope,fence,SkillExecutionDeclaration.parse(report),()->true);
        var newer=new LinkedHashMap<String,Object>(first);newer.put("observedAt",101L);report.put("skills",List.of(newer));
        registry.register("socket",scope,fence,SkillExecutionDeclaration.parse(report),()->true);
        assertEquals(101L,registry.lookup(scope,fence).catalog().skills().getFirst().observedAt());
        report.put("skills",List.of(first));assertThrows(IllegalArgumentException.class,()->registry.register("socket",scope,fence,SkillExecutionDeclaration.parse(report),()->true));
    }
    @Test void sameSessionRejectsCatalogRollbackAndSameRevisionDifferentContentEvenAfterSuspension() {
        var registry=new SkillExecutionSessionRegistry();var scope=new cn.jia.agent.service.SkillCapabilityLookup.Scope("0","client","owner","agent");
        var fence=new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence("rti_"+"1".repeat(32),"host","runtime",1);
        var accepted=SkillExecutionDeclaration.parse(catalog());assertNotNull(accepted);
        registry.register("socket",scope,fence,accepted,()->true);registry.suspend("socket");
        var rollback=catalog();rollback.put("catalogRevision","6");
        assertThrows(IllegalArgumentException.class,()->registry.register("socket",scope,fence,SkillExecutionDeclaration.parse(rollback),()->true));
        var conflicting=catalog();conflicting.put("skills",List.of());
        assertThrows(IllegalArgumentException.class,()->registry.register("socket",scope,fence,SkillExecutionDeclaration.parse(conflicting),()->true));
        registry.register("socket",scope,fence,accepted,()->true);
        assertEquals(accepted,registry.lookup(scope,fence).catalog());
        // Revisions are comparable within a session, not globally across a new authenticated boot.
        registry.remove("socket");var next=new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence(fence.runtimeInstallationId(),"host","next-runtime",2);
        registry.register("next-socket",scope,next,SkillExecutionDeclaration.parse(rollback),()->true);
        assertEquals("6",registry.lookup(scope,next).catalog().catalogRevision());
    }
}
