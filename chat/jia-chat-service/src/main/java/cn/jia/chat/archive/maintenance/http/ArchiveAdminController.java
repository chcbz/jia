package cn.jia.chat.archive.maintenance.http;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.dto.ArchivePageDTO;
import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/archive/admin/v1")
public class ArchiveAdminController {
    private final ArchiveMaintenanceService service;
    private final ObjectMapper mapper;

    public ArchiveAdminController(ArchiveMaintenanceService service, ObjectMapper mapper) {
        this.service = Objects.requireNonNull(service, "service");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @GetMapping("/collections/{collectionId}/capabilities")
    public JsonResult<ArchiveCapabilitiesDTO> capabilities(@PathVariable String collectionId,
                                                            Authentication authentication) {
        return JsonResult.success(service.capabilities(actor(authentication), collectionId));
    }

    @GetMapping("/collections/{collectionId}/slot")
    public JsonResult<cn.jia.chat.archive.maintenance.dto.ArchiveSlotDTO> slot(
            @PathVariable String collectionId, Authentication authentication) {
        return JsonResult.success(service.slot(actor(authentication), collectionId));
    }

    @GetMapping("/collections/{collectionId}/works/{workId}/state")
    public JsonResult<cn.jia.chat.archive.maintenance.dto.ArchiveWorkStateDTO> workState(
            @PathVariable String collectionId, @PathVariable String workId, Authentication authentication) {
        return JsonResult.success(service.workState(actor(authentication), collectionId, workId));
    }

    @GetMapping("/works/{workId}/editions")
    public ResponseEntity<JsonResult<ArchiveEditionHistoryDTO>> editionHistory(
            @PathVariable String workId, Authentication authentication) {
        ArchiveEditionHistoryDTO result = service.editionHistory(actor(authentication), workId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.workRevision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @GetMapping("/works/{workId}/editions/{editionId}")
    public ResponseEntity<JsonResult<ArchiveEditionVersionDTO>> edition(
            @PathVariable String workId, @PathVariable String editionId,
            Authentication authentication) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(service.edition(actor(authentication), workId, editionId)));
    }

    @PostMapping("/works/{workId}/editions/{editionId}/withdraw")
    public ResponseEntity<JsonResult<ArchiveWithdrawalDTO>> withdraw(
            @PathVariable String workId, @PathVariable String editionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveWithdrawalDTO result = service.withdraw(actor(authentication), workId, editionId,
                key, ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveWithdrawRequest.class));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.resultingWorkRevision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @GetMapping("/collections/{collectionId}/appointments")
    public JsonResult<ArchivePageDTO<ArchiveAppointmentDTO>> appointments(
            @PathVariable String collectionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit,
            Authentication authentication) {
        return JsonResult.success(service.appointments(actor(authentication), collectionId,
                cursor, pageLimit(limit, 50)));
    }

    @PostMapping("/collections/{collectionId}/appointments")
    public ResponseEntity<JsonResult<ArchiveAppointmentDTO>> createAppointment(
            @PathVariable String collectionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body,
            Authentication authentication) {
        ArchiveAppointmentDTO result = service.createAppointment(actor(authentication), collectionId, key,
                ArchiveHttpPreconditions.revision(ifMatch), ArchiveStrictRequest.read(mapper, body, ArchiveAppointmentCreateRequest.class));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(JsonResult.success(result));
    }

    @PostMapping("/collections/{collectionId}/manager-authorization/revoke")
    public ResponseEntity<JsonResult<ArchiveOperationDTO>> revokeManagerAuthorization(
            @PathVariable String collectionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveOperationDTO result = service.revokeManagerAuthorization(actor(authentication), collectionId,
                key, ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveManagerRevokeRequest.class));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PostMapping("/appointments/{appointmentId}/revoke")
    public ResponseEntity<JsonResult<ArchiveAppointmentDTO>> revoke(
            @PathVariable String appointmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body,
            Authentication authentication) {
        ArchiveAppointmentDTO result = service.revokeAppointment(actor(authentication), appointmentId, key,
                ArchiveHttpPreconditions.revision(ifMatch), body == null || body.length == 0 ? null : ArchiveStrictRequest.read(mapper, body, ArchiveAppointmentRevokeRequest.class));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(JsonResult.success(result));
    }

    @PostMapping("/collections/{collectionId}/source-snapshots")
    public ResponseEntity<JsonResult<ArchiveOperationAcceptedDTO>> prepareSource(
            @PathVariable String collectionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveOperationAcceptedDTO accepted = service.prepareSource(actor(authentication), collectionId, key,
                ArchiveStrictRequest.read(mapper, body, cn.jia.chat.archive.maintenance.dto.ArchiveSourcePrepareRequest.class));
        return acceptedOperation(accepted);
    }

    @GetMapping("/source-snapshots/{sourceId}")
    public ResponseEntity<JsonResult<cn.jia.chat.archive.maintenance.dto.ArchiveSourceSnapshotDTO>> source(
            @PathVariable String sourceId, Authentication authentication) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(service.source(actor(authentication), sourceId)));
    }
    @PostMapping("/collections/{collectionId}/requests")
    public ResponseEntity<JsonResult<ArchiveMaintenanceRequestResultDTO>> request(
            @PathVariable String collectionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body, Authentication authentication) {
        requireIdempotencyKey(key);
        ArchiveMaintenanceRequest request = ArchiveStrictRequest.read(
                mapper, body, ArchiveMaintenanceRequest.class);
        ArchiveMaintenanceRequestResultDTO result = service.confirmRequest(
                actor(authentication), collectionId, key, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PostMapping("/collections/{collectionId}/jobs")
    public ResponseEntity<JsonResult<ArchiveJobDTO>> createJob(@PathVariable String collectionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveJobDTO result = service.createJob(actor(authentication), collectionId, key, ArchiveStrictRequest.read(mapper, body, ArchiveJobCreateRequest.class));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(JsonResult.success(result));
    }

    @PostMapping("/jobs/{jobId}/resolve-input")
    public ResponseEntity<JsonResult<ArchiveJobDTO>> resolveInput(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveJobDTO result = service.resolveInput(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveResolveInputRequest.class));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PostMapping("/jobs/{jobId}/execute")
    public ResponseEntity<JsonResult<ArchiveExecutionDTO>> execute(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        ArchiveExecutionDTO result = service.ensureExecution(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PostMapping("/jobs/{jobId}/resume")
    public ResponseEntity<JsonResult<ArchiveExecutionRecoveryDTO>> resume(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveExecutionRecoveryDTO result = service.resume(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveResumeRequest.class));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PostMapping("/jobs/{jobId}/reassign")
    public ResponseEntity<JsonResult<ArchiveExecutionRecoveryDTO>> reassign(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveExecutionRecoveryDTO result = service.reassign(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveReassignRequest.class));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JsonResult<ArchiveJobDTO>> job(@PathVariable String jobId,
                                                            Authentication authentication) {
        ArchiveJobDTO result = service.getJob(actor(authentication), jobId);
        JsonResult<ArchiveJobDTO> body = JsonResult.success(result);
        // ETag validates the complete GET representation. The decimal body revision remains the
        // authoritative token for existing "vN" mutation If-Match requests.
        return ResponseEntity.ok().header(HttpHeaders.ETAG, jobRepresentationEtag(body))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    @GetMapping("/jobs/{jobId}/recovery-context")
    public ResponseEntity<JsonResult<ArchiveRecoveryContextDTO>> recoveryContext(
            @PathVariable String jobId, Authentication authentication) {
        ArchiveRecoveryContextDTO result = service.recoveryContext(actor(authentication), jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.jobRevision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @GetMapping("/jobs/{jobId}/events")
    public JsonResult<List<cn.jia.chat.archive.maintenance.dto.ArchiveJobEventDTO>> jobEvents(
            @PathVariable String jobId, @RequestParam(defaultValue = "0") String after,
            @RequestParam(defaultValue = "100") int limit, Authentication authentication) {
        if (!after.matches("0|[1-9][0-9]{0,18}")) {
            throw new ArchiveMaintenanceException(400, "INVALID_REQUEST", "Invalid archive event cursor");
        }
        long cursor;
        try { cursor = Long.parseLong(after); }
        catch (NumberFormatException invalid) {
            throw new ArchiveMaintenanceException(400, "INVALID_REQUEST", "Invalid archive event cursor");
        }
        return JsonResult.success(service.jobEvents(actor(authentication), jobId, cursor, limit));
    }
    @GetMapping("/collections/{collectionId}/jobs")
    public JsonResult<ArchivePageDTO<ArchiveJobDTO>> jobs(@PathVariable String collectionId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit,
            Authentication authentication) {
        return JsonResult.success(service.listJobs(actor(authentication), collectionId,
                state, cursor, pageLimit(limit, 50)));
    }

    @GetMapping("/collections/{collectionId}/works")
    public ResponseEntity<JsonResult<ArchiveWorksDTO>> works(
            @PathVariable String collectionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit,
            Authentication authentication) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(service.listWorks(actor(authentication), collectionId,
                        cursor, pageLimit(limit, 100))));
    }

    @GetMapping("/drafts/{draftId}/blocks/{blockId}")
    public ResponseEntity<JsonResult<ArchiveDraftBlockDTO>> draftBlock(
            @PathVariable String draftId, @PathVariable String blockId,
            Authentication authentication) {
        ArchiveDraftBlockDTO result = service.getDraftBlock(actor(authentication), draftId, blockId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PutMapping("/drafts/{draftId}/blocks/{blockId}")
    public ResponseEntity<JsonResult<ArchiveDraftBlockDTO>> putDraftBlock(
            @PathVariable String draftId, @PathVariable String blockId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveDraftBlockDTO result = service.putDraftBlock(actor(authentication), draftId, blockId,
                key, ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveDraftBlockInput.class));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PatchMapping("/drafts/{draftId}")
    public ResponseEntity<JsonResult<ArchiveDraftDTO>> patchDraft(
            @PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveDraftDTO result = service.patchDraft(actor(authentication), draftId, key,
                ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveDraftPatchRequest.class));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @PostMapping("/drafts/{draftId}/validate")
    public ResponseEntity<JsonResult<ArchiveOperationAcceptedDTO>> validateDraft(
            @PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        ArchiveOperationAcceptedDTO result = service.validateDraft(actor(authentication), draftId,
                key, ArchiveHttpPreconditions.revision(ifMatch));
        return acceptedOperation(result);
    }

    @PostMapping("/drafts/{draftId}/publish")
    public ResponseEntity<JsonResult<ArchiveOperationAcceptedDTO>> publishDraft(
            @PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveOperationAcceptedDTO result = service.publishDraft(actor(authentication), draftId,
                key, ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchivePublishRequest.class));
        return acceptedOperation(result);
    }

    @GetMapping("/operations/{operationId}")
    public ResponseEntity<JsonResult<ArchiveAdminOperationDTO>> operation(
            @PathVariable String operationId, Authentication authentication) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(service.operation(actor(authentication), operationId)));
    }

    @GetMapping("/jobs/{jobId}/draft")
    public ResponseEntity<JsonResult<ArchiveDraftDTO>> draft(@PathVariable String jobId,
                                                               Authentication authentication) {
        ArchiveDraftDTO result = service.getDraft(actor(authentication), jobId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(JsonResult.success(result));
    }

    @PutMapping("/jobs/{jobId}/draft")
    public ResponseEntity<JsonResult<ArchiveDraftDTO>> updateDraft(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveDraftDTO result = service.updateDraft(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch), ArchiveStrictRequest.read(mapper, body, ArchiveDraftUpdateRequest.class));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(JsonResult.success(result));
    }

    @PostMapping("/jobs/{jobId}/validate")
    public JsonResult<ArchiveValidationDTO> validate(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return JsonResult.success(service.validate(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch)));
    }

    @GetMapping("/drafts/{draftId}/validation")
    public ResponseEntity<JsonResult<ArchiveValidationDTO>> validation(
            @PathVariable String draftId, Authentication authentication) {
        ArchiveValidationDTO result = service.validation(actor(authentication), draftId);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.draftRevision()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    @GetMapping("/operations/by-key")
    public ResponseEntity<JsonResult<ArchiveOperationDTO>> operationByKey(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication authentication) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(service.operationByKey(actor(authentication), key)));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<JsonResult<ArchiveJobDTO>> cancel(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        ArchiveJobDTO result = service.cancel(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch),
                ArchiveStrictRequest.read(mapper, body, ArchiveCancelRequest.class));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(JsonResult.success(result));
    }

    @PostMapping("/jobs/{jobId}/publish")
    public JsonResult<ArchivePublicationDTO> publish(@PathVariable String jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication) {
        return JsonResult.success(service.publish(actor(authentication), jobId, key,
                ArchiveHttpPreconditions.revision(ifMatch), ArchiveStrictRequest.read(mapper, body, ArchivePublishRequest.class)));
    }

    private String jobRepresentationEtag(JsonResult<ArchiveJobDTO> body) {
        try {
            return "\"r-" + ArchiveEtags.sha256(mapper.writeValueAsBytes(body)) + "\"";
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Archive job representation cannot be serialized", failure);
        }
    }

    private ResponseEntity<JsonResult<ArchiveOperationAcceptedDTO>> acceptedOperation(
            ArchiveOperationAcceptedDTO result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/archive/admin/v1/operations/" + result.operationId())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(JsonResult.success(result));
    }

    private int pageLimit(String value, int defaultValue) {
        if (value == null) return defaultValue;
        if (!value.matches("[1-9][0-9]{0,2}")) {
            throw new ArchiveMaintenanceException(400, "INVALID_ARCHIVE_PAGE_LIMIT",
                    "Archive page limit must be between 1 and 100");
        }
        int parsed = Integer.parseInt(value);
        if (parsed > 100) {
            throw new ArchiveMaintenanceException(400, "INVALID_ARCHIVE_PAGE_LIMIT",
                    "Archive page limit must be between 1 and 100");
        }
        return parsed;
    }

    static ArchiveActorScope actor(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            throw new ArchiveMaintenanceException(401, "AUTH_CONTEXT_INCOMPLETE",
                    "Archive authentication context is incomplete");
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object owner = claims.get("jiacn");
        Object client = claims.get("client_id");
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !exact(ownerJiacn, 50) || !exact(clientId, 50)) {
            throw new ArchiveMaintenanceException(401, "AUTH_CONTEXT_INCOMPLETE",
                    "Archive authentication context is incomplete");
        }
        return new ArchiveActorScope("0", clientId, ownerJiacn);
    }

    private static void requireIdempotencyKey(String key) {
        if (!exact(key, 100)) {
            throw new ArchiveMaintenanceException(428, "PRECONDITION_REQUIRED",
                    "Idempotency-Key is required");
        }
    }

    private static boolean exact(String value, int maxBytes) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
