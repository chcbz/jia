package cn.jia.chat.archive.maintenance.http;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftBlockInput;
import cn.jia.chat.archive.maintenance.dto.ArchiveOperationAcceptedDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftUpdateRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveNativePublicationDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeContextDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeFailureRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeResultDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveRuntimeStartRequest;
import cn.jia.chat.archive.maintenance.dto.ArchivePublicationDTO;
import cn.jia.chat.archive.maintenance.dto.ArchivePublishRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveValidationDTO;
import cn.jia.chat.archive.maintenance.model.ArchiveRuntimeScope;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/internal/archive/v1/jobs/{jobId}/runs/{runId}")
public class ArchiveNativeController {
    private static final Set<String> DRAFT_FIELDS = Set.of("blocks", "excludedSourceRanges");
    private static final Set<String> BLOCK_FIELDS = Set.of(
            "blockType", "blockKey", "ordinal", "title", "titleSourceRanges", "paragraphs");
    private static final Set<String> PARAGRAPH_FIELDS = Set.of("ordinal", "text", "sourceRanges");

    private static final Set<String> RANGE_FIELDS = Set.of("startByte", "endByte");
    private static final Set<String> EXCLUSION_FIELDS = Set.of("startByte", "endByte", "reason");

    private final ArchiveMaintenanceService service;
    private final ObjectMapper mapper;

    public ArchiveNativeController(ArchiveMaintenanceService service, ObjectMapper mapper) {
        this.service = Objects.requireNonNull(service, "service");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @PostMapping("/start")
    public ArchiveNativeResponse<ArchiveRuntimeResultDTO> start(@PathVariable String jobId,
            @PathVariable String runId, @RequestBody byte[] body, Authentication authentication,
            HttpServletRequest request) {
        ArchiveRuntimeStartRequest claim = ArchiveStrictRequest.read(mapper, body,
                ArchiveRuntimeStartRequest.class);
        return ArchiveNativeResponse.success(service.runtimeStart(runtime(authentication, request), jobId, runId, claim));
    }

    @PostMapping("/failure")
    public ArchiveNativeResponse<ArchiveRuntimeResultDTO> failure(@PathVariable String jobId,
            @PathVariable String runId, @RequestBody byte[] body, Authentication authentication,
            HttpServletRequest request) {
        ArchiveRuntimeFailureRequest failure = ArchiveStrictRequest.read(mapper, body,
                ArchiveRuntimeFailureRequest.class);
        return ArchiveNativeResponse.success(service.runtimeFailure(runtime(authentication, request), jobId, runId, failure));
    }

    @GetMapping("/result")
    public ArchiveNativeResponse<ArchiveRuntimeResultDTO> result(@PathVariable String jobId,
            @PathVariable String runId, Authentication authentication, HttpServletRequest request) {
        return ArchiveNativeResponse.success(service.runtimeResult(runtime(authentication, request), jobId, runId));
    }

    @GetMapping("/context")
    public ArchiveNativeResponse<ArchiveRuntimeContextDTO> context(@PathVariable String jobId,
            @PathVariable String runId, Authentication authentication, HttpServletRequest request) {
        return ArchiveNativeResponse.success(service.runtimeContext(runtime(authentication, request), jobId, runId));
    }

    @GetMapping("/sources/{sourceId}/content")
    public ResponseEntity<byte[]> sourceContent(@PathVariable String jobId, @PathVariable String runId,
            @PathVariable String sourceId, Authentication authentication, HttpServletRequest request) {
        ArchiveRuntimeContextDTO context = service.runtimeContext(runtime(authentication, request), jobId, runId);
        if (!sourceId.equals(context.sourceId())) {
            throw new ArchiveMaintenanceException(404, "ARCHIVE_RESOURCE_NOT_FOUND", "Archive source is not available");
        }
        byte[] bytes = service.runtimeSourceContent(runtime(authentication, request), jobId, runId, sourceId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Archive-Source-Sha256", context.sourceSha256())
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(bytes.length))
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8").body(bytes);
    }
    @GetMapping("/draft")
    public ResponseEntity<ArchiveNativeResponse<ArchiveDraftDTO>> draft(@PathVariable String jobId,
            @PathVariable String runId, Authentication authentication, HttpServletRequest request) {
        ArchiveDraftDTO result = service.runtimeDraft(runtime(authentication, request), jobId, runId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(ArchiveNativeResponse.success(result));
    }

    @PutMapping("/blocks/{blockKey}")
    public ResponseEntity<ArchiveNativeResponse<ArchiveDraftDTO>> putBlock(@PathVariable String jobId,
            @PathVariable String runId, @PathVariable String blockKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication, HttpServletRequest servletRequest) {
        ArchiveDraftUpdateRequest request = blockRequest(ArchiveStrictRequest.tree(mapper, body), blockKey);
        ArchiveDraftDTO result = service.runtimePutBlock(runtime(authentication, servletRequest), jobId, runId,
                blockKey, key, ArchiveHttpPreconditions.revision(ifMatch), request);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, ArchiveHttpPreconditions.etag(result.revision()))
                .body(ArchiveNativeResponse.success(result));
    }

    @PostMapping("/validate")
    public ResponseEntity<ArchiveNativeResponse<ArchiveOperationAcceptedDTO>> validate(
            @PathVariable String jobId, @PathVariable String runId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication, HttpServletRequest request) {
        ArchiveOperationAcceptedDTO accepted = service.runtimeValidate(runtime(authentication, request),
                jobId, runId, key, ArchiveHttpPreconditions.revision(ifMatch));
        String location = "/internal/archive/v1/jobs/" + jobId + "/runs/" + runId
                + "/validation?operationId=" + accepted.operationId();
        return ResponseEntity.status(202).header(HttpHeaders.LOCATION, location)
                .body(ArchiveNativeResponse.accepted(accepted));
    }

    @GetMapping("/validation")
    public ArchiveNativeResponse<ArchiveValidationDTO> validation(@PathVariable String jobId,
            @PathVariable String runId, Authentication authentication, HttpServletRequest request) {
        return ArchiveNativeResponse.success(service.runtimeValidation(runtime(authentication, request),
                jobId, runId, validationOperationId(request)));
    }

    @PostMapping("/publish")
    public ArchiveNativeResponse<ArchiveNativePublicationDTO> publish(@PathVariable String jobId,
            @PathVariable String runId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody byte[] body, Authentication authentication, HttpServletRequest request) {
        return ArchiveNativeResponse.success(new ArchiveNativePublicationDTO(
                service.runtimePublish(runtime(authentication, request), jobId, runId, key,
                        ArchiveHttpPreconditions.revision(ifMatch),
                        ArchiveStrictRequest.read(mapper, body, ArchivePublishRequest.class))));
    }

    private ArchiveRuntimeScope runtime(Authentication authentication, HttpServletRequest request) {
        if (!(authentication instanceof AgentRuntimeAuthentication runtime) || !authentication.isAuthenticated()) {
            throw new ArchiveMaintenanceException(401, "RUNTIME_UNAUTHENTICATED",
                    "Runtime authentication context is incomplete");
        }
        AgentRuntimeAuthentication.Scope scope = runtime.getPrincipal();
        return new ArchiveRuntimeScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                scope.agentId(), scope.runtimeInstanceId(), single(request, "X-Archive-Grant-Ref"),
                single(request, "X-Archive-Execution-Ref"), single(request, "X-Archive-Command-Id"),
                positive(single(request, "X-Archive-Command-Attempt")),
                positive(single(request, "X-Archive-Execution-Epoch")));
    }

