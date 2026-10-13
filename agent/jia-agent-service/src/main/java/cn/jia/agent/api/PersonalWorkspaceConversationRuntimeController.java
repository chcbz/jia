package cn.jia.agent.api;

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
    public ResponseEntity<ConversationQueue> commands(HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        var queue=new ConversationQueue(executions.runtimeConversationCommandViews(scope(authentication),16));
        return ok(runtimeAuthentication.withNativeFence(authentication,()->queue));
    }

    @GetMapping(value="/conversation-executions/controlled-image-v3-commands",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationQueueV3> controlledV3Commands(HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        var queue=new ConversationQueueV3(executions.runtimeControlledImageV3Commands(scope(authentication),16));
        return ok(runtimeAuthentication.withNativeFence(authentication,()->queue));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/lease",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ConversationLease> claim(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody ClaimRequest claim,HttpServletRequest request,
            Authentication authentication) {
        noQuery(request);
        if (claim==null || claim.commandId()==null || claim.messageId()==null) throw new BadRequest();
        return ok(runtimeAuthentication.withNativeFence(authentication, () ->
                executions.claimConversationStart(scope(authentication),taskId,runId,claim.commandId(),claim.messageId())));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/lease/renew",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ConversationLease> renew(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody FenceRequest request,HttpServletRequest servletRequest,
            Authentication authentication) {
        noQuery(servletRequest);
        return ok(runtimeAuthentication.withNativeFence(authentication, () ->
                executions.renewConversationLease(scope(authentication),taskId,runId,fence(request))));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/provider-start",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProviderStartReceipt> providerStart(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody FenceRequest request,
            HttpServletRequest servletRequest,Authentication authentication) {
        noQuery(servletRequest);
        runtimeAuthentication.withNativeFence(authentication,()->{
            executions.beginConversationProviderStart(scope(authentication),taskId,runId,fence(request));
            return null;
        });
        return ok(new ProviderStartReceipt(true));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/provider-start-controlled-image",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ControlledProviderStartReceipt>
            controlledProviderStart(@PathVariable String taskId,@PathVariable String runId,
            @RequestBody(required=false) String rawBody,HttpServletRequest request,
            Authentication authentication) {
        noQuery(request);
        try {
            return ok(executions.beginControlledConversationProviderStart(
                    scope(authentication),taskId,runId,controlledStart(rawBody)));
        } catch (PersonalWorkspaceExecutionService.Failure failure) {
            throw new ControlledStartFailure(failure);
        }
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/provider-start-controlled-image-v3",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ControlledProviderStartReceiptV3>
            controlledProviderStartV3(@PathVariable String taskId,@PathVariable String runId,
            @RequestBody(required=false) String rawBody,HttpServletRequest request,Authentication authentication) {
        noQuery(request);try{return ok(executions.beginControlledConversationProviderStartV3(scope(authentication),taskId,runId,controlledStartV3(rawBody)));}
        catch(PersonalWorkspaceExecutionService.Failure failure){throw new ControlledStartFailure(failure);}
    }

    // This native-only DTO contains authorized source IDs/hashes, never credentials or file bytes.
    // Bean-to-Map sanitization loses the source union's NON_NULL serialization contract.
    // Keep the exact wire shape without weakening runtime authentication, fencing, or other routes.
    @AllowSensitiveOutput(reason="Native authenticated V3 input metadata requires exact source union fields; no secrets")
    @PostMapping(value="/{taskId}/runs/{runId}/conversation/inputs-v3",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ConversationInputSnapshotV3> inputsV3(
            @PathVariable String taskId,@PathVariable String runId,@RequestBody FenceRequest fence,
            HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        var inputs=executions.conversationInputsV3(scope(authentication),taskId,runId,fence(fence));
        return ok(runtimeAuthentication.withNativeFence(authentication,()->inputs));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/inputs",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ConversationInputSnapshot> inputs(
            @PathVariable String taskId,@PathVariable String runId,@RequestBody FenceRequest fence,
            HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        var inputs=executions.conversationInputs(scope(authentication),taskId,runId,fence(fence));
        return ok(runtimeAuthentication.withNativeFence(authentication,()->inputs));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/inputs/{inputRef}/content",
            consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> inputContent(@PathVariable String taskId,@PathVariable String runId,
            @PathVariable String inputRef,@RequestBody FenceRequest fence,
            HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        var loaded=executions.conversationInputContent(scope(authentication),taskId,runId,
                fence(fence),inputRef);
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
    public ResponseEntity<PersonalWorkspaceExecutionService.StagedOutput> stage(@PathVariable String taskId,
            @PathVariable String runId,@PathVariable String outputId,@RequestParam("file") MultipartFile file,
            @RequestParam("version") String version,@RequestParam("token") String token,
            @RequestParam("sha256") String sha256,@RequestParam("length") String length,
            HttpServletRequest request,Authentication authentication) throws IOException {
        noQueryString(request);
        var fence=fence(version,token);
        if (file==null || sha256==null || !sha256.matches("[0-9a-f]{64}")
                || length==null || !length.matches("0|[1-9][0-9]*")) throw new BadRequest();
        byte[] bytes=file.getBytes();
        // Check declared content before any storage side effect; service rechecks the fence under root lock.
        if (!length.equals(Long.toString(bytes.length)) || !sha256.equals(digest(bytes))) throw new BadRequest();
        return created(executions.stageConversationOutput(scope(authentication),taskId,runId,fence,
                outputId,file.getOriginalFilename(),file.getContentType(),bytes));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/output-commits/{manifestId}",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.CommitView> commit(@PathVariable String taskId,
            @PathVariable String runId,@PathVariable String manifestId,@RequestBody CommitRequest body,
            HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        if (body==null || body.outputs()==null || body.outputs().size()!=1) throw new BadRequest();
        var item=body.outputs().getFirst();
        if (item==null || item.outputId()==null || item.sha256()==null
                || !item.sha256().matches("[0-9a-f]{64}") || item.length()==null || item.length()<0)
            throw new BadRequest();
        return ok(executions.commitConversationOutput(scope(authentication),taskId,runId,
                fence(body.fence()),manifestId,List.of(new PersonalWorkspaceExecutionService.OutputDeclaration(
                        item.outputId(),item.sha256(),item.length()))));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/result-commits/{manifestId}",
            consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.CommitView> recoverStaged(
            @PathVariable String taskId,@PathVariable String runId,@PathVariable String manifestId,
            @RequestBody ResultRecoveryRequest body,HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        if (body==null || !Integer.valueOf(1).equals(body.schemaVersion())
                || body.outputs()==null || body.outputs().size()!=1) throw new BadRequest();
        var item=body.outputs().getFirst();
        if (item==null || item.length()==null || item.length()<0) throw new BadRequest();
        return ok(executions.recoverStagedConversationOutput(scope(authentication),taskId,runId,manifestId,
                new PersonalWorkspaceExecutionService.ConversationResultRecovery(body.executionId(),
                        body.commandId(),body.messageId(),body.inputSnapshotDigest(),List.of(
                            new PersonalWorkspaceExecutionService.OutputDeclaration(item.outputId(),
                                    item.sha256(),item.length())))));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/result-commits/{manifestId}",
            consumes=MediaType.MULTIPART_FORM_DATA_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.CommitView> recoverUploaded(
            @PathVariable String taskId,@PathVariable String runId,@PathVariable String manifestId,
            @RequestParam("proof") String proof,@RequestParam("file") MultipartFile file,
            HttpServletRequest request,Authentication authentication) throws IOException {
        noQueryString(request);
        var command=resultRecovery(proof);
        if (file==null) throw new BadRequest();
        byte[] bytes=file.getBytes();
        var output=command.outputs().getFirst();
        if (output.byteLength()!=bytes.length || !output.sha256().equals(digest(bytes))) throw new BadRequest();
        return ok(executions.recoverConversationOutput(scope(authentication),taskId,runId,manifestId,
                command,file.getOriginalFilename(),file.getContentType(),bytes));
    }

    private static PersonalWorkspaceExecutionService.ConversationResultRecovery resultRecovery(String raw) {
        try {
            JsonNode value=STRICT_JSON.readTree(raw);
            if (value==null || !value.isObject() || !fields(value).equals(Set.of("schemaVersion",
                    "executionId","commandId","messageId","inputSnapshotDigest","outputs"))
                    || !integral(value.get("schemaVersion"),1)) throw new BadRequest();
            for (String key:List.of("executionId","commandId","messageId"))
                if (!safeTextId(value.get(key),100)) throw new BadRequest();
            var digest=value.get("inputSnapshotDigest");
            if (!text(digest) || !digest.textValue().matches("[a-f0-9]{64}")) throw new BadRequest();
            var outputs=value.get("outputs");
            if (!outputs.isArray() || outputs.size()!=1) throw new BadRequest();
            var output=outputs.get(0);
            if (!output.isObject() || !fields(output).equals(Set.of("outputId","sha256","length"))
                    || !text(output.get("outputId")) || !"output_1".equals(output.get("outputId").textValue())
                    || !text(output.get("sha256")) || !output.get("sha256").textValue().matches("[a-f0-9]{64}")
                    || !safeIntegral(output.get("length"),1)) throw new BadRequest();
            return new PersonalWorkspaceExecutionService.ConversationResultRecovery(
                    value.get("executionId").textValue(),value.get("commandId").textValue(),
                    value.get("messageId").textValue(),digest.textValue(),List.of(
                        new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",
                            output.get("sha256").textValue(),output.get("length").longValue())));
        } catch (RuntimeException invalid) { throw new BadRequest(); }
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/failure",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> failure(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody FailRequest body,HttpServletRequest request,
            Authentication authentication) {
        noQuery(request);
        if (body==null || body.code()==null) throw new BadRequest();
        return ok(runtimeAuthentication.withNativeFence(authentication, () ->
                executions.failConversation(scope(authentication),taskId,runId,fence(body.fence()),body.code())));
    }

    @ExceptionHandler(ControlledStartFailure.class)
    public ResponseEntity<ErrorBody> controlledUnavailable(ControlledStartFailure wrapped) {
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
    public ResponseEntity<ErrorBody> unavailable(PersonalWorkspaceExecutionService.Failure ignored) {
        return error(HttpStatus.NOT_FOUND,"CONVERSATION_EXECUTION_UNAVAILABLE");
    }
    @ExceptionHandler({BadRequest.class,IllegalArgumentException.class,IOException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) { return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"CONVERSATION_EXECUTION_UNAVAILABLE");
    }

    private static PersonalWorkspaceExecutionService.ControlledProviderStart controlledStart(String raw) {
        if (raw==null || raw.isBlank()) throw new BadRequest();
        try {
            JsonNode root=STRICT_JSON.readTree(raw);
            if (root==null || !root.isObject() || !fields(root).equals(CONTROLLED_START_FIELDS)
                    || !integral(root.get("schemaVersion"),2)
                    || !text(root.get("commandId")) || !text(root.get("messageId"))
                    || !text(root.get("executionId"))) throw new BadRequest();
            JsonNode provider=root.get("providerExecution");
            if (provider==null || !provider.isObject()
                    || !fields(provider).equals(PROVIDER_EXECUTION_FIELDS)
                    || !safeTextId(provider.get("providerLane"),50)
                    || !text(provider.get("consentId"))
                    || !provider.get("consentId").textValue().matches("consent_[0-9a-f]{32}")
                    || !safeTextId(provider.get("bindingId"),100)
                    || !text(provider.get("bindingEpoch"))
                    || !safeTextId(provider.get("modelId"),100)
                    || !integral(provider.get("maxInputItems"),16)
                    || !integral(provider.get("maxOutboundRequestAttempts"),1)
                    || !integral(provider.get("precallFenceVersion"),1)) throw new BadRequest();
            String epoch=provider.get("bindingEpoch").textValue();
            if (!epoch.matches("[1-9][0-9]*")) throw new BadRequest();
            long epochNumber=Long.parseLong(epoch);
            if (epochNumber>MAX_SAFE_INTEGER || !Long.toString(epochNumber).equals(epoch))
                throw new BadRequest();
            JsonNode fence=root.get("fence");
            if (fence==null || !fence.isObject() || !fields(fence).equals(FENCE_FIELDS)
                    || !safeIntegral(fence.get("version"),1) || !text(fence.get("token")))
                throw new BadRequest();
            String token=fence.get("token").textValue();
            if (!token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new BadRequest();
            var descriptor=new PersonalWorkspaceExecutionService.ProviderExecution(
                    provider.get("providerLane").textValue(),provider.get("consentId").textValue(),
                    provider.get("bindingId").textValue(),epoch,provider.get("modelId").textValue(),
                    16,1,1);
            var exactFence=new PersonalWorkspaceExecutionService.ConversationFence(
                    fence.get("version").longValue(),token);
            return new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                    root.get("commandId").textValue(),root.get("messageId").textValue(),
                    root.get("executionId").textValue(),descriptor,exactFence);
        } catch (BadRequest failure) { throw failure; }
        catch (Exception invalid) { throw new BadRequest(); }
    }
    private static PersonalWorkspaceExecutionService.ControlledProviderStartV3 controlledStartV3(String raw) {
        if(raw==null||raw.isBlank())throw new BadRequest();try{JsonNode root=STRICT_JSON.readTree(raw);
            if(root==null||!root.isObject()||!fields(root).equals(CONTROLLED_START_V3_FIELDS)||!integral(root.get("schemaVersion"),3)||!safeTextId(root.get("commandId"),100)||!safeTextId(root.get("messageId"),100)||!safeTextId(root.get("executionId"),100)||!text(root.get("operation"))||!Set.of("GENERATE_IMAGE","EDIT_IMAGE").contains(root.get("operation").textValue())||!text(root.get("inputSnapshotDigest"))||!root.get("inputSnapshotDigest").textValue().matches("[0-9a-f]{64}"))throw new BadRequest();
            JsonNode provider=root.get("providerExecution");if(provider==null||!provider.isObject()||!fields(provider).equals(PROVIDER_EXECUTION_FIELDS)||!safeTextId(provider.get("providerLane"),50)||!text(provider.get("consentId"))||!provider.get("consentId").textValue().matches("consent_[0-9a-f]{32}")||!safeTextId(provider.get("bindingId"),100)||!text(provider.get("bindingEpoch"))||!provider.get("bindingEpoch").textValue().matches("[1-9][0-9]*")||!safeTextId(provider.get("modelId"),100)||!integral(provider.get("maxInputItems"),16)||!integral(provider.get("maxOutboundRequestAttempts"),1)||!integral(provider.get("precallFenceVersion"),1))throw new BadRequest();long epoch=Long.parseLong(provider.get("bindingEpoch").textValue());if(epoch>MAX_SAFE_INTEGER||!Long.toString(epoch).equals(provider.get("bindingEpoch").textValue()))throw new BadRequest();
            JsonNode f=root.get("fence");if(f==null||!f.isObject()||!fields(f).equals(FENCE_FIELDS)||!safeIntegral(f.get("version"),1)||!text(f.get("token")))throw new BadRequest();String token=f.get("token").textValue();if(!token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new BadRequest();
            return new PersonalWorkspaceExecutionService.ControlledProviderStartV3(3,root.get("commandId").textValue(),root.get("messageId").textValue(),root.get("executionId").textValue(),root.get("operation").textValue(),root.get("inputSnapshotDigest").textValue(),new PersonalWorkspaceExecutionService.ProviderExecution(provider.get("providerLane").textValue(),provider.get("consentId").textValue(),provider.get("bindingId").textValue(),Long.toString(epoch),provider.get("modelId").textValue(),16,1,1),new PersonalWorkspaceExecutionService.ConversationFence(f.get("version").longValue(),token));
        }catch(BadRequest e){throw e;}catch(Exception e){throw new BadRequest();}}

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
        return fence(Long.toString(value.version()),value.token());
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
    private static ResponseEntity<ErrorBody> error(HttpStatus status,String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8))
                .body(new ErrorBody(code,"Native conversation execution unavailable"));
    }
    public record ConversationQueue(List<? extends PersonalWorkspaceExecutionService.ConversationCommandView> items) { }
    public record ConversationQueueV3(List<PersonalWorkspaceExecutionService.ControlledConversationRuntimeCommandV3> items) { }
    public record ClaimRequest(String commandId,String messageId) { }
    public record FenceRequest(long version,String token) { }
    public record CommitItem(String outputId,String sha256,Long length) { }
    public record CommitRequest(FenceRequest fence,List<CommitItem> outputs) { }
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
