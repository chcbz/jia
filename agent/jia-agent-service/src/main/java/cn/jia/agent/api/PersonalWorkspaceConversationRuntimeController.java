package cn.jia.agent.api;

import cn.jia.core.entity.JsonRequest;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.core.security.AllowSensitiveOutput;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Native runtime only. A lease token never passes through browser, ordinary workspace or WS routes. */
@RestController
@RequestMapping("/internal/agent/tasks")
public final class PersonalWorkspaceConversationRuntimeController {
    private static final String CACHE_CONTROL = "private, no-store";
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    private static final Set<String> CONTROLLED_START_FIELDS=Set.of("schemaVersion","commandId",
            "messageId","executionId","providerExecution","fence");
    private static final Set<String> CONTROLLED_START_V3_FIELDS=Set.of("schemaVersion","commandId",
            "messageId","executionId","operation","inputSnapshotDigest","providerExecution","fence");
    private static final Set<String> PROVIDER_EXECUTION_FIELDS=Set.of("providerLane","consentId",
            "bindingId","bindingEpoch","modelId","maxInputItems","maxOutboundRequestAttempts",
            "precallFenceVersion");
    private static final Set<String> FENCE_FIELDS=Set.of("version","token");
    private static final ObjectMapper STRICT_JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public record ProviderStartReceipt(boolean started) { }
    private final PersonalWorkspaceExecutionService executions;

    private final AgentRuntimeAuthenticationService runtimeAuthentication;
    public PersonalWorkspaceConversationRuntimeController(PersonalWorkspaceExecutionService executions,
            AgentRuntimeAuthenticationService runtimeAuthentication) {
        this.executions=Objects.requireNonNull(executions,"executions");
        this.runtimeAuthentication=Objects.requireNonNull(runtimeAuthentication,"runtimeAuthentication");
    }

