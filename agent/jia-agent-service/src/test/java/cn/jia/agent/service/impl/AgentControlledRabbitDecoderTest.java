package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AgentControlledRabbitDecoderTest {
    static final AgentRabbitTopologyManifest MANIFEST=AgentRabbitTopologyManifest.canonical();
    final AgentCommandRabbitMessageDecoder decoder=new AgentCommandRabbitMessageDecoder(MANIFEST);
    static Message rabbit(byte[] wire,String command,String task) {
        var props=new MessageProperties();
        props.setContentType(AgentCommandAmqpContract.CONTENT_TYPE);
        props.setContentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING);
        props.setType(AgentCommandAmqpContract.MESSAGE_TYPE);
        props.setMessageId("msg");props.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setConsumerQueue(AgentRabbitTopologyManifest.DISPATCH_QUEUE);
        props.setReceivedExchange(AgentRabbitTopologyManifest.MAIN_EXCHANGE);
        props.setReceivedRoutingKey(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY);
        props.setHeader(AgentCommandAmqpContract.HEADER_WIRE_VERSION,1);
        props.setHeader(AgentCommandAmqpContract.HEADER_EVENT_ID,"event");
        props.setHeader(AgentCommandAmqpContract.HEADER_DELIVERY_ID,1L);
        props.setHeader(AgentCommandAmqpContract.HEADER_COMMAND_ID,command);
        props.setHeader(AgentCommandAmqpContract.HEADER_TENANT_ID,"0");
        props.setHeader(AgentCommandAmqpContract.HEADER_CLIENT_ID,"client");
        props.setHeader(AgentCommandAmqpContract.HEADER_TASK_ID,task);
        props.setHeader(AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID,"agt_1");
        props.setHeader(AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT,1);
        props.setHeader(AgentCommandAmqpContract.HEADER_EXPIRES_AT,3600001L);
        props.setHeader(AgentCommandAmqpContract.HEADER_WIRE_SHA256,AgentCommandAmqpContract.hex(AgentCommandAmqpContract.sha256(wire)));
        props.setHeader(AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256,MANIFEST.sha256());
        props.setHeader(AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY,0);
        return new Message(wire,props);
    }
    @Test void decodeCarriesTheValidatedRunNotTheJobToConsumer() {
        var draft=AgentControlledCommandCodecTest.draft("ARCHIVE_MAINTENANCE_EXECUTE",
                AgentControlledCommandCodecTest.archive("/internal/archive/v1/jobs/job_1/runs/run_1/context"));
        byte[] wire=AgentCommandCanonicalCodec.wireBytes(draft,"msg");
        var decoded=decoder.decode(rabbit(wire,draft.commandId(),draft.taskId()));
        assertEquals("run_1",decoded.controlledResourceId());assertEquals("job_1",decoded.taskId());
        assertArrayEquals(wire,decoded.rawWireBytes());
    }
    @Test void badRunOrJobIsDroppedAtDecodeRatherThanParkedAfterInboxClaim() {
        var draft=AgentControlledCommandCodecTest.draft("ARCHIVE_MAINTENANCE_EXECUTE",
                AgentControlledCommandCodecTest.archive("/internal/archive/v1/jobs/job_1/runs/run_1/context"));
        String wire=new String(AgentCommandCanonicalCodec.wireBytes(draft,"msg"),StandardCharsets.UTF_8);
        for(String bad:java.util.List.of(wire.replace("\"causationId\":\"run_1\"","\"causationId\":\"run_other\""),
                wire.replace("\"jobId\":\"job_1\"","\"jobId\":\"job_other\""))) {
            var failure=assertThrows(AgentCommandRabbitDecodeException.class,
                    ()->decoder.decode(rabbit(bad.getBytes(StandardCharsets.UTF_8),draft.commandId(),draft.taskId())));
            assertEquals("CONTROLLED_RESOURCE_MISMATCH",failure.reasonCode());
        }
    }
    @Test void platformDecoderPreservesTheInstallationResource() {
        var draft=AgentControlledCommandCodecTest.draft("PLATFORM_SKILL_INSTALL",
                AgentControlledCommandCodecTest.platform("/internal/agent/platform-skills/installations/psi_1/package"));
        assertEquals("psi_1",decoder.decode(rabbit(AgentCommandCanonicalCodec.wireBytes(draft,"msg"),draft.commandId(),draft.taskId())).controlledResourceId());
    }
}
