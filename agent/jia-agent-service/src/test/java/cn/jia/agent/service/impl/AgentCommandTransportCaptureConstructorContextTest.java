package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.service.AgentCommandTransportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Registers the component class itself so Spring cannot silently select its legacy constructor. */
class AgentCommandTransportCaptureConstructorContextTest {
    @Test
    void flagsOffConsultsInjectedGateWithoutTouchingWriter() {
        AgentRabbitSafetyGate gate = mock(AgentRabbitSafetyGate.class);
        AgentCommandTransportWriter writer = mock(AgentCommandTransportWriter.class);
        when(gate.commandOutboxEnabled()).thenReturn(false);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("rabbitSafetyGate", gate);
            context.getBeanFactory().registerSingleton("commandTransportWriter", writer);
            context.register(AgentCommandTransportCapture.class);
            context.refresh();

            assertFalse(context.getBean(AgentCommandTransportCapture.class)
                    .captureTaskInvites(null, null, null, 0));
            verify(gate).commandOutboxEnabled();
            verifyNoInteractions(writer);
        }
    }

    @Test
    void enabledWithoutWriterFailsClosedInsteadOfSilentlyDisablingCapture() {
        AgentRabbitSafetyGate gate = mock(AgentRabbitSafetyGate.class);
        when(gate.commandOutboxEnabled()).thenReturn(true);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("rabbitSafetyGate", gate);
            context.register(AgentCommandTransportCapture.class);
            context.refresh();

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> context.getBean(AgentCommandTransportCapture.class)
                            .captureTaskInvites(null, null, null, 0));
            assertEquals("agent.command-outbox.enabled=true but AgentCommandTransportWriter is missing",
                    failure.getMessage());
            verify(gate).commandOutboxEnabled();
        }
    }

    @Test
    void missingGateFailsContextRatherThanChoosingPrivateNoArgConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AgentCommandTransportCapture.class);
            assertThrows(UnsatisfiedDependencyException.class, context::refresh);
        }
    }
}
