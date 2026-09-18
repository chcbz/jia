package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
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
import java.util.List;
import java.util.Objects;

/** Narrow native-only file lane. Browser JWTs can never establish the required runtime principal. */
@RestController
@RequestMapping("/internal/agent/tasks")
public class PersonalWorkspaceRuntimeFileController {
    private static final String CACHE_CONTROL = "private, no-store";
    private final PersonalWorkspaceExecutionService service;
    public PersonalWorkspaceRuntimeFileController(PersonalWorkspaceExecutionService service) { this.service=Objects.requireNonNull(service,"service"); }

    @GetMapping(value = "/workspace-executions/commands", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuntimeQueueView> queuedCommands(HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        return json(new RuntimeQueueView(service.runtimeQueuedCommands(scope(authentication), 16)));
    }

    @GetMapping(value = "/{taskId}/runs/{runId}/inputs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersonalWorkspaceExecutionService.RuntimeInput>> inputs(@PathVariable String taskId,
            @PathVariable String runId, HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request); return json(service.runtimeInputs(scope(authentication), taskId, runId));
    }
    @GetMapping("/{taskId}/runs/{runId}/inputs/{inputRef}/content")
    public ResponseEntity<byte[]> content(@PathVariable String taskId, @PathVariable String runId,
            @PathVariable String inputRef, HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request); PersonalWorkspaceExecutionService.RuntimeContent content=service.runtimeInputContent(scope(authentication),taskId,runId,inputRef);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(safe(content.filename()),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(content.contentMimeType())).contentLength(content.bytes().length).body(content.bytes());
    }
    @PostMapping(value = "/{taskId}/runs/{runId}/outputs/{outputId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.StagedOutput> stage(@PathVariable String taskId,
            @PathVariable String runId, @PathVariable String outputId, @RequestParam("file") MultipartFile file,
            @RequestParam("taskId") String declaredTaskId, @RequestParam("runId") String declaredRunId,
            @RequestParam("outputId") String declaredOutputId, @RequestParam("sha256") String declaredSha256,
            @RequestParam("length") String declaredLength, HttpServletRequest request,
            Authentication authentication) throws IOException {
        requireNoQueryString(request); if(file==null || !taskId.equals(declaredTaskId) || !runId.equals(declaredRunId)
                || !outputId.equals(declaredOutputId) || !declaredSha256.matches("[0-9a-f]{64}")
                || !declaredLength.matches("0|[1-9][0-9]*")) throw new RequestFailure();
        PersonalWorkspaceExecutionService.StagedOutput staged = service.stageOutput(scope(authentication),taskId,runId,outputId,file.getOriginalFilename(),file.getContentType(),file.getBytes());
        if (!declaredSha256.equals(staged.sha256()) || !declaredLength.equals(Long.toString(staged.byteLength()))) throw new RequestFailure();
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).body(staged);
    }
    @PostMapping(value = "/{taskId}/runs/{runId}/output-commits/{manifestId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.CommitView> commit(@PathVariable String taskId,
            @PathVariable String runId, @PathVariable String manifestId, @RequestBody CommitRequest request,
            HttpServletRequest servletRequest, Authentication authentication) {
        requireNoQuery(servletRequest); if(request==null||request.outputs()==null||request.outputs().size()!=1)throw new RequestFailure();
        CommitOutput output=request.outputs().getFirst(); if(output==null||output.outputId()==null||output.sha256()==null||output.length()==null||!output.sha256().matches("[0-9a-f]{64}")||output.length()<0)throw new RequestFailure();
        return json(service.commitOutputs(scope(authentication),taskId,runId,manifestId,List.of(
                new PersonalWorkspaceExecutionService.OutputDeclaration(output.outputId(), output.sha256(), output.length()))));
    }
    @PostMapping(value = "/{taskId}/runs/{runId}/failure", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> failure(@PathVariable String taskId,
            @PathVariable String runId, @RequestBody FailureRequest request, HttpServletRequest servletRequest,
            Authentication authentication) {
        requireNoQuery(servletRequest);
        if (request == null || request.code() == null) throw new RequestFailure();
        return json(service.fail(scope(authentication), taskId, runId, request.code()));
    }
    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<ErrorBody> failure(PersonalWorkspaceExecutionService.Failure ignored) { return error(HttpStatus.NOT_FOUND,"RUNTIME_FILE_NOT_FOUND","Runtime file access is unavailable"); }
    @ExceptionHandler({RequestFailure.class,IllegalArgumentException.class,IOException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) { return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","Invalid runtime file request"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored) { return error(HttpStatus.SERVICE_UNAVAILABLE,"RUNTIME_FILE_UNAVAILABLE","Runtime file access is temporarily unavailable"); }

    private static PersonalWorkspaceExecutionService.RuntimeScope scope(Authentication authentication) {
        if(!(authentication instanceof AgentRuntimeAuthentication runtime)||!runtime.isAuthenticated())throw new RequestFailure();
        AgentRuntimeAuthentication.Scope scope=runtime.getPrincipal();
        return new PersonalWorkspaceExecutionService.RuntimeScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.agentId(),scope.runtimeInstanceId());
    }
    private static void requireNoQuery(HttpServletRequest request){if(request.getParameterMap()!=null&&!request.getParameterMap().isEmpty())throw new RequestFailure();}
    private static void requireNoQueryString(HttpServletRequest request){if(request.getQueryString()!=null&&!request.getQueryString().isEmpty())throw new RequestFailure();}
    private static String safe(String value){return value==null?"output.bin":value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceAll("^\\.+","").trim();}
    private static <T> ResponseEntity<T> json(T body){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(MediaType.APPLICATION_JSON).body(body);}
    private static ResponseEntity<ErrorBody> error(HttpStatus status,String code,String message){return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8)).body(new ErrorBody(code,message));}
    public record RuntimeQueueView(List<PersonalWorkspaceExecutionService.RuntimeQueuedCommand> items) { }
    public record CommitRequest(List<CommitOutput> outputs) { }
    public record CommitOutput(String outputId,String sha256,Long length) { }
    public record FailureRequest(String code) { }
    public record ErrorBody(String code,String message) { }
    private static final class RequestFailure extends RuntimeException { }
}
