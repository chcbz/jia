package cn.jia.agent.skill;
import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SkillRuntimeSecurityContractTest {
    MockHttpServletRequest request(String method,String path){return new MockHttpServletRequest(method,path);}
    @Test void materialsReadIsClosedNativeLaneWithExactPathAndMethod() {
        String path="/internal/agent/chat/requests/request/turns/turn/materials/inputs/input/content";
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(request("GET",path)));assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(request("GET",path)));
        for(String method:List.of("POST","PUT","DELETE"))assertFalse(AgentRuntimeAuthenticationFilter.allowed(request(method,path)));
        for(String variant:List.of(path+";x=y",path+"/extra",path.replace("/input/","/%2f/"),path.replace("/turn/","/../")))assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("GET",variant)));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("GET","/agent/skill-maintenance/product-versions")));
    }
    @Test void historicalProviderFeeAndImageSubmissionWritesAreNoLongerHttpRoutes() {
        for(Class<?> type:List.of(AgentTaskProviderCostConsentController.class,ControlledImagePointAndStartController.class))assertTrue(Arrays.stream(type.getDeclaredMethods()).noneMatch(m->m.getAnnotation(PostMapping.class)!=null));
        for(var m:PersonalWorkspaceConversationRuntimeController.class.getDeclaredMethods()){var annotation=m.getAnnotation(PostMapping.class);if(annotation!=null)for(String path:annotation.value())assertFalse(path.contains("provider-start")||path.contains("inputs-v3"));}
    }
}
