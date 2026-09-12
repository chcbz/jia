package cn.jia.agent.skill;
import cn.jia.economy.entity.skill.SkillInstallationEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SkillInstallResultContractTest {
    private final SkillInstallationEntity install=new SkillInstallationEntity().setInstallationId("si_1").setOrderId("so_1")
            .setProductVersionId("spv_1").setCommandId("cmd_skill_si_1").setTargetAgentId("agt_1")
            .setAttempt(1).setFencingToken(1L).setDeliveryEpoch(1L).setSkillKey("repo-test").setSkillVersion("1.0.0").setPackageSha256(new byte[32]);
    private Map<String,Object> valid() {
        Map<String,Object> b=new HashMap<>();
        b.put("schemaVersion",1);b.put("messageType","work.result");b.put("messageId","msg_1");b.put("resultType","SKILL_INSTALL_RESULT");
        b.put("commandId","cmd_skill_si_1");b.put("attempt",1);b.put("fencingToken","1");b.put("deliveryEpoch","1");
        b.put("orderId","so_1");b.put("installationId","si_1");b.put("targetAgentId","agt_1");b.put("productVersionId","spv_1");
        b.put("status","SUCCEEDED");b.put("packageDigest",SkillMarketplaceService.digest(new byte[32]));b.put("skillKey","repo-test");b.put("skillVersion","1.0.0");
        b.put("installedAt","1788650000000");b.put("failureCode",null);return b;
    }
    @Test void exactInstallerResultAndStableAcknowledgement() {
        var b=valid(); assertDoesNotThrow(()->SkillInstallResultService.validateEnvelope(b,install));
        assertEquals(SkillInstallResultService.receipt(b),SkillInstallResultService.receipt(b));
        assertEquals("ACCEPTED",SkillInstallResultService.receipt(b).get("receiptStatus"));
    }
    @Test void allFrozenIdentityAndFenceFieldsRejectTampering() {
        for(String field:List.of("schemaVersion","messageType","resultType","commandId","attempt","fencingToken","deliveryEpoch","orderId",
                "installationId","targetAgentId","productVersionId","packageDigest","skillKey","skillVersion","status")) {
            var body=valid();body.put(field,field.equals("schemaVersion") || field.equals("attempt")?2:"different");
            assertThrows(SkillMarketplaceException.class,()->SkillInstallResultService.validateEnvelope(body,install),field);
        }
    }
    @Test void ambiguousInstallerFailureCodesNeverAuthorizeRefund() {
        for(String code:List.of("SKILL_INSTALL_IO_FAILED","SKILL_INSTALL_CONFLICT","SKILL_INSTALL_COMMAND_INVALID","UNKNOWN"))
            assertFalse(SkillInstallResultService.SAFE_FAILURES.contains(code));
        assertTrue(SkillInstallResultService.SAFE_FAILURES.contains("SKILL_PACKAGE_DIGEST_MISMATCH"));
    }
    @Test void alternateAgentAndHiddenNestedPayloadAreRejected() {
        var b=valid();b.put("sourceAgentId","other"); final var wrongAgent=b;
        assertThrows(SkillMarketplaceException.class,()->SkillInstallResultService.validateEnvelope(wrongAgent,install));
        b=valid();b.put("payload",Map.of("status","SUCCEEDED"));final var nested=b;
        assertThrows(SkillMarketplaceException.class,()->SkillInstallResultService.resultHash(nested));
    }
}
