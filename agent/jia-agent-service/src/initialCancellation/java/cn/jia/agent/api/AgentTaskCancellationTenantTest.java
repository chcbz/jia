package cn.jia.agent.api;

import cn.jia.agent.service.AgentTaskCancellationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskCancellationTenantTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "tenant-blue"})
    void admittedOwnerPartitionIsNotRewritten(String tenant) {
        var service = mock(AgentTaskCancellationService.class);
        when(service.cancel(tenant, "client", "owner", "actor", "417", 1L))
                .thenReturn(new AgentTaskCancellationService.Receipt("417", "cancelled", 2L));
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "none").claim("sub", "actor")
                .claim("jiacn", "owner").claim("client_id", "client").claim("tenant_id", tenant).build();
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(("{\"protocolVersion\":1,\"requestId\":\"gray-cancel-1\","
                + "\"data\":{\"expectedTaskVersion\":\"1\"}}").getBytes(StandardCharsets.UTF_8));
        var response = new AgentTaskCancellationController(service).cancel("417", request,
                new JwtAuthenticationToken(jwt, List.of(), "actor"));
        assertEquals(200, response.getStatusCode().value());
        verify(service).cancel(tenant, "client", "owner", "actor", "417", 1L);
        verifyNoMoreInteractions(service);
    }
}
