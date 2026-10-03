package cn.jia.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeControlledImageV3SecurityIntegrationTest {
    @Test void onlyExactV3InboxInputsAndProviderStartRoutesEnterRuntimeLane() {
        for(String path:List.of(
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/provider-start-controlled-image-v3"))
            assertTrue(AgentRuntimeAuthenticationFilter.allowed(request(
                    path.endsWith("commands")?"GET":"POST",path)),path);

        for(String path:List.of(
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands/",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v4",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/provider-start-controlled-image-v3/extra",
                "/internal/agent/tasks/task%2Fother/runs/run-1/conversation/inputs-v3",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/../provider-start-controlled-image-v3"))
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("POST",path)),path);
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("GET",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3")));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("POST",
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands")));
    }

    @Test void stagedResultRecoveryIsOneExactNativePostNotAnUploadOrStartCapability() {
        String path="/internal/agent/tasks/task-1/runs/run-1/conversation/result-commits/pwe_m_"+"a".repeat(64);
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(request("POST",path)));
        for(String method:List.of("GET","PUT","DELETE"))
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(request(method,path)));
        for(String suffix:List.of("/content","/start","/lease","/extra","/"))
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("POST",path+suffix)));
    }

    @Test void realNativeHttpChainPreservesExactWorkspaceAndAssetSourceFields() throws Exception {
        var executions=org.mockito.Mockito.mock(cn.jia.agent.service.PersonalWorkspaceExecutionService.class);
        var authentication=org.mockito.Mockito.mock(AgentRuntimeAuthenticationService.class);
        var principal=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("0","client","owner","agent","runtime"));
        org.mockito.Mockito.when(authentication.authenticate("agent","runtime","a".repeat(32))).thenReturn(principal);
        var aware=new org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter();
        aware.afterPropertiesSet();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
          new cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController(executions))
          .setControllerAdvice(new cn.jia.core.security.SensitiveResponseBodyAdvice(new cn.jia.core.security.SensitiveResponseProperties()))
          .addFilters(new AgentRuntimeAuthenticationFilter(authentication),aware).build();
        var sources=List.of(
          new cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeSource("TASK_LINKED_WORKSPACE_VERSION","file","2","REFERENCE",null,null,null,null,null,null,null,null,null),
          new cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeSource("CURRENT_CONVERSATION_ASSET",null,null,null,"conversation","1","asset","2","request","step","execution0","run0","output_1"));
        var fieldSets=List.of(java.util.Set.of("kind","fileId","version","purpose"),
          java.util.Set.of("kind","conversationId","conversationGeneration","assetId","assetRevision","producerRequestId","producerStepId","producerExecutionId","producerRunId","producerOutputId"));
        for(int i=0;i<sources.size();i++) {
          var snapshot=new cn.jia.agent.service.PersonalWorkspaceExecutionService.ConversationInputSnapshotV3(
            3,"execution",1,i==0?"GENERATE_IMAGE":"EDIT_IMAGE","b".repeat(64),false,
            List.of(new cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeInputV3("input_1",sources.get(i),"image/png","4","c".repeat(64))));
          org.mockito.Mockito.when(executions.conversationInputsV3(
            org.mockito.ArgumentMatchers.eq(new cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime")),
            org.mockito.ArgumentMatchers.eq("task-1"),org.mockito.ArgumentMatchers.eq("run-1"),org.mockito.ArgumentMatchers.any())).thenReturn(snapshot);
          var response=mvc.perform(nativeInputsRequest()).andReturn().getResponse();
          assertEquals(200,response.getStatus());
          assertEquals("private, no-store",response.getHeader("Cache-Control"));
          var root=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getContentAsString());
          assertEquals(java.util.Set.of("schemaVersion","executionId","leaseVersion","operation","inputSnapshotDigest","noReferencedMaterials","inputs"),new java.util.HashSet<>(root.propertyNames()));
          assertEquals(fieldSets.get(i),new java.util.HashSet<>(root.get("inputs").get(0).get("source").propertyNames()));
          assertEquals("4",root.get("inputs").get(0).get("byteLength").textValue());
          assertEquals(1,root.get("leaseVersion").longValue());
        }
        org.mockito.Mockito.clearInvocations(executions);
        assertEquals(403,mvc.perform(nativeInputsRequest().header("Origin","https://browser.invalid")).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
          "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3").contentType("application/json")
          .content("{}")).andReturn().getResponse().getStatus());
        org.mockito.Mockito.verifyNoInteractions(executions);
        org.mockito.Mockito.when(executions.conversationInputsV3(org.mockito.ArgumentMatchers.any(),
          org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
          .thenThrow(new cn.jia.agent.service.PersonalWorkspaceExecutionService.Failure(
            cn.jia.agent.service.PersonalWorkspaceExecutionService.Reason.NOT_FOUND));
        var denied=mvc.perform(nativeInputsRequest()).andReturn().getResponse();
        assertEquals(404,denied.getStatus());assertFalse(denied.getContentAsString().contains("fileId"));
    }

    @Test void exactWireExemptionDoesNotDisableOrdinaryResponseSanitization() throws Exception {
        var type=cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.class;
        var marked=java.util.Arrays.stream(type.getDeclaredMethods())
          .filter(m->m.isAnnotationPresent(cn.jia.core.security.AllowSensitiveOutput.class)).toList();
        assertEquals(List.of("inputsV3"),marked.stream().map(java.lang.reflect.Method::getName).toList());
        var method=type.getMethod("inputs",String.class,String.class,
          cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.FenceRequest.class,
          jakarta.servlet.http.HttpServletRequest.class,org.springframework.security.core.Authentication.class);
        var advice=new cn.jia.core.security.SensitiveResponseBodyAdvice(new cn.jia.core.security.SensitiveResponseProperties());
        var result=(java.util.Map<?,?>)advice.beforeBodyWrite(java.util.Map.of("password","secret","fileId","file"),
          new org.springframework.core.MethodParameter(method,-1),org.springframework.http.MediaType.APPLICATION_JSON,null,null,null);
        assertNull(result.get("password"));assertEquals("file",result.get("fileId"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder nativeInputsRequest() {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
          "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3")
          .header("Authorization","AgentRuntime "+"a".repeat(32)).header("X-Agent-Id","agent")
          .header("X-Agent-Runtime-Id","runtime").contentType("application/json")
          .content("{\"version\":1,\"token\":\"11111111-1111-1111-1111-111111111111\"}");
    }

    private static MockHttpServletRequest request(String method,String path) {
        return new MockHttpServletRequest(method,path);
    }
}
