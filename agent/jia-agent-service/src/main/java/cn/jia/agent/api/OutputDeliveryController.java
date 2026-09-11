package cn.jia.agent.api;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.dto.OutputDetailDTO;
import cn.jia.agent.output.dto.OutputCapabilitiesDTO;
import cn.jia.agent.output.dto.OutputDownloadDTO;
import cn.jia.agent.output.dto.OutputPageDTO;
import cn.jia.agent.output.dto.OutputSummaryDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/agent")
@ConditionalOnProperty(prefix="agent.output-delivery",name="enabled",havingValue="true")
public class OutputDeliveryController {
    private final OutputDeliveryService service;
    public OutputDeliveryController(OutputDeliveryService service) { this.service = service; }

    @GetMapping(value="/output-capabilities", produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputCapabilitiesDTO>> capabilities(
            Authentication authentication) {
        OutputHttpSupport.scope(authentication);
        return ok(service.capabilities());
    }

    @PostMapping(value="/tasks/{taskId}/artifacts", consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputSummaryDTO>> publish(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String taskId,
            @RequestBody byte[] body) {
        return ok(service.publish(bearer, key, OutputConstants.SOURCE_TASK, taskId,
                OutputHttpSupport.taskPublish(body)));
    }

    @GetMapping(value="/tasks/{taskId}/artifacts", produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputPageDTO>> list(@PathVariable String taskId,
            Authentication authentication, HttpServletRequest request) {
        OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);
        return ok(service.list(s.tenantId(),s.clientId(),s.jiacn(),OutputConstants.SOURCE_TASK,
                taskId,OutputHttpSupport.optionalQuery(request,"cursor"),
                OutputHttpSupport.optionalLimit(request)));
    }

    @GetMapping(value="/tasks/{taskId}/artifacts/{artifactId}/versions",
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputPageDTO>> versions(
            @PathVariable String taskId,@PathVariable String artifactId,
            Authentication authentication,HttpServletRequest request) {
        OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);
        return ok(service.listVersions(s.tenantId(),s.clientId(),s.jiacn(),
                OutputConstants.SOURCE_TASK,taskId,artifactId,
                OutputHttpSupport.optionalQuery(request,"cursor"),
                OutputHttpSupport.optionalLimit(request)));
    }

    @GetMapping(value="/tasks/{taskId}/artifacts/{artifactId}/versions/{version}",
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputDetailDTO>> detail(
            @PathVariable String taskId,@PathVariable String artifactId,@PathVariable String version,
            Authentication authentication) {
        OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);
        return ok(service.getVersion(s.tenantId(),s.clientId(),s.jiacn(),
                OutputConstants.SOURCE_TASK,taskId,artifactId,version));
    }

    @GetMapping("/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String taskId,
            @PathVariable String artifactId,@PathVariable String version,
            Authentication authentication) {
        OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);
        return download(service.downloadVersion(s.tenantId(),s.clientId(),s.jiacn(),
                OutputConstants.SOURCE_TASK,taskId,artifactId,version));
    }

    @ExceptionHandler(OutputDeliveryException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> delivery(OutputDeliveryException e,
            HttpServletRequest request){return OutputHttpEnvelope.error(request,e.code(),
            e.getMessage(),e.status(),e.retryable());}
    @ExceptionHandler(OutputAuthorizationException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> auth(OutputAuthorizationException e,
            HttpServletRequest request){int status="OUTPUT_AUTH_UNAUTHORIZED".equals(e.getCode())?401:403;
        return OutputHttpEnvelope.error(request,e.getCode(),"Output access is unavailable",status,false);}
    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<OutputHttpEnvelope.Error> badRequest(Exception e,HttpServletRequest request){
        return OutputHttpEnvelope.error(request,"OUTPUT_REQUEST_INVALID",
                "Invalid output request",400,false);}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> unsupported(
            HttpMediaTypeNotSupportedException e,HttpServletRequest request){
        return OutputHttpEnvelope.error(request,"OUTPUT_MIME_UNSUPPORTED",
                "Output MIME unsupported",415,false);}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<OutputHttpEnvelope.Error> unexpected(Exception e,HttpServletRequest request){
        return OutputHttpEnvelope.error(request,"OUTPUT_DELIVERY_UNAVAILABLE",
                "Output delivery unavailable",503,true);}

    private static <T> ResponseEntity<OutputHttpEnvelope.Success<T>> ok(T value) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(new OutputHttpEnvelope.Success<>(value));
    }
    private static ResponseEntity<StreamingResponseBody> download(OutputDownloadDTO d) {
        StreamingResponseBody body=out->{try(var in=d.stream()){in.transferTo(out);}};
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(d.fileName(),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(d.contentLength())
                .body(body);
    }
}
