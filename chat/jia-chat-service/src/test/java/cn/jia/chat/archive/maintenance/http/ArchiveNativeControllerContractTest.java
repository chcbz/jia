package cn.jia.chat.archive.maintenance.http;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockInput;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftUpdateRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeContextDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeFailureRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeStartRequest;
import cn.jia.chat.archive.maintenance.dto.ArchivePublishRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRecoveryContextDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveSkillRef;
import cn.jia.chat.archive.maintenance.dto.ArchiveResumeRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveReassignRequest;
import cn.jia.chat.archive.maintenance.model.ArchiveRuntimeScope;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveNativeControllerContractTest {
    @Test
    void nativeSurfaceHasOnlyExactPublishAndNoAppointmentRouteAndBodiesCarryNoIdentity() {
        Set<String> methods = Arrays.stream(ArchiveNativeController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertTrue(methods.contains("publish"));
        assertFalse(methods.contains("createAppointment"));
        assertEquals(Set.of("jobId", "runId", "collectionId", "workId", "operation",
                        "expectedWorkRevision", "expectedActiveEditionId", "appointmentId",
                        "appointmentRevision", "agentId", "bindingVersion", "permissionProfile",
                        "publicationMode", "state", "waitReason", "requiredSkill", "sourceId",
                        "sourceSha256", "sourceSummary", "rightsBasis", "draftId", "draftRevision"),
                fields(ArchiveRuntimeContextDTO.class));
        assertEquals(Set.of("blocks", "excludedSourceRanges"), Arrays.stream(ArchiveDraftUpdateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet()));
        Set<String> blockFields = Arrays.stream(ArchiveDraftBlockInput.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
        assertFalse(blockFields.contains("tenantId"));
        assertFalse(blockFields.contains("clientId"));
        assertFalse(blockFields.contains("ownerJiacn"));
        assertFalse(blockFields.contains("permissions"));
        assertEquals(Set.of("reason", "expectedAppointmentId", "expectedAppointmentRevision",
                        "expectedSkill"), fields(ArchiveResumeRequest.class));
        assertEquals(Set.of("reason", "expectedAppointmentId", "expectedAppointmentRevision",
                        "expectedSkill", "newAppointmentId", "newAppointmentRevision", "newSkill"),
                fields(ArchiveReassignRequest.class));
        Set<String> adminMethods = Arrays.stream(ArchiveAdminController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertTrue(adminMethods.containsAll(Set.of("resolveInput", "resume", "reassign")));
        assertEquals(Set.of("sourceId", "newWork", "workId", "expectedAppointmentId",
                        "expectedAppointmentRevision", "expectedSkill"),
                fields(cn.jia.chat.archive.maintenance.dto.ArchiveResolveInputRequest.class));
    }
    @Test
    void recoveryContextRouteReturnsExactSanitizedShapeAndJobRevisionEtag() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveAdminController controller = new ArchiveAdminController(service, new ObjectMapper());
        ArchiveRecoveryContextDTO dto = new ArchiveRecoveryContextDTO("job-a", "7",
                new ArchiveRecoveryContextDTO.PreviousAppointment("appointment-old", "3",
                        new ArchiveSkillRef("archive-maintainer", "1.0.0", "a".repeat(64)),
                        "REVOKED"),
                List.of(new ArchiveRecoveryContextDTO.CandidateAppointment("appointment-new",
                        "5", new ArchiveSkillRef("archive-maintainer", "1.0.0",
                                "a".repeat(64)), "ACTIVE", "agent-a")));
        when(service.recoveryContext(any(), eq("job-a"))).thenReturn(dto);

        var response = controller.recoveryContext("job-a", authenticatedJwt());

        assertEquals("\"v7\"", response.getHeaders().getETag());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        ArgumentCaptor<cn.jia.chat.archive.maintenance.model.ArchiveActorScope> actor =
                ArgumentCaptor.forClass(cn.jia.chat.archive.maintenance.model.ArchiveActorScope.class);
        verify(service).recoveryContext(actor.capture(), eq("job-a"));
        assertEquals(new cn.jia.chat.archive.maintenance.model.ArchiveActorScope(
                "0", "client-a", "owner-a"), actor.getValue());

        var root = new ObjectMapper().valueToTree(response.getBody());
        assertEquals(Set.of("status", "code", "msg", "data"), names(root));
        var data = root.get("data");
        assertEquals(Set.of("jobId", "jobRevision", "previousAppointment", "candidates"),
                names(data));
        assertEquals(Set.of("appointmentId", "revision", "requiredSkill", "status"),
                names(data.get("previousAppointment")));
        assertEquals(Set.of("appointmentId", "revision", "requiredSkill", "status", "agentId"),
                names(data.get("candidates").get(0)));
        assertEquals(Set.of("key", "version", "packageSha256"),
                names(data.get("previousAppointment").get("requiredSkill")));
        String raw = root.toString();
        for (String forbidden : List.of("ownerJiacn", "clientId", "tenantId",
                "runtimeInstanceId", "storageUri", "bindingVersion")) {
            assertFalse(raw.contains(forbidden));
        }
    }

    @Test
    void withdrawAndValidationRoutesUseWorkCasStrictBodyAndSanitizedReceipts() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveAdminController controller = new ArchiveAdminController(service, new ObjectMapper());
        var receipt = new cn.jia.chat.archive.maintenance.dto.ArchiveWithdrawalDTO(
                "withdrawal-a", "edition-a", "rights correction", "HUMAN", "owner-a", "5",
                "2026-10-01T00:00:00Z", null, null, "8", "withdraw-key", "PENDING");
        when(service.withdraw(any(), eq("work-a"), eq("edition-a"), eq("withdraw-key"), eq(7L), any()))
                .thenReturn(receipt);

        var response = controller.withdraw("work-a", "edition-a", "withdraw-key", "\"v7\"",
                "{\"reason\":\"rights correction\",\"replacementActiveEditionId\":null}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), authenticatedJwt());

        assertEquals("\"v8\"", response.getHeaders().getETag());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        var withdrawalJson = new ObjectMapper().valueToTree(response.getBody().getData());
        assertEquals(Set.of("withdrawalId", "editionId", "reason", "actorType", "actorId",
                "authorizationRevision", "withdrawnAt", "requestedReplacementActiveEditionId",
                "resultingActiveEditionId", "resultingWorkRevision", "operationKey", "outboxState"),
                names(withdrawalJson));
        assertFalse(withdrawalJson.toString().contains("tenantId"));
        assertFalse(withdrawalJson.toString().contains("clientId"));
        assertFalse(withdrawalJson.toString().contains("ownerJiacn"));
        ArgumentCaptor<cn.jia.chat.archive.maintenance.dto.ArchiveWithdrawRequest> body =
                ArgumentCaptor.forClass(cn.jia.chat.archive.maintenance.dto.ArchiveWithdrawRequest.class);
        verify(service).withdraw(any(), eq("work-a"), eq("edition-a"), eq("withdraw-key"), eq(7L), body.capture());
        assertEquals("rights correction", body.getValue().reason());
        assertNull(body.getValue().replacementActiveEditionId());
        ArchiveMaintenanceException injected = assertThrows(ArchiveMaintenanceException.class,
                () -> controller.withdraw("work-a", "edition-a", "other", "\"v7\"",
                        "{\"reason\":\"x\",\"ownerJiacn\":\"forged\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8), authenticatedJwt()));
        assertEquals("INVALID_REQUEST", injected.code());

        var validation = new cn.jia.chat.archive.maintenance.dto.ArchiveValidationDTO(
                "validation-a", "draft-a", "4", "FAILED", "a".repeat(64), List.of("finding"));
        when(service.validation(any(), eq("draft-a"))).thenReturn(validation);
        var validationResponse = controller.validation("draft-a", authenticatedJwt());
        assertEquals("\"v4\"", validationResponse.getHeaders().getETag());
        assertEquals("FAILED", validationResponse.getBody().getData().outcome());

        var version = new cn.jia.chat.archive.maintenance.dto.ArchiveEditionVersionDTO(
                "publication-a", "platform-classics", "work-a", "edition-a", "4",
                "a".repeat(64), "b".repeat(64), "WITHDRAWN", "HUMAN", "owner-a",
                "5", "2026-09-30T00:00:00Z", receipt);
        var history = new cn.jia.chat.archive.maintenance.dto.ArchiveEditionHistoryDTO(
                "work-a", "8", null, List.of(version));
        when(service.editionHistory(any(), eq("work-a"))).thenReturn(history);
        when(service.edition(any(), eq("work-a"), eq("edition-a"))).thenReturn(version);
        var historyResponse = controller.editionHistory("work-a", authenticatedJwt());
        var detailResponse = controller.edition("work-a", "edition-a", authenticatedJwt());
        assertEquals("\"v8\"", historyResponse.getHeaders().getETag());
        assertEquals(Set.of("workId", "workRevision", "activeEditionId", "editions"),
                names(new ObjectMapper().valueToTree(historyResponse.getBody().getData())));
        assertEquals(Set.of("publicationId", "collectionId", "workId", "editionId",
                "draftRevision", "manifestSha256", "sourceSha256", "state", "actorType",
                "actorId", "authorizationRevision", "publishedAt", "withdrawal"),
                names(new ObjectMapper().valueToTree(detailResponse.getBody().getData())));
    }

    @Test
    void resolveInputUsesJobCasStrictBodyAndReturnsJobEtagWithoutIdentityFields() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveAdminController controller = new ArchiveAdminController(service, new ObjectMapper());
        var job = new cn.jia.chat.archive.maintenance.dto.ArchiveJobDTO(
                "job-a", "run-a", "platform-classics", "WAITING_SKILL",
                "CLIENT_UPDATE_REQUIRED", "8", "appointment-a", "agent-a", "DRAFT_ONLY",
                "MANUAL", "ADD_WORK", "work-a", "work-key", "Work", "source-a",
                "draft-a", null);
        when(service.resolveInput(any(), eq("job-a"), eq("resolve-key"), eq(7L), any()))
                .thenReturn(job);
        byte[] body = ("{\"sourceId\":\"source-a\",\"newWork\":{" +
                "\"canonicalKey\":\"work-key\",\"title\":\"Work\",\"language\":null}," +
                "\"workId\":null,\"expectedAppointmentId\":\"appointment-a\"," +
                "\"expectedAppointmentRevision\":\"3\",\"expectedSkill\":{" +
                "\"key\":\"archive-maintainer\",\"version\":\"1.0.0\"," +
                "\"packageSha256\":\"" + "a".repeat(64) + "\"}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var response = controller.resolveInput("job-a", "resolve-key", "\"v7\"",
                body, authenticatedJwt());

        assertEquals("\"v8\"", response.getHeaders().getETag());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        ArgumentCaptor<cn.jia.chat.archive.maintenance.dto.ArchiveResolveInputRequest> request =
                ArgumentCaptor.forClass(cn.jia.chat.archive.maintenance.dto.ArchiveResolveInputRequest.class);
        verify(service).resolveInput(any(), eq("job-a"), eq("resolve-key"), eq(7L), request.capture());
        assertEquals("source-a", request.getValue().sourceId());
        assertEquals("appointment-a", request.getValue().expectedAppointmentId());
        assertEquals("3", request.getValue().expectedAppointmentRevision());
        assertEquals("archive-maintainer", request.getValue().expectedSkill().key());
        var json = new ObjectMapper().valueToTree(response.getBody().getData());
        assertEquals(Set.of("jobId", "runId", "collectionId", "state", "waitReason", "revision",
                "appointmentId", "assignedAgentId", "permissionProfile", "publicationMode",
                "operation", "workId", "canonicalKey", "title", "sourceId", "draftId",
                "publicationId"), names(json));
        assertFalse(json.toString().contains("ownerJiacn"));
        assertFalse(json.toString().contains("tenantId"));

        byte[] injected = "{\"sourceId\":\"source-a\",\"ownerJiacn\":\"forged\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.resolveInput("job-a", "other", "\"v7\"",
                        injected, authenticatedJwt())).code());
        assertEquals("INVALID_CONDITIONAL_HEADER", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.resolveInput("job-a", "other", "\"7\"",
                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        authenticatedJwt())).code());
    }

    @Test
    void nativeContextRequiresSingletonExactGrantCommandAttemptAndEpochHeaders() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveNativeController controller = new ArchiveNativeController(service, new ObjectMapper());
        AgentRuntimeAuthentication authentication = mock(AgentRuntimeAuthentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new AgentRuntimeAuthentication.Scope(
                "0", "client-a", "owner-a", "agent-a", "runtime-a"));
        MockHttpServletRequest request = exactRequest();
        controller.context("job-a", "run-a", authentication, request);
        ArgumentCaptor<ArchiveRuntimeScope> scope = ArgumentCaptor.forClass(ArchiveRuntimeScope.class);
        verify(service).runtimeContext(scope.capture(), eq("job-a"), eq("run-a"));
        assertEquals(new ArchiveRuntimeScope("0", "client-a", "owner-a", "agent-a", "runtime-a",
                "grant-a", "execution-a", "command-a", 2, 3), scope.getValue());

        for (String missing : java.util.List.of("X-Archive-Grant-Ref", "X-Archive-Execution-Ref",
                "X-Archive-Command-Id", "X-Archive-Command-Attempt", "X-Archive-Execution-Epoch")) {
            MockHttpServletRequest invalid = exactRequest();
            invalid.removeHeader(missing);
            ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                    () -> controller.context("job-a", "run-a", authentication, invalid));
            assertEquals("RUNTIME_UNAUTHENTICATED", failure.code());
        }
        MockHttpServletRequest duplicate = exactRequest();
        duplicate.addHeader("X-Archive-Command-Id", "command-b");
        assertEquals("RUNTIME_UNAUTHENTICATED", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.context("job-a", "run-a", authentication, duplicate)).code());
        MockHttpServletRequest zeroAttempt = exactRequest();
        zeroAttempt.removeHeader("X-Archive-Command-Attempt");
        zeroAttempt.addHeader("X-Archive-Command-Attempt", "0");
        assertEquals("RUNTIME_UNAUTHENTICATED", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.context("job-a", "run-a", authentication, zeroAttempt)).code());
    }

    @Test
    void lifecycleRoutesUseStrictBodiesAndAuthenticatedHeaderScopeOnly() {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveNativeController controller = new ArchiveNativeController(service, new ObjectMapper());
        AgentRuntimeAuthentication authentication = mock(AgentRuntimeAuthentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new AgentRuntimeAuthentication.Scope(
                "0", "client-a", "owner-a", "agent-a", "runtime-a"));
        MockHttpServletRequest request = exactRequest();

        controller.start("job-a", "run-a", ("{\"commandId\":\"command-a\","
                + "\"messageId\":\"message-a\",\"attempt\":\"2\","
                + "\"executionEpoch\":\"3\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                authentication, request);
        controller.failure("job-a", "run-a", ("{\"phase\":\"RUNNER\","
                + "\"code\":\"RUNNER_CRASH\",\"retryable\":true}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), authentication, request);
        controller.result("job-a", "run-a", authentication, request);
        controller.publish("job-a", "run-a", "publish-key", "\"v7\"",
                ("{\"validationId\":\"val-a\",\"expectedActiveEditionId\":null,"
                        + "\"expectedWorkRevision\":\"4\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), authentication, request);

        verify(service).runtimeStart(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"),
                eq(new ArchiveRuntimeStartRequest("command-a", "message-a", "2", "3")));
        verify(service).runtimeFailure(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"),
                eq(new ArchiveRuntimeFailureRequest("RUNNER", "RUNNER_CRASH", true)));
        verify(service).runtimeResult(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"));
        verify(service).runtimePublish(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"),
                eq("publish-key"), eq(7L), eq(new ArchivePublishRequest("val-a", null, "4")));

        byte[] duplicate = ("{\"commandId\":\"command-a\",\"commandId\":\"command-b\","
                + "\"messageId\":\"message-a\",\"attempt\":\"2\","
                + "\"executionEpoch\":\"3\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.start("job-a", "run-a", duplicate, authentication, request)).code());
        byte[] identityInjection = ("{\"phase\":\"RUNNER\",\"code\":\"RUNNER_CRASH\","
                + "\"retryable\":true,\"tenantId\":\"0\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.failure("job-a", "run-a", identityInjection,
                        authentication, request)).code());
        byte[] publishIdentityInjection = ("{\"validationId\":\"val-a\","
                + "\"expectedActiveEditionId\":null,\"expectedWorkRevision\":\"4\","
                + "\"ownerJiacn\":\"owner-a\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.publish("job-a", "run-a", "publish-key", "\"v7\"",
                        publishIdentityInjection, authentication, request)).code());
        byte[] validPublish = ("{\"validationId\":\"val-a\",\"expectedActiveEditionId\":null,"
                + "\"expectedWorkRevision\":\"4\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_CONDITIONAL_HEADER", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.publish("job-a", "run-a", "publish-key", "\"7\"",
                        validPublish, authentication, request)).code());
    }

    private static Set<String> fields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static Set<String> names(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JwtAuthenticationToken authenticatedJwt() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a")
                .claim("sub", "manager-a").build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ARCHIVE_MANAGER")));
    }

    private static MockHttpServletRequest exactRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Archive-Grant-Ref", "grant-a");
        request.addHeader("X-Archive-Execution-Ref", "execution-a");
        request.addHeader("X-Archive-Command-Id", "command-a");
        request.addHeader("X-Archive-Command-Attempt", "2");
        request.addHeader("X-Archive-Execution-Epoch", "3");
        return request;
    }

}
