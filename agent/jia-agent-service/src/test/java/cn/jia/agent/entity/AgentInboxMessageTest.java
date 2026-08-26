package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentInboxMessageTest {
    @Test
    void rawWireBytesAreDefensivelyCopiedOnIngressAndEgress() {
        byte[] source = {1, 2, 3};
        AgentInboxMessage message = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1, source);

        source[0] = 9;
        byte[] firstRead = message.rawWireBytes();
        firstRead[1] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, message.rawWireBytes());
    }

    @Test
    void dispositionsExposeOnlyBoundedAsciiReasonCodes() {
        assertEquals(AgentInboxDisposition.Type.SENT, AgentInboxDisposition.sent().type());
        assertThrows(IllegalArgumentException.class,
                () -> new AgentInboxDisposition(
                        AgentInboxDisposition.Type.FAILED, null, "payload={secret}"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentInboxDisposition(
                        AgentInboxDisposition.Type.RETRY, null, "TRANSIENT"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentInboxDisposition(
                        AgentInboxDisposition.Type.SENT, 123L, null));
    }
}
