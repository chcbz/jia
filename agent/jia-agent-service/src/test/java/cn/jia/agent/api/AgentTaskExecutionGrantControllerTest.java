package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantDTO;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskExecutionGrantControllerTest {
    @Test
    void legacyAssignWithoutV2FieldsPreservesOriginalServicePath() {
        AgentService legacy=mock(AgentService.class); AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        AgentController controller=new AgentController(legacy,mock(AbilityEvaluationService.class));
        controller.setTaskExecutionGrants(grants); AgentTaskAssignDTO request=new AgentTaskAssignDTO(); request.setAgentId("agent-1");
        controller.assignTask("task-1",request,null,null);
        verify(legacy).assignTask("task-1",request); verifyNoInteractions(grants);
    }

    @Test
    void v2RequiresJwtBeforeGrantService() {
        AgentService legacy=mock(AgentService.class); AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        AgentController controller=new AgentController(legacy,mock(AbilityEvaluationService.class)); controller.setTaskExecutionGrants(grants);
        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> controller.assignTask("task-1",request(),"key-1",null));
        assertEquals(AgentTaskExecutionGrantException.Reason.UNAUTHENTICATED,failure.reason());
        verifyNoInteractions(legacy,grants);
    }

    @Test
    void v2ForwardsOnlyJwtDerivedScopeAndReturnsNoStore() {
        AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
        AgentController controller=new AgentController(mock(AgentService.class),mock(AbilityEvaluationService.class));
        controller.setTaskExecutionGrants(grants); AgentTaskAssignDTO request=request();
        when(grants.assignAndGrant(any(),eq("task-1"),eq("key-1"),same(request)))
                .thenReturn(new AgentTaskExecutionGrantDTO().setGrantId("grant-1"));
        Object raw=controller.assignTask("task-1",request,"key-1",jwt("owner","client"));
        assertInstanceOf(ResponseEntity.class,raw);
        verify(grants).assignAndGrant(new AgentTaskExecutionGrantService.Scope("0","client","owner"),
                "task-1","key-1",request);
    }

    private static AgentTaskAssignDTO request(){AgentTaskAssignDTO r=new AgentTaskAssignDTO();r.setAgentId("agent-1");r.setWorkflowVersion(2);r.setBusinessAction("assign_and_start");r.setExpectedTaskVersion(0L);r.setRequirementRevision(1L);r.setRequestedOperations(List.of("GENERATE_IMAGE"));return r;}
    private static JwtAuthenticationToken jwt(String owner,String client){Jwt jwt=Jwt.withTokenValue("token").header("alg","none").claim("jiacn",owner).claim("client_id",client).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();return new JwtAuthenticationToken(jwt,List.of());}
}
