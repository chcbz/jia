package cn.jia.chat.archive.maintenance.http;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockInput;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftUpdateRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeContextDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeFailureRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeStartRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeResultDTO;
import cn.jia.chat.archive.maintenance.dto.ArchivePublishRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRecoveryContextDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveRepairResolution;
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
        assertTrue(methods.contains("putBlock"));
        assertFalse(methods.contains("updateDraft"));
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
                        "expectedSkill", "repairResolution"), fields(ArchiveResumeRequest.class));
        assertEquals(Set.of("reason", "expectedAppointmentId", "expectedAppointmentRevision",
                        "expectedSkill", "newAppointmentId", "newAppointmentRevision", "newSkill",
                        "repairResolution"), fields(ArchiveReassignRequest.class));
        assertEquals(Set.of("failureId", "resolutionCode"), fields(ArchiveRepairResolution.class));
        Set<String> adminMethods = Arrays.stream(ArchiveAdminController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertTrue(adminMethods.containsAll(Set.of("resolveInput", "resume", "reassign")));
        assertEquals(Set.of("sourceId", "newWork", "workId", "expectedAppointmentId",
                        "expectedAppointmentRevision", "expectedSkill"),
                fields(cn.jia.chat.archive.maintenance.dto.ArchiveResolveInputRequest.class));
    }
    @Test
    void exactAdminDraftRoutesUseStrictBodiesDraftEtagsAndQueryable202Locations() throws Exception {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveAdminController controller = new ArchiveAdminController(service, new ObjectMapper());
        var paragraph = new cn.jia.chat.archive.maintenance.dto.ArchiveDraftParagraphInput(
                1, "正文", List.of(new cn.jia.chat.archive.maintenance.dto.ArchiveSourceRangeInput(0, 6)));
        var block = new ArchiveDraftBlockInput("CHAPTER", "chapter-1", 1, "第一回",
                List.of(new cn.jia.chat.archive.maintenance.dto.ArchiveSourceRangeInput(0, 9)),
                List.of(paragraph));
        var blockResult = new cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockDTO(
                "draft-a", "job-a", "8", "EDITABLE", block);
        when(service.putDraftBlock(any(), eq("draft-a"), eq("chapter-1"), eq("block-key"),
                eq(7L), any())).thenReturn(blockResult);
        byte[] blockBody = new ObjectMapper().writeValueAsBytes(block);

        var put = controller.putDraftBlock("draft-a", "chapter-1", "block-key", "\"v7\"",
                blockBody, authenticatedJwt());

        assertEquals("\"v8\"", put.getHeaders().getETag());
        assertEquals("private, no-store", put.getHeaders().getCacheControl());
        assertEquals("chapter-1", put.getBody().getData().block().blockKey());
        byte[] injected = (new String(blockBody, java.nio.charset.StandardCharsets.UTF_8)
                .replaceFirst("\\{", "{\"ownerJiacn\":\"forged\","))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.putDraftBlock("draft-a", "chapter-1", "other", "\"v7\"",
                        injected, authenticatedJwt())).code());

        var patched = new cn.jia.chat.archive.maintenance.dto.ArchiveDraftDTO(
                "draft-a", "job-a", "9", "EDITABLE",
                new ArchiveDraftUpdateRequest(List.of(block), List.of()), "a".repeat(64), null, null);
        when(service.patchDraft(any(), eq("draft-a"), eq("patch-key"), eq(8L), any()))
                .thenReturn(patched);
        byte[] patchBody = ("{\"blocks\":[{\"blockKey\":\"chapter-1\",\"ordinal\":1,"
                + "\"title\":\"第一回\",\"titleSourceRanges\":[{\"startByte\":0,\"endByte\":9}]}],"
                + "\"excludedSourceRanges\":null}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var patch = controller.patchDraft("draft-a", "patch-key", "\"v8\"",
                patchBody, authenticatedJwt());
        assertEquals("\"v9\"", patch.getHeaders().getETag());

        var accepted = new cn.jia.chat.archive.maintenance.dto.ArchiveOperationAcceptedDTO(
                "aop-1", "job-a", "COMMITTED");
        when(service.validateDraft(any(), eq("draft-a"), eq("validate-key"), eq(9L)))
                .thenReturn(accepted);
        var response = controller.validateDraft("draft-a", "validate-key", "\"v9\"",
                authenticatedJwt());
        assertEquals(202, response.getStatusCode().value());
        assertEquals("/archive/admin/v1/operations/aop-1",
                response.getHeaders().getFirst(org.springframework.http.HttpHeaders.LOCATION));
        assertEquals("job-a", response.getBody().getData().jobId());

        byte[] sourceBody = ("{\"sourceName\":\"source\",\"sourceVersion\":\"v1\","
                + "\"rightsBasis\":\"authorized\",\"declaredSha256\":\"" + "a".repeat(64)
                + "\",\"contentBase64\":\"eA==\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.prepareSource(any(), eq("platform-classics"), eq("source-key"), any()))
                .thenReturn(new cn.jia.chat.archive.maintenance.dto.ArchiveOperationAcceptedDTO(
                        "src-operation", null, "COMMITTED"));
        var sourceAccepted = controller.prepareSource("platform-classics", "source-key",
                sourceBody, authenticatedJwt());
        assertEquals(202, sourceAccepted.getStatusCode().value());
        assertEquals("/archive/admin/v1/operations/src-operation",
                sourceAccepted.getHeaders().getFirst(org.springframework.http.HttpHeaders.LOCATION));
        assertNull(sourceAccepted.getBody().getData().jobId());

        Set<String> methods = Arrays.stream(ArchiveAdminController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertTrue(methods.containsAll(Set.of("works", "draftBlock", "putDraftBlock",
                "patchDraft", "validateDraft", "publishDraft", "operation")));
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
                                "a".repeat(64)), "ACTIVE", "agent-a", true, true)),
                false, new ArchiveRecoveryContextDTO.LatestFailure("19", "RUNNER",
                        "RUNNER_CRASH", true, "bounded diagnostic", true, false,
                        List.of("RUNTIME_REPAIRED")));
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
        assertEquals(Set.of("jobId", "jobRevision", "previousAppointment", "candidates",
                        "resumeAllowed", "latestFailure"), names(data));
        assertEquals(Set.of("appointmentId", "revision", "requiredSkill", "status"),
                names(data.get("previousAppointment")));
        assertEquals(Set.of("appointmentId", "revision", "requiredSkill", "status", "agentId",
                        "recoveryAllowed", "inputChanged"),
                names(data.get("candidates").get(0)));
        assertEquals(Set.of("failureId", "phase", "code", "retryable", "diagnostic",
                        "blockedRootCause", "inputChangedForResume", "allowedRepairResolutionCodes"),
                names(data.get("latestFailure")));
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
                "actorId", "authorizationRevision", "publishedAt", "withdrawal", "verification"),
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
                "publicationId", "handling"), names(json));
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
    void jobReadReturnsOnlyStructuredAuthorizedFactsWithExactDecimalStrings() throws Exception {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveAdminController controller = new ArchiveAdminController(service, new ObjectMapper());
        var verification = new cn.jia.chat.archive.maintenance.dto.ArchivePublicationVerificationDTO(
                "PASSED", "2", "b".repeat(64), List.of(), "2026-10-02T00:00:00Z");
        var receipt = new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.Receipt(
                "publication-a", "job-a", "work-a", "edition-a", "9",
                "c".repeat(64), "d".repeat(64));
        var assignment = new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.AssignmentSnapshot(
                "appointment-a", "4", "agent-a", "PUBLISH_VALIDATED");
        var handling = new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO(
                "三国演义", "platform-classics",
                new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.SourceRef(
                        "source-a", "三国演义底本", "v1"),
                "agent-a", "PUBLISH_VALIDATED", "MANUAL", "PUBLISHED", null,
                new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.Progress(
                        "120", true, "120"),
                new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.CurrentPublication(
                        "PUBLISHED", receipt, verification,
                        new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.ReaderTarget(
                                "work-a", "edition-a")), "ACTIVE", assignment);
        var job = new cn.jia.chat.archive.maintenance.dto.ArchiveJobDTO(
                "job-a", "run-a", "platform-classics", "PUBLISHED", null,
                "9223372036854775806", "appointment-a", "agent-a",
                "PUBLISH_VALIDATED", "MANUAL", "ADD_WORK", "work-a",
                "three-kingdoms", "三国演义", "source-a", "draft-a",
                "publication-a", handling);
        var changedVerification = new cn.jia.chat.archive.maintenance.dto.ArchivePublicationVerificationDTO(
                "FAILED", "3", "e".repeat(64), List.of("reader mismatch"),
                "2026-10-02T00:01:00Z");
        var changedHandling = new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO(
                handling.title(), handling.collectionId(), handling.source(), handling.assignedAgentId(),
                handling.permissionProfile(), handling.publicationMode(), handling.stage(), handling.blocker(),
                handling.progress(),
                new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.CurrentPublication(
                        "PUBLISHED", receipt, changedVerification, null),
                handling.assignmentStatus(), handling.assignmentSnapshot());
        var changedJob = new cn.jia.chat.archive.maintenance.dto.ArchiveJobDTO(
                job.jobId(), job.runId(), job.collectionId(), job.state(), job.waitReason(), job.revision(),
                job.appointmentId(), job.assignedAgentId(), job.permissionProfile(), job.publicationMode(),
                job.operation(), job.workId(), job.canonicalKey(), job.title(), job.sourceId(), job.draftId(),
                job.publicationId(), changedHandling);
        when(service.getJob(any(), eq("job-a"))).thenReturn(job, changedJob);

        var response = controller.job("job-a", authenticatedJwt());
        var changedResponse = controller.job("job-a", authenticatedJwt());
        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().valueToTree(
                response.getBody().getData());

        assertTrue(response.getHeaders().getETag().matches("\"r-[0-9a-f]{64}\""));
        assertNotEquals(response.getHeaders().getETag(), changedResponse.getHeaders().getETag(),
                "same job revision with changed readback facts must change the representation validator");
        assertEquals("9223372036854775806", json.get("revision").asText());
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("三国演义底本", json.at("/handling/source/sourceName").asText());
        assertEquals("120", json.at("/handling/progress/completedChapters").asText());
        assertTrue(json.at("/handling/progress/totalKnown").asBoolean());
        assertEquals("publication-a", json.at(
                "/handling/currentPublication/receipt/publicationId").asText());
        assertEquals("PASSED", json.at(
                "/handling/currentPublication/verification/state").asText());
        assertEquals("edition-a", json.at(
                "/handling/currentPublication/readerTarget/editionId").asText());
        assertEquals(Set.of("title", "collectionId", "source", "assignedAgentId",
                "permissionProfile", "publicationMode", "stage", "blocker", "progress",
                "currentPublication", "assignmentStatus", "assignmentSnapshot"),
                names(json.get("handling")));
        assertEquals("ACTIVE", json.at("/handling/assignmentStatus").asText());
        assertEquals(Set.of("appointmentId", "appointmentRevision", "assignedAgentId",
                "permissionProfile"), names(json.at("/handling/assignmentSnapshot")));
        var legacyHandling = new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO(
                "legacy", "platform-classics", null, "snapshot-agent", "DRAFT_ONLY",
                "MANUAL", "WAITING_SKILL", "CLIENT_UPDATE_REQUIRED",
                new cn.jia.chat.archive.maintenance.dto.ArchiveJobHandlingFactsDTO.Progress(
                        "0", false, null), null);
        assertEquals("UNVERIFIED", legacyHandling.assignmentStatus());
        assertNull(legacyHandling.assignedAgentId());
        assertNull(legacyHandling.permissionProfile());
        assertNull(legacyHandling.assignmentSnapshot());
        assertEquals(Set.of("sourceId", "sourceName", "sourceVersion"),
                names(json.at("/handling/source")));
        assertEquals(Set.of("completedChapters", "totalKnown", "totalChapters"),
                names(json.at("/handling/progress")));
        assertEquals(Set.of("state", "receipt", "verification", "readerTarget"),
                names(json.at("/handling/currentPublication")));
        assertEquals(Set.of("publicationId", "jobId", "workId", "editionId",
                "draftRevision", "manifestSha256", "sourceSha256"),
                names(json.at("/handling/currentPublication/receipt")));
        assertEquals(Set.of("state", "revision", "verificationDigest", "findings", "checkedAt"),
                names(json.at("/handling/currentPublication/verification")));
        assertEquals(Set.of("workId", "editionId"),
                names(json.at("/handling/currentPublication/readerTarget")));

        var resumeResult = new cn.jia.chat.archive.maintenance.dto.ArchiveExecutionRecoveryDTO(
                "job-a", "run-b", "2", "WAITING");
        when(service.resume(any(), eq("job-a"), eq("resume-key"),
                eq(9223372036854775806L), any())).thenReturn(resumeResult);
        byte[] resumeBody = new ObjectMapper().writeValueAsBytes(new ArchiveResumeRequest(
                "resume", "appointment-a", "4",
                new ArchiveSkillRef("archive-maintainer", "1.0.0", "f".repeat(64))));
        controller.resume("job-a", "resume-key", "\"v9223372036854775806\"",
                resumeBody, authenticatedJwt());
        verify(service).resume(any(), eq("job-a"), eq("resume-key"),
                eq(9223372036854775806L), any());
        byte[] injectedRepair = ("{\"reason\":\"resume\","
                + "\"expectedAppointmentId\":\"appointment-a\","
                + "\"expectedAppointmentRevision\":\"4\","
                + "\"expectedSkill\":{\"key\":\"archive-maintainer\","
                + "\"version\":\"1.0.0\",\"packageSha256\":\"" + "f".repeat(64) + "\"},"
                + "\"repairResolution\":{\"failureId\":\"19\","
                + "\"resolutionCode\":\"RUNTIME_REPAIRED\",\"ownerJiacn\":\"forged\"}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.resume("job-a", "injected-repair", "\"v9223372036854775806\"",
                        injectedRepair, authenticatedJwt())).code());
        verify(service, never()).resume(any(), eq("job-a"), eq("injected-repair"),
                anyLong(), any());
        assertEquals("INVALID_CONDITIONAL_HEADER", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.resume("job-a", "bad-key", response.getHeaders().getETag(),
                        resumeBody, authenticatedJwt())).code(),
                "representation validators must not masquerade as job mutation revisions");
        String serialized = json.toString();
        for (String forbidden : List.of("tenantId", "clientId", "ownerJiacn", "storageUri",
                "rightsBasis", "sourceSummary", "content", "actorId", "credential")) {
            assertFalse(serialized.contains(forbidden), forbidden);
        }
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
        when(service.runtimeContext(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a")))
                .thenReturn(new ArchiveRuntimeContextDTO(
                        "job-a", "run-a", "collection-a", "work-a", "REVISE_WORK", "4", null,
                        "appointment-a", "1", "agent-a", "1", "PUBLISH_VALIDATED", "MANUAL",
                        "RUNNING", null, new ArchiveSkillRef("archive-maintainer", "1.0.0", "f".repeat(64)),
                        "source-a", "a".repeat(64), "source-summary", "rights-basis", "draft-a", "7"));
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
        ArchiveRuntimeResultDTO result = new ArchiveRuntimeResultDTO(
                "job-a", "run-a", "command-a", "2", "3", "RUNNING", "2",
                "RUNNING", "3", "RUNNING", null, null, null, "7",
                null, null, null, null, null, null, null);
        when(service.runtimeStart(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"),
                any(ArchiveRuntimeStartRequest.class))).thenReturn(result);
        when(service.runtimeFailure(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"),
                any(ArchiveRuntimeFailureRequest.class))).thenReturn(result);
        when(service.runtimeResult(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a")))
                .thenReturn(result);
        when(service.runtimePublish(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"),
                eq("publish-key"), eq(7L), any(ArchivePublishRequest.class)))
                .thenReturn(new cn.jia.chat.archive.maintenance.dto.ArchivePublicationDTO(
                        "publication-a", "job-a", "work-a", "edition-a", "7",
                        "b".repeat(64), "a".repeat(64), "PUBLISHED", "PENDING"));

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

    @Test
    void nativeDtosMatchFrozenControlledClientKeySetsAndKeepAdminVerificationSeparate() {
        assertEquals(Set.of("jobId", "runId", "commandId", "attempt", "executionEpoch",
                        "runState", "runRevision", "jobState", "jobRevision", "stage",
                        "validationId", "validationOutcome", "validationDigest", "draftRevision",
                        "publicationId", "workId", "editionId", "publicationState",
                        "failurePhase", "failureCode", "failureRetryable", "failureId",
                        "blockedRootCause", "failureDiagnostic"),
                fields(ArchiveRuntimeResultDTO.class));
        assertEquals(Set.of("agentId", "appointmentId", "appointmentRevision", "bindingVersion",
                        "collectionId", "draftId", "draftRevision", "expectedActiveEditionId",
                        "expectedWorkRevision", "jobId", "operation", "permissionProfile",
                        "publicationMode", "requiredSkill", "rightsBasis", "runId", "sourceId",
                        "sourceSha256", "sourceSummary", "state", "waitReason", "workId"),
                fields(ArchiveRuntimeContextDTO.class));
        assertEquals(Set.of("key", "packageSha256", "version"), fields(ArchiveSkillRef.class));
        assertEquals(Set.of("checkpoints", "content", "contentSha256", "draftId", "jobId", "revision", "state",
                        "validatedRevision", "validationId"),
                fields(cn.jia.chat.archive.maintenance.dto.ArchiveDraftDTO.class));
        assertEquals(Set.of("blockKey", "draftRevision", "digest", "byteLength"),
                fields(cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockCheckpointDTO.class));
        assertEquals(Set.of("draftId", "draftRevision", "findings", "outcome",
                        "validationDigest", "validationId"),
                fields(cn.jia.chat.archive.maintenance.dto.ArchiveValidationDTO.class));
        assertEquals(Set.of("operationId", "jobId", "state"),
                fields(cn.jia.chat.archive.maintenance.dto.ArchiveOperationAcceptedDTO.class));
        assertEquals(Set.of("draftRevision", "editionId", "jobId", "manifestSha256",
                        "publicationId", "readbackState", "sourceSha256", "state", "workId"),
                fields(cn.jia.chat.archive.maintenance.dto.ArchiveNativePublicationDTO.class));
        assertTrue(fields(cn.jia.chat.archive.maintenance.dto.ArchivePublicationDTO.class)
                .contains("verification"), "admin publication facts must retain current verification");
    }
    @Test
    void nativeBlockAndValidationUseOnlyExactRouteAndStrict202ReceiptThenScoped200Query()
            throws Exception {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        var block = new ArchiveDraftBlockInput("CHAPTER", "chapter-1", 1, "第一回",
                List.of(), List.of());
        var content = new ArchiveDraftUpdateRequest(List.of(block), List.of());
        var draft = new cn.jia.chat.archive.maintenance.dto.ArchiveDraftDTO(
                "draft-a", "job-a", "1", "EDITABLE", content,
                "a".repeat(64), null, null, List.of(
                new cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockCheckpointDTO(
                        "chapter-1", "1", "c".repeat(64), "96")));
        when(service.runtimePutBlock(any(), eq("job-a"), eq("run-a"), eq("chapter-1"),
                eq("block-key"), eq(0L), eq(content))).thenReturn(draft);
        when(service.runtimeValidate(any(), eq("job-a"), eq("run-a"),
                eq("validate-key"), eq(1L))).thenReturn(
                new cn.jia.chat.archive.maintenance.dto.ArchiveOperationAcceptedDTO(
                        "val-a", "job-a", "COMMITTED"));
        var validation = new cn.jia.chat.archive.maintenance.dto.ArchiveValidationDTO(
                "val-a", "draft-a", "1", "FAILED", "b".repeat(64),
                List.of("finding"));
        when(service.runtimeValidation(any(), eq("job-a"), eq("run-a"), eq("val-a")))
                .thenReturn(validation);
        when(service.runtimeValidation(any(), eq("job-a"), eq("run-a"), isNull()))
                .thenReturn(validation);
        AgentRuntimeAuthentication authentication = mock(AgentRuntimeAuthentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new AgentRuntimeAuthentication.Scope(
                "0", "client-a", "owner-a", "agent-a", "runtime-a"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ArchiveNativeController(service, new ObjectMapper()))
                .setControllerAdvice(new cn.jia.core.security.SensitiveResponseBodyAdvice(
                        new cn.jia.core.security.SensitiveResponseProperties()))
                .setMessageConverters(
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                        new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
                                tools.jackson.databind.json.JsonMapper.builder().build()))
                .build();
        String blockJson = new ObjectMapper().writeValueAsString(content);
        var put = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/internal/archive/v1/jobs/job-a/runs/run-a/blocks/chapter-1")
                        .principal(authentication).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(blockJson).header("Idempotency-Key", "block-key")
                        .header("If-Match", "\"v0\"")
                        .header("X-Archive-Grant-Ref", "grant-a")
                        .header("X-Archive-Execution-Ref", "execution-a")
                        .header("X-Archive-Command-Id", "command-a")
                        .header("X-Archive-Command-Attempt", "2")
                        .header("X-Archive-Execution-Epoch", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse();
        assertEquals("\"v1\"", put.getHeader("ETag"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/internal/archive/v1/jobs/job-a/runs/run-a/draft")
                        .principal(authentication).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(blockJson))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isMethodNotAllowed());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/internal/archive/v1/jobs/job-a/runs/run-a/draft/blocks/chapter-1")
                        .principal(authentication).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(blockJson))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());

        var accepted = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/internal/archive/v1/jobs/job-a/runs/run-a/validate")
                        .principal(authentication).header("Idempotency-Key", "validate-key")
                        .header("If-Match", "\"v1\"")
                        .header("X-Archive-Grant-Ref", "grant-a")
                        .header("X-Archive-Execution-Ref", "execution-a")
                        .header("X-Archive-Command-Id", "command-a")
                        .header("X-Archive-Command-Attempt", "2")
                        .header("X-Archive-Execution-Epoch", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse();
        assertEquals("/internal/archive/v1/jobs/job-a/runs/run-a/validation?operationId=val-a",
                accepted.getHeader("Location"));
        var acceptedJson = new ObjectMapper().readTree(accepted.getContentAsByteArray());
        assertEquals(Set.of("code", "data", "msg", "status"), names(acceptedJson));
        assertEquals(202, acceptedJson.path("status").asInt());
        assertEquals(Set.of("operationId", "jobId", "state"), names(acceptedJson.path("data")));

        var queried = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/archive/v1/jobs/job-a/runs/run-a/validation")
                        .queryParam("operationId", "val-a").principal(authentication)
                        .header("X-Archive-Grant-Ref", "grant-a")
                        .header("X-Archive-Execution-Ref", "execution-a")
                        .header("X-Archive-Command-Id", "command-a")
                        .header("X-Archive-Command-Attempt", "2")
                        .header("X-Archive-Execution-Epoch", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse();
        var queriedJson = new ObjectMapper().readTree(queried.getContentAsByteArray());
        assertEquals(200, queriedJson.path("status").asInt());
        assertEquals("val-a", queriedJson.at("/data/validationId").asText());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/archive/v1/jobs/job-a/runs/run-a/validation")
                        .principal(authentication)
                        .header("X-Archive-Grant-Ref", "grant-a")
                        .header("X-Archive-Execution-Ref", "execution-a")
                        .header("X-Archive-Command-Id", "command-a")
                        .header("X-Archive-Command-Attempt", "2")
                        .header("X-Archive-Execution-Epoch", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.validationId").value("val-a"));
        verify(service).runtimeValidation(any(), eq("job-a"), eq("run-a"), isNull());

        ArchiveNativeController controller = new ArchiveNativeController(service, new ObjectMapper());
        MockHttpServletRequest duplicateQuery = exactRequest();
        duplicateQuery.addParameter("operationId", "val-a", "val-b");
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.validation("job-a", "run-a", authentication,
                        duplicateQuery)).code());
        byte[] multipleBlocks = ("{\"blocks\":[" + new ObjectMapper().writeValueAsString(block)
                + "," + new ObjectMapper().writeValueAsString(block)
                + "],\"excludedSourceRanges\":[]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.putBlock("job-a", "run-a", "chapter-1", "key", "\"v0\"",
                        multipleBlocks, authentication, exactRequest())).code());
        byte[] identityInjection = blockJson.replaceFirst("\\{", "{\"ownerJiacn\":\"forged\",")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> controller.putBlock("job-a", "run-a", "chapter-1", "key", "\"v0\"",
                        identityInjection, authentication, exactRequest())).code());
    }

    @Test
    void nativeResultUsesExactFourFieldEnvelopeThroughJackson3MvcConverter() throws Exception {
        ArchiveMaintenanceService service = mock(ArchiveMaintenanceService.class);
        ArchiveRuntimeResultDTO running = new ArchiveRuntimeResultDTO(
                "job-a", "run-a", "command-a", "2", "3", "RUNNING", "2",
                "RUNNING", "3", "RUNNING", null, null, null, "0",
                null, null, null, null, null, null, null);
        when(service.runtimeResult(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a")))
                .thenReturn(running);
        var publication = new cn.jia.chat.archive.maintenance.dto.ArchivePublicationDTO(
                "publication-a", "job-a", "work-a", "edition-a", "1", "a".repeat(64),
                "b".repeat(64), "PUBLISHED", "PASSED",
                new cn.jia.chat.archive.maintenance.dto.ArchivePublicationVerificationDTO(
                        "PASSED", "2", "c".repeat(64), List.of(), "2026-10-03T08:00:00Z"));
        when(service.runtimePublish(any(ArchiveRuntimeScope.class), eq("job-a"), eq("run-a"),
                eq("publish-key"), eq(1L), any(ArchivePublishRequest.class))).thenReturn(publication);
        AgentRuntimeAuthentication authentication = mock(AgentRuntimeAuthentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new AgentRuntimeAuthentication.Scope(
                "0", "client-a", "owner-a", "agent-a", "runtime-a"));

        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ArchiveNativeController(service, new ObjectMapper()))
                .setControllerAdvice(new cn.jia.core.security.SensitiveResponseBodyAdvice(
                        new cn.jia.core.security.SensitiveResponseProperties()))
                .setMessageConverters(
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                        new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(
                                tools.jackson.databind.json.JsonMapper.builder().build()))
                .build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/archive/v1/jobs/job-a/runs/run-a/result")
                        .principal(authentication)
                        .header("X-Archive-Grant-Ref", "grant-a")
                        .header("X-Archive-Execution-Ref", "execution-a")
                        .header("X-Archive-Command-Id", "command-a")
                        .header("X-Archive-Command-Attempt", "2")
                        .header("X-Archive-Execution-Epoch", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse();

        var json = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(Set.of("code", "data", "msg", "status"), names(json));
        assertFalse(json.has("location"));
        assertEquals("E0", json.path("code").asText());
        assertEquals("ok", json.path("msg").asText());
        assertEquals(200, json.path("status").asInt());
        assertEquals(Set.of("jobId", "runId", "commandId", "attempt", "executionEpoch",
                        "runState", "runRevision", "jobState", "jobRevision", "stage",
                        "validationId", "validationOutcome", "validationDigest", "draftRevision",
                        "publicationId", "workId", "editionId", "publicationState",
                        "failurePhase", "failureCode", "failureRetryable", "failureId",
                        "blockedRootCause", "failureDiagnostic"), names(json.path("data")));
        assertTrue(json.at("/data/validationId").isNull());
        assertTrue(json.at("/data/publicationId").isNull());
        assertTrue(json.at("/data/failureCode").isNull());
        var publishResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/internal/archive/v1/jobs/job-a/runs/run-a/publish")
                        .principal(authentication)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"validationId\":\"validation-a\",\"expectedActiveEditionId\":null,"
                                + "\"expectedWorkRevision\":\"4\"}")
                        .header("Idempotency-Key", "publish-key")
                        .header("If-Match", "\"v1\"")
                        .header("X-Archive-Grant-Ref", "grant-a")
                        .header("X-Archive-Execution-Ref", "execution-a")
                        .header("X-Archive-Command-Id", "command-a")
                        .header("X-Archive-Command-Attempt", "2")
                        .header("X-Archive-Execution-Epoch", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse();
        var publishJson = new ObjectMapper().readTree(publishResponse.getContentAsByteArray());
        assertEquals(Set.of("code", "data", "msg", "status"), names(publishJson));
        assertEquals(Set.of("draftRevision", "editionId", "jobId", "manifestSha256",
                        "publicationId", "readbackState", "sourceSha256", "state", "workId"),
                names(publishJson.path("data")));
        assertFalse(publishJson.path("data").has("verification"));
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
