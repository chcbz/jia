package cn.jia.chat.service;

import cn.jia.agent.entity.AgentRuntimeDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSenderIdentityResolverTest {
    @Test
    void resolvesRuntimeThenPersonaThenAuthenticatedId() {
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId("agent-1");
        runtime.setName("吴用");
        runtime.setPersonaName("智多星");
        assertEquals("吴用", AgentSenderIdentityResolver.resolve(runtime, "agent-1"));

        runtime.setName("bad\nname");
        assertEquals("智多星", AgentSenderIdentityResolver.resolve(runtime, "agent-1"));

        runtime.setPersonaName(" ");
        assertEquals("agent-1", AgentSenderIdentityResolver.resolve(runtime, "agent-1"));

        runtime.setAgentId("agent-2");
        runtime.setName("伪造名称");
        assertEquals("agent-1", AgentSenderIdentityResolver.resolve(runtime, "agent-1"));
    }
}
