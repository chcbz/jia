package cn.jia.chat.api;

import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import cn.jia.chat.service.PointAndStartCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskPointAndStartCapabilityControllerTest {
    private final PointAndStartCapabilityService service=mock(PointAndStartCapabilityService.class);
    private final AgentTaskPointAndStartCapabilityController controller=new AgentTaskPointAndStartCapabilityController(service);

    @Test
    void exactOwnerQueryReturnsFrozenPrivateProjection() {
        when(service.read(any(),eq("task-a"),eq("agent-a"))).thenReturn(capability());
        var response=controller.read("task-a","agent-a",request("targetAgentId=agent-a"),jwt("owner-a","client-a"));
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals("private, no-store",response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        var body=response.getBody().getData();
        assertEquals(1,body.schemaVersion());
        assertEquals(List.of("GENERATE_IMAGE"),body.requestedOperations());
        assertEquals("TASK_LINKED_REFERENCE",body.inputRefsPolicy());
        assertFalse(body.authorization().paidExecutionAuthorized());
        assertFalse(body.newStart().eligible());
        verify(service).read(argThat(scope->"0".equals(scope.tenantId())&&"client-a".equals(scope.clientId())&&"owner-a".equals(scope.ownerJiacn())),eq("task-a"),eq("agent-a"));
    }

    @Test
    void missingDuplicateUnknownAndUnsafeParametersFailBeforeSource() {
        for(MockHttpServletRequest request:List.of(request(null),request("targetAgentId=agent-a&targetAgentId=agent-b"),request("targetAgentId=agent-a&x=1"))){
            assertThrows(RuntimeException.class,()->controller.read("task-a",request.getParameter("targetAgentId"),request,jwt("owner-a","client-a")));
        }
        assertThrows(RuntimeException.class,()->controller.read("task-a","agent/a",request("targetAgentId=agent%2Fa"),jwt("owner-a","client-a")));
        verifyNoInteractions(service);
    }

    @Test
    void authentication404And503AreNonleakingAndNeverCallProvider() {
        assertEquals(HttpStatus.UNAUTHORIZED,controller.authentication().getStatusCode());
        var missing=new AgentTaskPointAndStartPolicyService.Failure(AgentTaskPointAndStartPolicyService.Failure.Reason.NOT_FOUND);
        var notFound=controller.policyFailure(missing);assertEquals(HttpStatus.NOT_FOUND,notFound.getStatusCode());
        assertFalse(notFound.getBody().getMsg().contains("owner-a"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,controller.sourceUnavailable(new PointAndStartCapabilityService.SourceUnavailable()).getStatusCode());
    }

    private static MockHttpServletRequest request(String query){
        MockHttpServletRequest value=new MockHttpServletRequest("GET","/agent/tasks/task-a/point-and-start-capability");
        if(query!=null)value.setQueryString(query);
        if(query!=null) for(String pair:query.split("&")){String[] kv=pair.split("=",2);value.addParameter(kv[0],kv.length==1?"":java.net.URLDecoder.decode(kv[1],java.nio.charset.StandardCharsets.UTF_8));}
        return value;
    }
    private static JwtAuthenticationToken jwt(String owner,String client){
        Jwt token=Jwt.withTokenValue("token").header("alg","none").claim("jiacn",owner).claim("client_id",client).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        JwtAuthenticationToken value=new JwtAuthenticationToken(token);value.setAuthenticated(true);return value;
    }
    private static PointAndStartCapabilityService.Capability capability(){
        return new PointAndStartCapabilityService.Capability(1,"task-a","agent-a","ORDINARY_SINGLE_AGENT_ASSIGN_AND_START",
                new PointAndStartCapabilityService.ServerLane("READY",List.of()),
                new PointAndStartCapabilityService.NativeExecution("READY","PERSONAL_WORKSPACE_CONVERSATION_HTTP_V1",1,List.of("GENERATE_IMAGE")),
                new PointAndStartCapabilityService.Authorization("UNAVAILABLE",false),
                new PointAndStartCapabilityService.NewStart(false,List.of("COST_AUTHORIZATION_UNAVAILABLE")),
                List.of("GENERATE_IMAGE"),"GENERATE_IMAGE","TASK_LINKED_REFERENCE",
                new PointAndStartCapabilityService.OriginalIntentRecovery(false,"RECOVERY_REQUIRED","EXPLICIT_USER_EXACT_ORIGINAL_KEY_AND_BODY_ONLY"));
    }
}
