package cn.jia.agent.api;

import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentWorkItemReassignmentService;
import cn.jia.agent.service.AgentWorkItemResultCommitService;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentWorkItemRuntimeResultControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode fixture;
    private AgentWorkItemResultCommitService results;
    private AgentWorkItemReassignmentService reassignments;
    private AgentRuntimeAuthenticationService authentication;
    private AgentRuntimeAuthentication principal;
    private MockMvc mvc;
    private String path;
    @BeforeEach void setUp() throws Exception {
        try (var input = getClass().getResourceAsStream("/ur02/e05-runtime-lease-result.redacted.json")) {
            assertNotNull(input); fixture = mapper.readTree(input);
        }
        results = mock(AgentWorkItemResultCommitService.class);
        reassignments = mock(AgentWorkItemReassignmentService.class);
        authentication = mock(AgentRuntimeAuthenticationService.class);
        var scope = new AgentRuntimeAuthentication.Scope("0", "client-a", "owner-a",
                fixture.path("postResult").path("request").path("producerAgentId").asText(), "boot-fixture");
        principal = authenticationFixture(scope);
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(scope,
                "rti_0123456789abcdef0123456789abcdef", "host-fixture", 7, "a".repeat(64), 1, 0));
        mvc = MockMvcBuilders.standaloneSetup(new AgentWorkItemRuntimeResultController(results, reassignments, authentication))
                .setControllerAdvice(new cn.jia.core.security.SensitiveResponseBodyAdvice(new cn.jia.core.security.SensitiveResponseProperties()))
                .build();
        path = fixture.path("postResult").path("path").asText();
    }
    /** Standalone test fixture only; production native admission still belongs to its filter/service. */
    private static AgentRuntimeAuthentication authenticationFixture(AgentRuntimeAuthentication.Scope scope) {
        try {
            var constructor = AgentRuntimeAuthentication.class.getDeclaredConstructor(AgentRuntimeAuthentication.Scope.class);
            assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()), "Do not open production authentication construction for fixtures");
            return org.springframework.beans.BeanUtils.instantiateClass(constructor, scope);
        } catch (NoSuchMethodException changedSignature) {
            throw new AssertionError("Runtime authentication fixture constructor changed", changedSignature);
        }
    }
    private void fence() {
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(authentication).withNativeFence(eq(principal), notNull());
    }
    private AgentWorkItemResultCommitViewDTO result() { return mapper.treeToValue(fixture.path("postResult").path("response"), AgentWorkItemResultCommitViewDTO.class); }
    private String body() { return mapper.writeValueAsString(fixture.path("postResult").path("request")); }

    @Test void exactDtoSerializationPreservesAllTypesFieldsAndNullableArtifactMaterial() throws Exception {
        var request = mapper.treeToValue(fixture.path("postResult").path("request"), AgentWorkItemResultCommitDTO.class);
        assertEquals(fixture.path("postResult").path("request"), mapper.readTree(mapper.writeValueAsString(request)));
        assertEquals(fixture.path("postResult").path("response"), mapper.readTree(mapper.writeValueAsString(result())));
        var lease = mapper.treeToValue(fixture.path("getLease").path("response"), AgentWorkItemReassignmentLeaseDTO.class);
        assertEquals(fixture.path("getLease").path("response"), mapper.readTree(mapper.writeValueAsString(lease)));
        assertEquals(Long.class, AgentWorkItemResultCommitDTO.class.getDeclaredField("expectedWorkItemVersion").getType()); // version, not contextVersion string
        assertFalse(fixture.path("postResult").path("request").has("commandId"));
        assertFalse(fixture.path("postResult").path("response").has("data"));
        assertEquals(fixture.path("postResult").path("response"), fixture.path("getResult").path("response"));
    }

    @Test void realControllerSerializationIsRawAndStoragePreparationIsBetweenShortFences() throws Exception {
        fence();
        var prepared = mock(AgentWorkItemResultCommitService.PreparedRuntimeResult.class);
        when(results.prepareRuntimeResult(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(prepared);
        when(results.commitPreparedRuntimeResult(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), eq(prepared))).thenReturn(result());
        var response = mvc.perform(post(path).principal(principal).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store")).andReturn();
        assertEquals(fixture.path("postResult").path("response"), mapper.readTree(response.getResponse().getContentAsString()));
        var order = inOrder(authentication, results);
        order.verify(authentication).withNativeFence(eq(principal), notNull());
        order.verify(results).preflightRuntimeResult(eq("0"), eq("client-a"), eq("owner-a"), eq("task-1"), eq("work-1"), eq(principal.getPrincipal().agentId()), anyString(), anyString(), any());
        order.verify(results).prepareRuntimeResult(eq("0"), eq("client-a"), eq("owner-a"), eq("task-1"), eq("work-1"), eq(principal.getPrincipal().agentId()), anyString(), anyString(), any());
        order.verify(authentication).withNativeFence(eq(principal), notNull());
        order.verify(results).commitPreparedRuntimeResult(eq("0"), eq("client-a"), eq("owner-a"), eq("task-1"), eq("work-1"), eq(principal.getPrincipal().agentId()), anyString(), anyString(), eq(prepared));
    }
    @Test void exactPriorProofAvoidsStorageAndSecondBusinessCommit() throws Exception {
        fence(); when(results.preflightRuntimeResult(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(result());
        var response = mvc.perform(post(path).principal(principal).contentType("application/json").content(body())).andExpect(status().isOk()).andReturn();
        assertEquals(fixture.path("postResult").path("response"), mapper.readTree(response.getResponse().getContentAsString()));
        verify(results, never()).prepareRuntimeResult(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(results, never()).commitPreparedRuntimeResult(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(authentication).withNativeFence(eq(principal), notNull());
    }
    @Test void resultAndLeaseReadbackUseCurrentProofOriginalKeysAndRawDto() throws Exception {
        fence();
        when(results.readRuntimeResult(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(result());
        var lease = mapper.treeToValue(fixture.path("getLease").path("response"), AgentWorkItemReassignmentLeaseDTO.class);
        when(reassignments.readCurrentRuntimeLease(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(lease);
        var response = mvc.perform(get(path).principal(principal)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store")).andReturn();
        assertEquals(fixture.path("getResult").path("response"), mapper.readTree(response.getResponse().getContentAsString()));
        var leased = mvc.perform(get(fixture.path("getLease").path("path").asText()).principal(principal)).andExpect(status().isOk()).andReturn();
        assertEquals(fixture.path("getLease").path("response"), mapper.readTree(leased.getResponse().getContentAsString()));
        verify(reassignments).readCurrentRuntimeLease(eq("0"), eq("client-a"), eq("owner-a"), eq(principal.getPrincipal().agentId()), eq("task-1"), eq("work-1"),
                eq(path.split("/")[8]), eq(path.split("/")[10]));
        verify(reassignments, never()).readLease(any(), any(), any(), any(), any(), any(), any(), any());
        verify(reassignments, never()).startLease(any(), any(), any(), any(), any(), any(), any(), any());
        verify(reassignments, never()).heartbeatLease(any(), any(), any(), any(), any(), any(), any(), any());
    }
    @Test void spoofedProducerPathWorkItemAndAnyCallerQueryAreForbiddenBeforeFence() throws Exception {
        for (String key : List.of("workItemId", "producerAgentId")) {
            var altered = (tools.jackson.databind.node.ObjectNode) fixture.path("postResult").path("request").deepCopy(); altered.put(key, "foreign");
            mvc.perform(post(path).principal(principal).contentType("application/json").content(mapper.writeValueAsString(altered)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_FORBIDDEN"));
        }
        mvc.perform(get(path).principal(principal).queryParam("actorAgentId", principal.getPrincipal().agentId())).andExpect(status().isForbidden());
        verifyNoInteractions(results, reassignments, authentication);
    }
    @Test void missingRuntimeProofAndUserPrincipalCannotFallbackToJwtOrApiKey() throws Exception {
        for (var user : List.of(authenticationFixture(principal.getPrincipal()),
                UsernamePasswordAuthenticationToken.authenticated("owner-a", "key", List.of()))) {
            mvc.perform(post(path).principal(user).contentType("application/json").content(body())).andExpect(status().isForbidden());
        }
        mvc.perform(get(path)).andExpect(status().isForbidden());
        verifyNoInteractions(results, reassignments, authentication);
    }
    @Test void strictJsonRejectsDuplicateUnknownTrailingAndStringOrFractionalVersions() throws Exception {
        for (String value : List.of(body().replace("\"workItemId\":", "\"unknown\":true,\"workItemId\":"),
                body().replaceFirst("\"expectedWorkItemVersion\":6", "\"expectedWorkItemVersion\":6,\"expectedWorkItemVersion\":6"),
                body() + " {}", body().replace("\"expectedWorkItemVersion\":6", "\"expectedWorkItemVersion\":\"6\""),
                body().replace("\"expectedWorkItemVersion\":6", "\"expectedWorkItemVersion\":6.0"))) {
            mvc.perform(post(path).principal(principal).contentType("application/json").content(value)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).principal(principal).content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(results, reassignments, authentication);
    }
    @Test void domainErrorsUseFrozenSafeCodesAndNeverReturnFalseBusinessSuccess() throws Exception {
        fence();
        for (var reason : AgentWorkItemResultCommitService.RuntimeFailureReason.values()) {
            doThrow(new AgentWorkItemResultCommitService.RuntimeFailure(reason)).when(results).readRuntimeResult(any(), any(), any(), any(), any(), any(), any(), any());
            var expected = reason == AgentWorkItemResultCommitService.RuntimeFailureReason.NOT_FOUND ? 404 : 409;
            mvc.perform(get(path).principal(principal)).andExpect(status().is(expected))
                    .andExpect(jsonPath("$.code").value(expected == 404 ? "WORK_ITEM_RESULT_NOT_FOUND" : "WORK_ITEM_RESULT_RECOVERY_REQUIRED"));
        }
        doThrow(new AgentTaskCollaborationException(AgentTaskCollaborationException.Reason.VERSION_CONFLICT, "private-token"))
                .when(results).readRuntimeResult(any(), any(), any(), any(), any(), any(), any(), any());
        mvc.perform(get(path).principal(principal)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_STALE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-token"))));
        doThrow(new IllegalStateException("private-token")).when(results).readRuntimeResult(any(), any(), any(), any(), any(), any(), any(), any());
        mvc.perform(get(path).principal(principal)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WORK_ITEM_RESULT_UNAVAILABLE"));
    }
    @Test void nativeLaneAllowsOnlyFrozenMethodsPathsWithoutUserFallback() {
        for (String route : List.of(path, fixture.path("getLease").path("path").asText())) {
            var req = new org.springframework.mock.web.MockHttpServletRequest("GET", route);
            assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(req));
            assertTrue(AgentRuntimeAuthenticationFilter.allowed(req));
            req.setMethod("DELETE"); assertFalse(AgentRuntimeAuthenticationFilter.allowed(req));
        }
        var req = new org.springframework.mock.web.MockHttpServletRequest("POST", path); assertTrue(AgentRuntimeAuthenticationFilter.allowed(req));
        req.setRequestURI(path.replace("/result-commit", "/lease")); assertFalse(AgentRuntimeAuthenticationFilter.allowed(req));
        req.setRequestURI(path + "/extra"); assertFalse(AgentRuntimeAuthenticationFilter.allowed(req));
    }
}
