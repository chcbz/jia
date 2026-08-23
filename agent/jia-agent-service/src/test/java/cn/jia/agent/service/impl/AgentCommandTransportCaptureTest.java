package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.service.AgentCommandTransportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandTransportCaptureTest {
    @Test
    void flagsOffDoesNotResolveWriterOrTouchTransport() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> provider = mock(ObjectProvider.class);
        AgentCommandTransportCapture capture = new AgentCommandTransportCapture(gate(false), provider);

        capture.captureTaskInvites(null, null, null, 0);

        verify(provider, never()).getIfAvailable();
    }

    @Test
    void enabledWithoutWriterFailsClosed() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        AgentCommandTransportCapture capture = new AgentCommandTransportCapture(gate(true), provider);

        assertThrows(IllegalStateException.class,
                () -> capture.captureTaskInvites(null, null, null, 0));
    }

    private AgentRabbitSafetyGate gate(boolean enabled) {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(enabled),
                new AgentRabbitSafetyProperties.RabbitTopology(false),
                new AgentRabbitSafetyProperties.RabbitPublish(false),
                new AgentRabbitSafetyProperties.RabbitConsume(false),
                new AgentRabbitSafetyProperties.RabbitDispatch(false), null));
    }
}
