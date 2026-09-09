package cn.jia.agent.output.service;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAuthorizer;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class OutputSourceAuthorizerRegistryTest extends BaseMockTest {
    @Mock OutputSourceAuthorizer first;
    @Mock OutputSourceAuthorizer second;

    @Test
    void rejectsDuplicateAndBlankHandlersAtConstruction() {
        when(first.sourceType()).thenReturn("TASK");
        when(second.sourceType()).thenReturn("TASK");
        assertThrows(IllegalStateException.class,
                () -> new OutputSourceAuthorizerRegistry(List.of(first, second)));

        when(first.sourceType()).thenReturn(" ");
        assertThrows(IllegalStateException.class,
                () -> new OutputSourceAuthorizerRegistry(List.of(first)));
    }

    @Test
    void rejectsUnknownOrMismatchedAuthorizationAndReturnsExactProjection() {
        when(first.sourceType()).thenReturn("TASK");
        OutputSourceAuthorizerRegistry registry =
                new OutputSourceAuthorizerRegistry(List.of(first));

        assertThrows(OutputAuthorizationException.class,
                () -> registry.lockAndAuthorize("owner", "client", "CONVERSATION",
                        "1", "agent"));

        when(first.lockAndAuthorize("owner", "client", "task", "agent"))
                .thenReturn(new OutputSourceAuthorization(
                        "Owner", "client", "TASK", "task", "owner", "agent", true));
        assertThrows(OutputAuthorizationException.class,
                () -> registry.lockAndAuthorize(
                        "owner", "client", "TASK", "task", "agent"));

        OutputSourceAuthorization exact = new OutputSourceAuthorization(
                "owner", "client", "TASK", "task", "owner", "agent", true);
        when(first.lockAndAuthorize("owner", "client", "task", "agent"))
                .thenReturn(exact);
        assertEquals(exact, registry.lockAndAuthorize(
                "owner", "client", "TASK", "task", "agent"));
    }
}