    private static String single(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw invalidRuntimeProof();
        String value = values.getFirst();
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) throw invalidRuntimeProof();
        return value;
    }
    private static long positive(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (RuntimeException invalid) { throw invalidRuntimeProof(); }
    }
    private static ArchiveMaintenanceException invalidRuntimeProof() {
        return new ArchiveMaintenanceException(401, "RUNTIME_UNAUTHENTICATED",
                "Runtime execution proof is incomplete");
    }

    private ArchiveDraftUpdateRequest blockRequest(JsonNode body, String blockKey) {
        requireExactObjectFields(body, DRAFT_FIELDS);
        JsonNode blocks = body.get("blocks");
        if (blocks == null || !blocks.isArray() || blocks.size() != 1) bad();
        validateBlock(blocks.get(0));
        validateExclusions(body.get("excludedSourceRanges"));
        try {
            ArchiveDraftUpdateRequest request = mapper.convertValue(body, ArchiveDraftUpdateRequest.class);
            ArchiveDraftBlockInput block = request.blocks().getFirst();
            if (!Objects.equals(blockKey, block.blockKey())) bad();
            return request;
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw invalidNative();
        }
    }

    private String validationOperationId(HttpServletRequest request) {
        var parameters = request.getParameterMap();
        if (parameters.isEmpty()) return null;
        if (parameters.size() != 1 || !parameters.containsKey("operationId")) bad();
        String[] values = parameters.get("operationId");
        if (values == null || values.length != 1 || values[0] == null
                || !values[0].matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) bad();
        return values[0];
    }

    private void validateBlock(JsonNode node) {
        requireObjectFields(node, BLOCK_FIELDS);
        JsonNode paragraphs = node.get("paragraphs");
        if (paragraphs == null || !paragraphs.isArray()) bad();
        validateRanges(node.get("titleSourceRanges"));
        for (JsonNode paragraph : paragraphs) {
            requireObjectFields(paragraph, PARAGRAPH_FIELDS);
            validateRanges(paragraph.get("sourceRanges"));
        }
    }

    private void validateRanges(JsonNode ranges) {
        if (ranges == null || !ranges.isArray()) bad();
        for (JsonNode range : ranges) requireObjectFields(range, RANGE_FIELDS);
    }

    private void validateExclusions(JsonNode exclusions) {
        if (exclusions == null || !exclusions.isArray()) bad();
        for (JsonNode exclusion : exclusions) requireObjectFields(exclusion, EXCLUSION_FIELDS);
    }

    private void requireExactObjectFields(JsonNode node, Set<String> expected) {
        requireObjectFields(node, expected);
        if (node.size() != expected.size()) bad();
        for (String field : expected) if (!node.has(field)) bad();
    }

    private void requireObjectFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) bad();
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) bad();
        }
    }

    private void bad() { throw invalidNative(); }

    private ArchiveMaintenanceException invalidNative() {
        return new ArchiveMaintenanceException(400, "INVALID_REQUEST",
                "Native request contains unknown or invalid fields");
    }
}
