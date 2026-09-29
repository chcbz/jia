package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Native runtime only. A lease token never passes through browser, ordinary workspace or WS routes. */
@RestController
@RequestMapping("/internal/agent/tasks")
public final class PersonalWorkspaceConversationRuntimeController {
    private static final String CACHE_CONTROL = "private, no-store";
    private final PersonalWorkspaceExecutionService executions;

    public PersonalWorkspaceConversationRuntimeController(PersonalWorkspaceExecutionService executions) {
        this.executions=Objects.requireNonNull(executions,"executions");
    }

    @GetMapping(value="/conversation-executions/commands",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationQueue> commands(HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        return ok(new ConversationQueue(executions.runtimeConversationCommands(scope(authentication),16)));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/lease",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ConversationLease> claim(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody ClaimRequest claim,HttpServletRequest request,
            Authentication authentication) {
        noQuery(request);
        if (claim==null || claim.commandId()==null || claim.messageId()==null) throw new BadRequest();
        return ok(executions.claimConversationStart(scope(authentication),taskId,runId,
                claim.commandId(),claim.messageId()));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/lease/renew",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ConversationLease> renew(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody FenceRequest request,HttpServletRequest servletRequest,
            Authentication authentication) {
        noQuery(servletRequest);
        return ok(executions.renewConversationLease(scope(authentication),taskId,runId,fence(request)));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/inputs",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ConversationInputSnapshot> inputs(
            @PathVariable String taskId,@PathVariable String runId,@RequestBody FenceRequest fence,
            HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        return ok(executions.conversationInputs(scope(authentication),taskId,runId,fence(fence)));
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/inputs/{inputRef}/content",
            consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> inputContent(@PathVariable String taskId,@PathVariable String runId,
            @PathVariable String inputRef,@RequestBody FenceRequest fence,
            HttpServletRequest request,Authentication authentication) {
        noQuery(request);
        var content=executions.conversationInputContent(scope(authentication),taskId,runId,
                fence(fence),inputRef);
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

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/failure",consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> failure(@PathVariable String taskId,
            @PathVariable String runId,@RequestBody FailRequest body,HttpServletRequest request,
            Authentication authentication) {
        noQuery(request);
        if (body==null || body.code()==null) throw new BadRequest();
        return ok(executions.failConversation(scope(authentication),taskId,runId,fence(body.fence()),body.code()));
    }

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
    public record ConversationQueue(List<PersonalWorkspaceExecutionService.ConversationRuntimeCommand> items) { }
    public record ClaimRequest(String commandId,String messageId) { }
    public record FenceRequest(long version,String token) { }
    public record CommitItem(String outputId,String sha256,Long length) { }
    public record CommitRequest(FenceRequest fence,List<CommitItem> outputs) { }
    public record FailRequest(FenceRequest fence,String code) { }
    public record ErrorBody(String code,String message) { }
    private static final class BadRequest extends RuntimeException { }
}
