package cn.jia.chat.api;

import cn.jia.agent.api.OutputHttpEnvelope;
import cn.jia.agent.api.OutputHttpSupport;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.dto.OutputDetailDTO;
import cn.jia.agent.output.dto.OutputDownloadDTO;
import cn.jia.agent.output.dto.OutputPageDTO;
import cn.jia.agent.output.dto.OutputSummaryDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
@RequestMapping("/chat/conversations/{conversationId}/outputs")
@ConditionalOnProperty(prefix="agent.output-delivery",name="enabled",havingValue="true")
public class ConversationOutputController {
    private final OutputDeliveryService service;
    public ConversationOutputController(OutputDeliveryService service){this.service=service;}

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputSummaryDTO>> publish(
            @RequestHeader(HttpHeaders.AUTHORIZATION)String bearer,
            @RequestHeader("Idempotency-Key")String key,@PathVariable String conversationId,
            @RequestBody byte[] body){return ok(service.publish(bearer,key,
            OutputConstants.SOURCE_CONVERSATION,conversationId,OutputHttpSupport.chatPublish(body)));}

    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputPageDTO>> list(
            @PathVariable String conversationId,Authentication authentication,HttpServletRequest request){
        OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);return ok(service.list(
                s.tenantId(),s.clientId(),s.jiacn(),OutputConstants.SOURCE_CONVERSATION,
                conversationId,OutputHttpSupport.optionalQuery(request,"cursor"),
                OutputHttpSupport.optionalLimit(request)));}

    @GetMapping(value="/{outputId}/versions",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputPageDTO>> versions(
            @PathVariable String conversationId,@PathVariable String outputId,
            Authentication authentication,HttpServletRequest request){
        OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);return ok(service.listVersions(
                s.tenantId(),s.clientId(),s.jiacn(),OutputConstants.SOURCE_CONVERSATION,
                conversationId,outputId,OutputHttpSupport.optionalQuery(request,"cursor"),
                OutputHttpSupport.optionalLimit(request)));}

    @GetMapping(value="/{outputId}/versions/{version}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputDetailDTO>> detail(
            @PathVariable String conversationId,@PathVariable String outputId,@PathVariable String version,
            Authentication authentication){OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);
        return ok(service.getVersion(s.tenantId(),s.clientId(),s.jiacn(),
                OutputConstants.SOURCE_CONVERSATION,conversationId,outputId,version));}

    @GetMapping("/{outputId}/versions/{version}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String conversationId,
            @PathVariable String outputId,@PathVariable String version,Authentication authentication){
        OutputHttpSupport.Scope s=OutputHttpSupport.scope(authentication);return download(service.downloadVersion(
                s.tenantId(),s.clientId(),s.jiacn(),OutputConstants.SOURCE_CONVERSATION,
                conversationId,outputId,version));}

    @ExceptionHandler(OutputDeliveryException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> delivery(OutputDeliveryException e,HttpServletRequest r){
        return OutputHttpEnvelope.error(r,e.code(),e.getMessage(),e.status(),e.retryable());}
    @ExceptionHandler(OutputAuthorizationException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> auth(OutputAuthorizationException e,HttpServletRequest r){
        int status="OUTPUT_AUTH_UNAUTHORIZED".equals(e.getCode())?401:403;
        return OutputHttpEnvelope.error(r,e.getCode(),"Output access is unavailable",status,false);}
    @ExceptionHandler({MissingRequestHeaderException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<OutputHttpEnvelope.Error> badRequest(Exception e,HttpServletRequest r){
        return OutputHttpEnvelope.error(r,"OUTPUT_REQUEST_INVALID","Invalid output request",400,false);}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> unsupported(HttpMediaTypeNotSupportedException e,HttpServletRequest r){
        return OutputHttpEnvelope.error(r,"OUTPUT_MIME_UNSUPPORTED","Output MIME unsupported",415,false);}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<OutputHttpEnvelope.Error> unexpected(Exception e,HttpServletRequest r){
        return OutputHttpEnvelope.error(r,"OUTPUT_DELIVERY_UNAVAILABLE","Output delivery unavailable",503,true);}
    private static <T>ResponseEntity<OutputHttpEnvelope.Success<T>> ok(T value){return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(new OutputHttpEnvelope.Success<>(value));}
    private static ResponseEntity<StreamingResponseBody> download(OutputDownloadDTO d){
        StreamingResponseBody body=out->{try(var in=d.stream()){in.transferTo(out);}};
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(d.fileName(),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(d.contentLength()).body(body);}
}
