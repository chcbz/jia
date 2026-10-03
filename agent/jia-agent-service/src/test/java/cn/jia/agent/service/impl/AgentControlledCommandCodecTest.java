package cn.jia.agent.service.impl;

import cn.jia.agent.entity.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class AgentControlledCommandCodecTest {
    static AgentPlatformSkillInstallPayload platform(String ref) {
        return new AgentPlatformSkillInstallPayload(1,"psi_1","17","archive-maintainer","1.0.0",
                "a".repeat(64),"challenge_1",ref);
    }
    static AgentArchiveMaintenancePayload archive(String ref) {
        return new AgentArchiveMaintenancePayload(1,"job_1","run_1","2","appointment_1","3","4","17",
                "grant_1","execution_1","dispatch_1","psi_1","a".repeat(64),ref);
    }
    static AgentCommandDraft draft(String type, AgentCommandPayload payload) {
        boolean install=type.equals("PLATFORM_SKILL_INSTALL");
        String resource=install?"psi_1":"run_1", task=install?"psi_1":"job_1", cause=install?"challenge_1":"run_1";
        String command=AgentCommandCanonicalCodec.controlledCommandId("0","client","owner",resource,"agt_1",type);
        return new AgentCommandDraft(1,command,task,cause,"0","client","owner",task,null,"agt_1",type,
                1,3600001,payload);
    }
    @Test void platformRoundTripHasNoCommercialOrderOrFlatFrozenFields() {
        var d=draft("PLATFORM_SKILL_INSTALL",platform("/internal/agent/platform-skills/installations/psi_1/package"));
        assertEquals(d,AgentCommandCanonicalCodec.decodeBusinessBytes(AgentCommandCanonicalCodec.businessBytes(d)));
        var wire=JsonMapper.builder().build().readTree(AgentCommandCanonicalCodec.wireBytes(d,"message_1",2));
        assertEquals(2,wire.get("attempt").asInt()); assertEquals("1",wire.get("executionEpoch").asString());
        assertFalse(wire.has("orderId")); assertFalse(wire.has("productVersionId")); assertFalse(wire.has("installationId"));
        assertEquals("psi_1",wire.get("payload").get("installationId").asString());
        assertFalse(AgentCommandCanonicalCodec.isHallIntentCommand(d));
    }
    @Test void archiveRoundTripPreservesGrantEpochSeparatelyFromTransportEpoch() {
        var d=draft("ARCHIVE_MAINTENANCE_EXECUTE",archive("/internal/archive/v1/jobs/job_1/runs/run_1/context"));
        assertEquals(d,AgentCommandCanonicalCodec.decodeBusinessBytes(AgentCommandCanonicalCodec.businessBytes(d)));
        var wire=JsonMapper.builder().build().readTree(AgentCommandCanonicalCodec.wireBytes(d,"message_1"));
        assertEquals("2",wire.get("executionEpoch").asString());
        assertEquals("1",wire.get("deliveryEpoch").asString()); assertFalse(wire.has("instruction"));
    }
    @Test void controlledWireDecoderRequiresExactFrozenBytesAndRejectsBypassFields() {
        for (var draft : List.of(
                draft("PLATFORM_SKILL_INSTALL", platform(
                        "/internal/agent/platform-skills/installations/psi_1/package")),
                draft("ARCHIVE_MAINTENANCE_EXECUTE", archive(
                        "/internal/archive/v1/jobs/job_1/runs/run_1/context")))) {
            byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, "message_1", 2);
            assertEquals(draft, AgentCommandCanonicalCodec.decodeControlledWireBytes(wire));
            String json = new String(wire, StandardCharsets.UTF_8);
            for (String bad : List.of(
                    json.replace("\"messageId\":\"message_1\"",
                            "\"messageId\":\"message_1\",\"eventId\":\"event_1\""),
                    json.replace("\"ownerJiacn\":\"owner\"",
                            "\"ownerJiacn\":\"owner\",\"ownerJiacn\":\"owner\""),
                    json.replace("\"fencingToken\":\"1\"",
                            "\"fencingToken\":1"))) {
                assertThrows(IllegalArgumentException.class, () ->
                        AgentCommandCanonicalCodec.decodeControlledWireBytes(
                                bad.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    @Test void controlledRouterUsesInstallationForPlatformAndRunForArchive() {
        var install=draft("PLATFORM_SKILL_INSTALL",platform("/internal/agent/platform-skills/installations/psi_1/package"));
        assertEquals("psi_1",AgentCommandRabbitMessageDecoder.controlledResourceId(AgentCommandCanonicalCodec.wireBytes(install,"msg"),install.commandType(),install.taskId()));
        var run=draft("ARCHIVE_MAINTENANCE_EXECUTE",archive("/internal/archive/v1/jobs/job_1/runs/run_1/context"));
        assertEquals("run_1",AgentCommandRabbitMessageDecoder.controlledResourceId(AgentCommandCanonicalCodec.wireBytes(run,"msg"),run.commandType(),run.taskId()));
        assertThrows(AgentCommandRabbitDecodeException.class,()->AgentCommandRabbitMessageDecoder.controlledResourceId(
                AgentCommandCanonicalCodec.wireBytes(run,"msg"),run.commandType(),"other-job"));
    }
    @Test void scopedLegacySkillBusinessGoldenAndFlatWireRemainFrozen() {
        var payload=new AgentSkillInstallPayload("so_1","si_1","version_1","repo-test","1.0.0","522","sha256:"+"a".repeat(64),"/internal/agent/skill-installations/si_1/package");
        var d=new AgentCommandDraft(1,AgentCommandCanonicalCodec.skillInstallCommandId("0","client","owner","si_1"),"so_1","si_1",
                "0","client","owner","so_1",null,"agt_1","SKILL_INSTALL",1,3600001,payload);
        assertEquals("064991db2072a2f6515ce1a17b6e38d3466914e7d9d922fbd28fd96842c5add0",java.util.HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(AgentCommandCanonicalCodec.businessBytes(d))));
        var wire=JsonMapper.builder().build().readTree(AgentCommandCanonicalCodec.wireBytes(d,"msg"));
        assertFalse(wire.has("executionEpoch"));assertEquals("msg",wire.get("requestId").asString());
        for(String key:List.of("orderId","installationId","productVersionId","skillKey","skillVersion","packageSize","packageDigest","downloadPath"))
            assertEquals(wire.get(key),wire.get("payload").get(key));
    }
    @Test void endpointReferencesCannotChangeOriginResourceOrEncoding() {
        for (var path:List.of("https://evil/package","/internal/agent/platform-skills/installations/psi_2/package",
                "/internal/agent/platform-skills/installations/psi_1/package?token=x",
                "/internal/agent/platform-skills/installations/%70si_1/package"))
            assertThrows(IllegalArgumentException.class,()->AgentCommandCanonicalCodec.businessBytes(draft("PLATFORM_SKILL_INSTALL",platform(path))));
        for (var path:List.of("https://evil/context","/internal/archive/v1/jobs/job_2/runs/run_1/context",
                "/internal/archive/v1/jobs/job_1/runs/run_2/context"))
            assertThrows(IllegalArgumentException.class,()->AgentCommandCanonicalCodec.businessBytes(draft("ARCHIVE_MAINTENANCE_EXECUTE",archive(path))));
    }
    @Test void payloadFamiliesCannotBeSwapped() {
        assertThrows(IllegalArgumentException.class,()->AgentCommandCanonicalCodec.businessBytes(draft("PLATFORM_SKILL_INSTALL",archive("x"))));
        assertThrows(IllegalArgumentException.class,()->AgentCommandCanonicalCodec.businessBytes(draft("ARCHIVE_MAINTENANCE_EXECUTE",platform("x"))));
    }
    @Test void commandIdentityIncludesScopeTargetResourceAndFamily() {
        String id=AgentCommandCanonicalCodec.controlledCommandId("0","client","owner","psi_1","agt_1","PLATFORM_SKILL_INSTALL");
        assertNotEquals(id,AgentCommandCanonicalCodec.controlledCommandId("0","client","other","psi_1","agt_1","PLATFORM_SKILL_INSTALL"));
        assertNotEquals(id,AgentCommandCanonicalCodec.controlledCommandId("0","client","owner","psi_1","agt_2","PLATFORM_SKILL_INSTALL"));
        assertNotEquals(id,AgentCommandCanonicalCodec.controlledCommandId("0","client","owner","psi_1","agt_1","ARCHIVE_MAINTENANCE_EXECUTE"));
        assertThrows(IllegalArgumentException.class,()->AgentCommandCanonicalCodec.controlledCommandId("0","client","owner","../psi_1","agt_1","PLATFORM_SKILL_INSTALL"));
    }
    @Test void unknownDuplicateAndNumericVersionFieldsFailBeforeExecution() {
        var d=draft("PLATFORM_SKILL_INSTALL",platform("/internal/agent/platform-skills/installations/psi_1/package"));
        String json=new String(AgentCommandCanonicalCodec.businessBytes(d),StandardCharsets.UTF_8);
        for (String bad:List.of(json.replace("\"bindingVersion\":\"17\"","\"bindingVersion\":17"),
                json.replace("\"bindingVersion\":\"17\"","\"bindingVersion\":\"017\""),
                json.replace("\"bindingVersion\":\"17\"","\"bindingVersion\":\"9223372036854775808\""),
                json.replace("\"schemaVersion\":1,\"installationId\"","\"schemaVersion\":1,\"unexpected\":true,\"installationId\""),
                json.replace("\"installationId\":\"psi_1\"","\"installationId\":\"psi_1\",\"installationId\":\"psi_1\"")))
            assertThrows(IllegalArgumentException.class,()->AgentCommandCanonicalCodec.decodeBusinessBytes(bad.getBytes(StandardCharsets.UTF_8)));
    }
}