    @GetMapping(value="/conversation-executions/commands",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<ConversationQueue>> commands(HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        var queue=new ConversationQueue(executions.runtimeConversationCommandViews(scope(authentication),16).stream()
                .map(value->value instanceof cn.jia.agent.service.SkillExecutionService.Command skill?SkillCommandResponse.from(skill):value).toList());
        return cn.jia.core.config.ApiJsonResponses.success(ok(runtimeAuthentication.withNativeFence(authentication,()->queue)));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/lease",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<LeaseResponse>> claim(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody JsonRequest<ClaimRequest> protocolRequest,HttpServletRequest request,
            Authentication authentication) {
        ClaimRequest claim = protocolRequest == null ? null : protocolRequest.data();
        noQuery(request);
        if (claim==null || claim.commandId()==null || claim.messageId()==null) throw new BadRequest();
        return cn.jia.core.config.ApiJsonResponses.success(ok(runtimeAuthentication.withNativeFence(authentication, () ->
                executions.claimConversationStart(scope(authentication),taskId,runId,claim.commandId(),claim.messageId()))), LeaseResponse::from);
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/lease/renew",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<LeaseResponse>> renew(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody JsonRequest<FenceRequest> protocolRequest,HttpServletRequest servletRequest,
            Authentication authentication) {
        FenceRequest request = protocolRequest == null ? null : protocolRequest.data();
        noQuery(servletRequest);
        return cn.jia.core.config.ApiJsonResponses.success(ok(runtimeAuthentication.withNativeFence(authentication, () ->
                executions.renewConversationLease(scope(authentication),taskId,runId,fence(request)))), LeaseResponse::from);
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/inputs",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<InputsResponse>> inputs(
            @PathVariable String taskId,@PathVariable String runId,@RequestBody JsonRequest<FenceRequest> protocolRequest,
            HttpServletRequest request,Authentication authentication) {
        FenceRequest fence = protocolRequest == null ? null : protocolRequest.data();
        noQuery(request);
        var inputs=skillCall(()->skills().inputs(scope(authentication),taskId,runId,fence(fence)));
        return cn.jia.core.config.ApiJsonResponses.success(ok(runtimeAuthentication.withNativeFence(authentication,()->inputs)), InputsResponse::from);
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/inputs/{inputRef}/content",
            consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> inputContent(@PathVariable String taskId,@PathVariable String runId,
            @PathVariable String inputRef,@RequestBody cn.jia.core.entity.JsonRequest<FenceRequest> protocolRequest,
            HttpServletRequest request,Authentication authentication) {
        FenceRequest fence = protocolRequest == null ? null : protocolRequest.data();
        noQuery(request);
        var loaded=skillCall(()->skills().inputContent(scope(authentication),taskId,runId,inputRef,fence(fence)));
        var content=runtimeAuthentication.withNativeFence(authentication,()->loaded);
        if (content==null || content.bytes()==null) throw new BadRequest();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=reference.bin")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.bytes().length).body(content.bytes());
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/outputs/{outputId}/content",
            consumes=MediaType.MULTIPART_FORM_DATA_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<NativeOutputWire.StagedOutputResponse>> stage(@PathVariable String taskId,
            @PathVariable String runId,@PathVariable String outputId,@RequestParam("file") MultipartFile file,
            @RequestParam("version") String version,@RequestParam("token") String token,
            @RequestParam("sha256") String sha256,@RequestParam("byteLength") String length,
            HttpServletRequest request,Authentication authentication) throws IOException {
        noQueryString(request);
        if(!request.getParameterMap().keySet().equals(Set.of("version","token","sha256","byteLength")))throw new BadRequest();
        var fence=fence(version,token);
        if (file==null || sha256==null || !sha256.matches("[0-9a-f]{64}")
                || length==null || !length.matches("0|[1-9][0-9]*")) throw new BadRequest();
        byte[] bytes=file.getBytes();
        // Check declared content before any storage side effect; service rechecks the fence under root lock.
        if (!length.equals(Long.toString(bytes.length)) || !sha256.equals(digest(bytes))) throw new BadRequest();
        return cn.jia.core.config.ApiJsonResponses.success(created(executions.stageConversationOutput(scope(authentication),taskId,runId,fence,
                outputId,file.getOriginalFilename(),file.getContentType(),bytes)), NativeOutputWire.StagedOutputResponse::from);
    }

    private cn.jia.agent.service.SkillExecutionService skills() {
        if(!(executions instanceof cn.jia.agent.service.SkillExecutionService skills))throw new IllegalStateException("Skill bridge unavailable");return skills;
    }
    private <T> T skillCall(java.util.function.Supplier<T> call) {
        try {return call.get();}catch(PersonalWorkspaceExecutionService.Failure failure){throw new ControlledStartFailure(failure);}
    }
    private static <T> T skillBody(String raw,Set<String> required,Class<T> type) {
        try {
            JsonNode body=STRICT_JSON.readTree(raw);
            if(body==null||!body.isObject()||!fields(body).equals(required))throw new BadRequest();
            if(body.has("fence")) {
                var fence=body.get("fence");if(fence==null||!fence.isObject()||!fields(fence).equals(FENCE_FIELDS)
                    ||!text(fence.get("version"))||!text(fence.get("token")))throw new BadRequest();
                var trustedFence=fence(fence.get("version").textValue(),fence.get("token").textValue());
                // Explicit wire-to-domain projection; the service still owns a Java long fence.
                ((tools.jackson.databind.node.ObjectNode) fence).put("version",trustedFence.version());
            }
            var projected=(tools.jackson.databind.node.ObjectNode)body;
            for(var entry:java.util.Map.of("snapshotSha256","snapshotDigest","effectSha256","effectDigest","resultSha256","resultDigest").entrySet()) {
                if(!projected.has(entry.getKey()))continue;
                JsonNode value=projected.remove(entry.getKey());
                if(!("resultSha256".equals(entry.getKey()) && value.isNull())
                        && (!text(value)||!value.textValue().matches("[a-f0-9]{64}")))throw new BadRequest();
                projected.set(entry.getValue(),value);
            }
            if(projected.has("artifacts")) {
                var artifacts=projected.get("artifacts");if(!artifacts.isArray())throw new BadRequest();
                for(var artifact:artifacts) {
                    if(!artifact.isObject()||!fields(artifact).equals(Set.of("outputId","sha256","byteLength"))
                            ||!text(artifact.get("byteLength")))throw new BadRequest();
                    ((tools.jackson.databind.node.ObjectNode)artifact).put("byteLength",decimalLength(artifact.get("byteLength").textValue()));
                }
            }
            return STRICT_JSON.treeToValue(projected,type);
        }catch(RuntimeException invalid){throw new BadRequest();}
    }
    @PostMapping(value="/{taskId}/runs/{runId}/conversation/effects/{effectId}/start",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<EffectReceiptResponse>> skillEffectStart(
            @PathVariable String taskId,@PathVariable String runId,@PathVariable String effectId,@cn.jia.core.annotation.ApiJsonData @RequestBody String raw,HttpServletRequest request,Authentication authentication) {
        noQuery(request);var command=skillBody(raw,Set.of("executionId","snapshotSha256","fence","effectSha256","providerBindingRevision"),cn.jia.agent.service.SkillExecutionService.EffectStart.class);
        return cn.jia.core.config.ApiJsonResponses.success(ok(skillCall(()->runtimeAuthentication.withNativeFence(authentication,()->skills().startEffect(scope(authentication),taskId,runId,effectId,command)))), EffectReceiptResponse::from);
    }
    @PostMapping(value="/{taskId}/runs/{runId}/conversation/effects/{effectId}/result",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<EffectReceiptResponse>> skillEffectResult(
            @PathVariable String taskId,@PathVariable String runId,@PathVariable String effectId,@cn.jia.core.annotation.ApiJsonData @RequestBody String raw,HttpServletRequest request,Authentication authentication) {
        noQuery(request);var command=skillBody(raw,Set.of("executionId","snapshotSha256","fence","effectSha256","providerBindingRevision","status","providerRequestId","resultSha256"),cn.jia.agent.service.SkillExecutionService.EffectResult.class);
        return cn.jia.core.config.ApiJsonResponses.success(ok(skillCall(()->runtimeAuthentication.withNativeFence(authentication,()->skills().recordEffect(scope(authentication),taskId,runId,effectId,command)))), EffectReceiptResponse::from);
    }
    @PostMapping(value="/{taskId}/runs/{runId}/conversation/resume",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<ResumeResponse>> skillResume(
            @PathVariable String taskId,@PathVariable String runId,@cn.jia.core.annotation.ApiJsonData @RequestBody String raw,HttpServletRequest request,Authentication authentication) {
        noQuery(request);var command=skillBody(raw,Set.of("executionId","snapshotSha256"),cn.jia.agent.service.SkillExecutionService.Resume.class);
        return cn.jia.core.config.ApiJsonResponses.success(ok(skillCall(()->runtimeAuthentication.withNativeFence(authentication,()->skills().resume(scope(authentication),taskId,runId,command)))), ResumeResponse::from);
    }
    @PostMapping(value="/{taskId}/runs/{runId}/conversation/result-commits/{manifestId}",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<ResultReceiptResponse>> skillResultCommit(
            @PathVariable String taskId,@PathVariable String runId,@PathVariable String manifestId,@cn.jia.core.annotation.ApiJsonData @RequestBody String raw,HttpServletRequest request,Authentication authentication) {
        noQuery(request);var command=skillBody(raw,Set.of("fence","snapshotSha256","data","artifacts","inputReceipts","effectDependencies"),cn.jia.agent.service.SkillExecutionService.ResultCommit.class);
        return cn.jia.core.config.ApiJsonResponses.success(ok(skillCall(()->skills().commitResult(scope(authentication),taskId,runId,manifestId,command))), ResultReceiptResponse::from);
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/failure",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<WorkspaceExecutionWire.Execution>> failure(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody JsonRequest<FailRequest> protocolRequest,HttpServletRequest request,
            Authentication authentication) {
        FailRequest body = protocolRequest == null ? null : protocolRequest.data();
        noQuery(request);
        if (body==null || body.code()==null) throw new BadRequest();
        return cn.jia.core.config.ApiJsonResponses.success(ok(runtimeAuthentication.withNativeFence(authentication, () ->
                executions.failConversation(scope(authentication),taskId,runId,fence(body.fence()),body.code()))), WorkspaceExecutionWire::from);
    }

    @ExceptionHandler(ControlledStartFailure.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> controlledUnavailable(ControlledStartFailure wrapped) {
        return switch (wrapped.failure.getReason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST,"BAD_REQUEST");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND,"CONVERSATION_EXECUTION_UNAVAILABLE");
            case TASK_CONFLICT, IDEMPOTENCY_CONFLICT, GRANT_CHANGED, GRANT_REVOKED,
                    OUTPUT_CONFLICT, OUTPUT_MISSING -> error(HttpStatus.CONFLICT,
                            "CONVERSATION_EXECUTION_CONFLICT");
            case CAPABILITY_UNAVAILABLE, STORAGE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                            "CONVERSATION_EXECUTION_UNAVAILABLE");
        };
    }
    /** Preserve the pre-v2 runtime error contract for every existing endpoint. */
    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> unavailable(PersonalWorkspaceExecutionService.Failure ignored) {
        return error(HttpStatus.NOT_FOUND,"CONVERSATION_EXECUTION_UNAVAILABLE");
    }
    @ExceptionHandler({BadRequest.class,IllegalArgumentException.class,IOException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> malformed(Exception ignored) { return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> unexpected(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"CONVERSATION_EXECUTION_UNAVAILABLE");
    }

    private static boolean integral(JsonNode value,int expected) {
        return value!=null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue()==expected;
    }
    private static boolean safeIntegral(JsonNode value,long min) {
        return value!=null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue()>=min && value.longValue()<=MAX_SAFE_INTEGER;
    }
    private static boolean text(JsonNode value) { return value!=null && value.isTextual(); }
    private static boolean safeTextId(JsonNode value,int max) {
        return text(value) && value.textValue().matches(
                "[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}");
    }
    private static Set<String> fields(JsonNode value) {
        Set<String> result=new HashSet<>();value.propertyNames().forEach(result::add);return result;
    }

    private static PersonalWorkspaceExecutionService.RuntimeScope scope(Authentication authentication) {
        if (!(authentication instanceof AgentRuntimeAuthentication runtime) || !runtime.isAuthenticated())
            throw new BadRequest();
        var trusted=runtime.getPrincipal();
        return new PersonalWorkspaceExecutionService.RuntimeScope(trusted.tenantId(),trusted.clientId(),
                trusted.ownerJiacn(),trusted.agentId(),trusted.runtimeInstanceId());
    }
    private static PersonalWorkspaceExecutionService.ConversationFence fence(FenceRequest value) {
        if (value==null) throw new BadRequest();
        return fence(value.version(),value.token());
    }
    private static PersonalWorkspaceExecutionService.ConversationFence fence(String version,String token) {
        if (version==null || !version.matches("[1-9][0-9]{0,18}") || token==null
                || !token.matches("[0-9a-fA-F-]{36}")) throw new BadRequest();
        try { return new PersonalWorkspaceExecutionService.ConversationFence(Long.parseLong(version),token); }
        catch (NumberFormatException bad) { throw new BadRequest(); }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getParameterMap()!=null && !request.getParameterMap().isEmpty()) throw new BadRequest();
    }
    private static void noQueryString(HttpServletRequest request) {
        if (request.getQueryString()!=null && !request.getQueryString().isEmpty()) throw new BadRequest();
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).header("X-Content-Type-Options","nosniff")
            .contentType(MediaType.APPLICATION_JSON).body(body); }
    private static <T> ResponseEntity<T> created(T body) { return ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).header("X-Content-Type-Options","nosniff")
            .contentType(MediaType.APPLICATION_JSON).body(body); }
    private static ResponseEntity<cn.jia.core.entity.JsonResult<Void>> error(HttpStatus status,String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8))
                .body(cn.jia.core.entity.JsonResult.<Void>failure(code,"Native conversation execution unavailable"));
    }
    public record ConversationQueue(List<?> items) { }
    public record ConversationQueueV3(List<PersonalWorkspaceExecutionService.ControlledConversationRuntimeCommandV3> items) { }
    private static long decimalLength(String value) {
        if(value==null||!value.matches("0|[1-9][0-9]{0,18}"))throw new BadRequest();
        try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw new BadRequest();}
    }
    public record EffectReceiptResponse(int schemaVersion,String disposition,String status,String providerRequestId,String resultSha256) {
        static EffectReceiptResponse from(cn.jia.agent.service.SkillExecutionService.EffectReceipt value) {
            return new EffectReceiptResponse(value.schemaVersion(),value.disposition(),value.status(),value.providerRequestId(),value.resultDigest());
        }
    }
    public record ResultReceiptResponse(String manifestId,String state,String phase,String resultSha256) {
        static ResultReceiptResponse from(cn.jia.agent.service.SkillExecutionService.ResultReceipt value) {
            return new ResultReceiptResponse(value.manifestId(),value.state(),value.phase(),value.resultDigest());
        }
    }
    public record SkillCommandResponse(int executionProtocolVersion,String executionId,String taskId,String runId,
            String conversationId,String conversationGeneration,String commandId,String messageId,List<cn.jia.agent.service.SkillExecutionService.AuthorizedSkill> skills,
            String snapshotSha256,java.util.Map<String,Object> parameters,String instruction,List<String> inputRefs,java.util.Map<String,Object> outputs,String toolPolicyRef) {
        static SkillCommandResponse from(cn.jia.agent.service.SkillExecutionService.Command value) {
            return new SkillCommandResponse(value.executionProtocolVersion(),value.executionId(),value.taskId(),value.runId(),
                    value.conversationId(),value.conversationGeneration(),value.commandId(),value.messageId(),value.skills(),
                    value.snapshotDigest(),value.parameters(),value.instruction(),value.inputRefs(),value.outputs(),value.toolPolicyRef());
        }
    }
    public record ClaimRequest(String commandId,String messageId) { }
    public record FenceRequest(String version,String token) { }
    public record LeaseResponse(String executionId,String version,String token,long expiresAt) {
        static LeaseResponse from(PersonalWorkspaceExecutionService.ConversationLease lease) {
            return new LeaseResponse(lease.executionId(),Long.toString(lease.version()),lease.token(),lease.expiresAt());
        }
    }
    public record InputsResponse(int schemaVersion,String executionId,String leaseVersion,String snapshotSha256,
            boolean noReferencedMaterials,List<java.util.Map<String,Object>> inputs) {
        static InputsResponse from(cn.jia.agent.service.SkillExecutionService.Inputs value) {
            return new InputsResponse(value.schemaVersion(),value.executionId(),Long.toString(value.leaseVersion()),
                    value.snapshotDigest(),value.noReferencedMaterials(),value.inputs().stream().map(input->{
                        var wire=new java.util.LinkedHashMap<String,Object>(input);
                        Object length=wire.get("byteLength");
                        if(length instanceof Long || length instanceof Integer) wire.put("byteLength",Long.toString(((Number)length).longValue()));
                        else if(!(length instanceof String text)||!text.matches("0|[1-9][0-9]*"))throw new IllegalStateException("Invalid private input length");
                        return java.util.Map.copyOf(wire);
                    }).toList());
        }
    }
    public record ResumeResponse(LeaseResponse lease,String phase,List<String> allowedWork,java.util.Map<String,Object> resultManifest,java.util.Map<String,Object> effects,String turnFailureCode) {
        static ResumeResponse from(cn.jia.agent.service.SkillExecutionService.ResumeReceipt value) {
            return new ResumeResponse(value.lease()==null?null:LeaseResponse.from(value.lease()),
                    value.phase(),value.allowedWork(),value.resultManifest(),value.effects().entrySet().stream().collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,entry->{
                        var effect=new java.util.LinkedHashMap<String,Object>();
                        effect.putAll((java.util.Map<String,Object>)entry.getValue());
                        effect.put("effectSha256",effect.remove("effectDigest"));effect.put("resultSha256",effect.remove("resultDigest"));
                        return effect;
                    })),value.turnFailureCode());
        }
    }
    public record CommitItem(String outputId,String sha256,Long length) { }
    public record CommitOutput(String outputId,String sha256,String byteLength) { }
    public record CommitRequest(FenceRequest fence,List<CommitOutput> outputs) { }
    public record ResultRecoveryRequest(Integer schemaVersion,String executionId,String commandId,
            String messageId,String inputSnapshotDigest,List<CommitItem> outputs) { }
    public record FailRequest(FenceRequest fence,String code) { }
    public record ErrorBody(String code,String message) { }
    private static final class BadRequest extends RuntimeException { }
    private static final class ControlledStartFailure extends RuntimeException {
        private final PersonalWorkspaceExecutionService.Failure failure;
        private ControlledStartFailure(PersonalWorkspaceExecutionService.Failure failure) {
            super(failure);this.failure=failure;
        }
    }
}
