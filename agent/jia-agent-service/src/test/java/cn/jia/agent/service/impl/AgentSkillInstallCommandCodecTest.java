package cn.jia.agent.service.impl;
import cn.jia.agent.entity.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentSkillInstallCommandCodecTest {
    private AgentCommandDraft draft() {
        return new AgentCommandDraft(1,"cmd_skill_si_1","so_1","si_1","Tenant-A","Client-A","so_1",null,"agt_1","SKILL_INSTALL",1,3600001,
                new AgentSkillInstallPayload("so_1","si_1","spv_repo_test_1_0_0","repo-test","1.0.0","522","sha256:"+"a".repeat(64),"/internal/agent/skill-installations/si_1/package"));
    }
    @Test void exactBusinessRoundTripAndFlatFrozenW10Fields() {
        var d=draft();assertEquals(d,AgentCommandCanonicalCodec.decodeBusinessBytes(AgentCommandCanonicalCodec.businessBytes(d)));
        var json=JsonMapper.builder().build().readTree(AgentCommandCanonicalCodec.wireBytes(d,"message-1"));
        assertEquals("command.dispatch",json.get("messageType").asString());assertEquals("si_1",json.get("requestId").asString());
        assertEquals("1",json.get("fencingToken").asString());assertEquals("1",json.get("deliveryEpoch").asString());assertTrue(json.get("attempt").isIntegralNumber());
        for(String field:List.of("orderId","installationId","productVersionId","skillKey","skillVersion","packageSize","packageDigest","downloadPath"))
            assertEquals(json.get(field),json.get("payload").get(field));
        assertFalse(AgentCommandCanonicalCodec.isHallIntentCommand(d));assertTrue(AgentCommandCanonicalCodec.isSupportedCommandType("SKILL_INSTALL"));
    }
    @Test void arbitraryPackagePathAndConflictingOrderIdentityAreRejected() {
        var d=draft();var bad=new AgentCommandDraft(1,d.commandId(),d.correlationId(),d.causationId(),d.tenantId(),d.clientId(),d.taskId(),null,d.targetAgentId(),"SKILL_INSTALL",1,3600001,
                new AgentSkillInstallPayload("other-order","si_1","spv_repo_test_1_0_0","repo-test","1.0.0","522","sha256:"+"a".repeat(64),"https://untrusted/package"));
        assertThrows(IllegalArgumentException.class,()->AgentCommandCanonicalCodec.businessBytes(bad));
    }
}
