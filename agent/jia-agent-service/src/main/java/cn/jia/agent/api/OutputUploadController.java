package cn.jia.agent.api;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputUploadException;
import cn.jia.agent.output.OutputUploadService;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.output.dto.OutputUploadCreateDTO;
import cn.jia.agent.output.dto.OutputUploadDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@RestController
@RequestMapping("/agent/output-uploads")
@ConditionalOnProperty(prefix="agent.output-delivery",name="enabled",havingValue="true")
public class OutputUploadController {
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> CREATE_FIELDS=Set.of("runId","source","name","size","sha256","mime"),SOURCE_FIELDS=Set.of("type","id");
    private final OutputUploadService service;
    public OutputUploadController(OutputUploadService service){this.service=service;}

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputUploadDTO>> create(@RequestHeader(HttpHeaders.AUTHORIZATION)String bearer,@RequestHeader("Idempotency-Key")String key,@RequestBody byte[] bytes){if(bytes.length>8192)throw bad();JsonNode n=parse(bytes);exactFields(n,CREATE_FIELDS);JsonNode source=n.get("source");exactFields(source,SOURCE_FIELDS);return ok(service.create(bearer,key,new OutputUploadCreateDTO(text(n,"runId"),new OutputSourceDTO(text(source,"type"),text(source,"id")),text(n,"name"),text(n,"size"),text(n,"sha256"),text(n,"mime"))),HttpStatus.OK);}
    @PutMapping(value="/{uploadId}/content",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputUploadDTO>> put(@RequestHeader(HttpHeaders.AUTHORIZATION)String bearer,@PathVariable String uploadId,HttpServletRequest request)throws java.io.IOException{return ok(service.put(bearer,uploadId,request.getInputStream()),HttpStatus.OK);}
    @PostMapping(value="/{uploadId}/complete",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputUploadDTO>> complete(@RequestHeader(HttpHeaders.AUTHORIZATION)String bearer,@RequestHeader("Idempotency-Key")String key,@PathVariable String uploadId){OutputUploadDTO result=service.complete(bearer,uploadId,key);return ok(result,"VERIFYING".equals(result.state())?HttpStatus.ACCEPTED:HttpStatus.OK);}
    @GetMapping(value="/{uploadId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<OutputUploadDTO>> status(@RequestHeader(HttpHeaders.AUTHORIZATION)String bearer,@PathVariable String uploadId){return ok(service.status(bearer,uploadId),HttpStatus.OK);}

    @ExceptionHandler(OutputUploadException.class) public ResponseEntity<OutputHttpEnvelope.Error> upload(OutputUploadException e,HttpServletRequest r){return OutputHttpEnvelope.error(r,e.code(),e.getMessage(),e.status(),!e.terminal());}
    @ExceptionHandler(OutputAuthorizationException.class) public ResponseEntity<OutputHttpEnvelope.Error> auth(OutputAuthorizationException e,HttpServletRequest r){return OutputHttpEnvelope.error(r,e.getCode(),"Output access is unavailable","OUTPUT_AUTH_UNAUTHORIZED".equals(e.getCode())?401:403,false);}
    @ExceptionHandler({MissingRequestHeaderException.class,HttpMessageNotReadableException.class}) public ResponseEntity<OutputHttpEnvelope.Error> binding(Exception e,HttpServletRequest r){return OutputHttpEnvelope.error(r,"OUTPUT_REQUEST_INVALID","Invalid output request",400,false);}
    @ExceptionHandler(Exception.class) public ResponseEntity<OutputHttpEnvelope.Error> unexpected(Exception e,HttpServletRequest r){return OutputHttpEnvelope.error(r,"OUTPUT_DELIVERY_UNAVAILABLE","Output delivery unavailable",503,true);}
    private static JsonNode parse(byte[] b){try{JsonNode n=JSON.readTree(b);if(n==null||!n.isObject())throw bad();return n;}catch(Exception e){throw bad();}}
    private static void exactFields(JsonNode n,Set<String> fields){if(n==null||!n.isObject())throw bad();Set<String> actual=new java.util.HashSet<>(n.propertyNames());if(!actual.equals(fields))throw bad();}
    private static String text(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isString())throw bad();return v.asText();}
    private static OutputUploadException bad(){return new OutputUploadException("OUTPUT_REQUEST_INVALID","Invalid output request",400);}
    private static <T>ResponseEntity<OutputHttpEnvelope.Success<T>> ok(T data,HttpStatus status){return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store").body(new OutputHttpEnvelope.Success<>(data));}
}
