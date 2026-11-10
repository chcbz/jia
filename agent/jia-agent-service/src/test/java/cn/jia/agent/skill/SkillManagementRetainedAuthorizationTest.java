package cn.jia.agent.skill;

import cn.jia.agent.entity.skill.PaidSkillInventoryEntity;
import cn.jia.agent.entity.skill.SkillInstallationAuthorizationEntity;
import cn.jia.agent.mapper.skill.SkillManagementMapper;
import cn.jia.agent.service.SkillCapabilityLookup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import static cn.jia.agent.skill.SkillManagementCodec.*;

class SkillManagementRetainedAuthorizationTest {
    private static final SkillCapabilityLookup.Scope SCOPE=new SkillCapabilityLookup.Scope("0","client","owner","a");
    static String inventory(String installation,String digest) {
        return json(List.of(map("skillKey","vision","source","MANAGED","revision","b".repeat(64),"enabled",false,
                "installation",map("skillInstallationId",installation,"productVersionId","spv_vision","skillVersion","1.0.0",
                        "kind","INSTRUCTION","packageSha256",digest,"manifestSha256",null)),
                map("skillKey","native","source","NATIVE","revision","c".repeat(64),"enabled",true,"installation",null)));
    }
    static PaidSkillInventoryEntity paid(String snapshot) {
        return new PaidSkillInventoryEntity().setPrincipalId("new-buyer").setOwnerJiacn("owner").setBindingId(8L)
                .setLeaseId("lease-next").setIntentId("initial-next").setCaptureTransactionId("capture-next")
                .setRuntimeInstallationId("rt_a").setRuntimeHostId("host").setInventorySnapshotJson(snapshot);
    }
    @Test void disabledSchemaAvoidsAnyInventoryQuery() {
        var mapper=mock(SkillManagementMapper.class);
        assertNull(new SkillRelationshipAuthorizations(mapper,false).read(SCOPE));verifyNoInteractions(mapper);
    }
    @Test void exactPaidReferenceIncludesDisabledButNeverInventsNativeInstallation() {
        var mapper=mock(SkillManagementMapper.class);var snapshot=inventory("si_a","a".repeat(64));
        when(mapper.paidInventory("0","client","a","owner")).thenReturn(paid(snapshot));
        var relation=new SkillRelationshipAuthorizations(mapper,true).read(SCOPE);
        assertEquals(1,relation.installations().size());assertFalse(relation.installations().get("si_a").enabled());
        var reference=map("skillInstallationId","si_a","productVersionId","spv_vision","skillVersion","1.0.0","kind","INSTRUCTION","packageSha256","a".repeat(64),"manifestSha256",null);
        var auth=new SkillInstallationAuthorizationEntity().setInstallationId("si_a").setPackageJson(json(reference));
        assertTrue(relation.covers(auth));reference.put("packageSha256","d".repeat(64));auth.setPackageJson(json(reference));assertFalse(relation.covers(auth));
        assertEquals("new-buyer",relation.actorId());verify(mapper).paidInventory("0","client","a","owner");
    }
    @Test void missingPaidCaptureNeverBecomesEmptyOrFreeGrantAndCorruptSnapshotIsUnavailable() {
        var mapper=mock(SkillManagementMapper.class);var service=new SkillRelationshipAuthorizations(mapper,true);
        assertNull(service.read(SCOPE));when(mapper.paidInventory("0","client","a","owner")).thenReturn(paid("{broken"));
        assertEquals("SKILL_STATE_UNAVAILABLE",assertThrows(SkillMarketplaceException.class,()->service.read(SCOPE)).code());
    }
    @Test void recommendationsUseNewPaidRuntimeFenceAndReadInventoryOnlyOncePerAgent() {
        var f=new SkillManagementRecommendationTest.Fixture();f.installed("a");
        var auth=f.db.activeAuthorizationsForAgent("0","client","a").getFirst();auth.setRuntimeInstallationId("retired-runtime");
        f.service.setRelationshipAuthorizations(new SkillRelationshipAuthorizations(f.db,true));
        assertFalse(f.service.recommendationSkills(SkillManagementRecommendationTest.ACTOR,SkillManagementRecommendationTest.candidates("a")).get("a").getFirst().available());
        when(f.db.paidInventory("0","client","a","owner")).thenReturn(paid(inventory("si_a","a".repeat(64))));
        clearInvocations(f.db);
        assertTrue(f.service.recommendationSkills(SkillManagementRecommendationTest.ACTOR,SkillManagementRecommendationTest.candidates("a")).get("a").getFirst().available());
        verify(f.db).paidInventory("0","client","a","owner");assertEquals("retired-runtime",auth.getRuntimeInstallationId());
    }
}
