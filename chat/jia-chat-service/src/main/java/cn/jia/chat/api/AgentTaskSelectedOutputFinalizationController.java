package cn.jia.chat.api;

import cn.jia.chat.service.ChatSelectedOutputFinalizationService;
import cn.jia.chat.service.ChatSelectedOutputFinalizationStore;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/agent/tasks")
@ConditionalOnProperty(prefix="chat.selected-output-finalization",name="enabled",havingValue="true")
public final class AgentTaskSelectedOutputFinalizationController {
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    // Exact compacted-wire maximum: fixed JSON syntax + two safe-integer literals + every
    // accepted UTF-16 string unit represented by its longest six-byte JSON Unicode escape.
    // Insignificant JSON whitespace is discarded while reading and therefore needs no invented cap.
    static final int MAX_BODY=derivedMaxBody();
    private static final Set<String> ROOT=Set.of("expectedTaskVersion","expectedAssignmentRevision","conversationId","summary","selectedOutputs");
    private static final Set<String> ITEM=Set.of("requestId","stepId","outputId","sha256","title","purpose");
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ChatSelectedOutputFinalizationService service;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;
    public AgentTaskSelectedOutputFinalizationController(ChatSelectedOutputFinalizationService service,
            HumanSenderIdentityResolver identities,TenantScopeResolver tenants){
        this.service=Objects.requireNonNull(service);this.identities=Objects.requireNonNull(identities);this.tenants=Objects.requireNonNull(tenants);
    }

    @PostMapping(value="/{taskId}/finalizations",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<ChatSelectedOutputFinalizationService.Receipt>> submit(@PathVariable String taskId,
            @RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request,Authentication authentication){
        Scope scope=scope(authentication);identifier(taskId,100);idempotencyKey(key);noQuery(request);
        var receipt=service.submit(scope.value(),taskId,key,parse(read(request)));
        HttpStatus status="completed".equals(receipt.state())?HttpStatus.OK:"failed".equals(receipt.state())?failureStatus(receipt.errorCode()):HttpStatus.ACCEPTED;
        return response(status,receipt);
    }

    /** Must remain before the operation mapping; it performs no orchestration or writes. */
    @GetMapping(value="/{taskId}/finalizations/request",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<ChatSelectedOutputFinalizationService.Receipt>> byRequest(@PathVariable String taskId,
            @RequestHeader(value="Idempotency-Key",required=false)String key,HttpServletRequest request,Authentication authentication){
        Scope scope=scope(authentication);identifier(taskId,100);idempotencyKey(key);noQuery(request);
        return response(HttpStatus.OK,service.getByKey(scope.value(),taskId,key));
    }

    @GetMapping(value="/{taskId}/finalizations/{operationId}",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<ChatSelectedOutputFinalizationService.Receipt>> byOperation(@PathVariable String taskId,
            @PathVariable String operationId,HttpServletRequest request,Authentication authentication){
        Scope scope=scope(authentication);identifier(taskId,100);identifier(operationId,100);noQuery(request);
        return response(HttpStatus.OK,service.getByOperation(scope.value(),taskId,operationId));
    }

    @ExceptionHandler(ChatSelectedOutputFinalizationStore.Missing.class)
    public ResponseEntity<JsonResult<Void>> missing(){return error(HttpStatus.NOT_FOUND,"FINALIZATION_NOT_FOUND","Finalization is unavailable");}
    @ExceptionHandler(ChatSelectedOutputFinalizationStore.Conflict.class)
    public ResponseEntity<JsonResult<Void>> conflict(){return error(HttpStatus.CONFLICT,"FINALIZATION_IDEMPOTENCY_CONFLICT","Idempotency key conflicts with another request");}
    @ExceptionHandler({IllegalArgumentException.class,RequestFailure.class})
    public ResponseEntity<JsonResult<Void>> bad(){return error(HttpStatus.BAD_REQUEST,"FINALIZATION_BAD_REQUEST","Invalid finalization request");}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> unexpected(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"FINALIZATION_INTERNAL_ERROR","Finalization is unavailable");}

    private Scope scope(Authentication authentication){
        String tenant=tenants.resolve(authentication);ServerResolvedSender sender=identities.resolve(EsContextHolder.getContext());
        if(sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type()))throw new RequestFailure();
        identifier(sender.jiacn(),50);identifier(sender.clientId(),50);return new Scope(new ChatSelectedOutputFinalizationService.Scope(tenant,sender.clientId(),sender.jiacn()));
    }
    private static ChatSelectedOutputFinalizationService.Command parse(byte[] body){
        try{
            JsonNode root=JSON.readTree(body);if(!exactObject(root,ROOT))throw new RequestFailure();
            long taskVersion=version(root,"expectedTaskVersion"),assignment=version(root,"expectedAssignmentRevision");
            String conversation=text(root,"conversationId",100),summary=text(root,"summary",4000);
            JsonNode source=root.get("selectedOutputs");if(!source.isArray()||source.isEmpty()||source.size()>99)throw new RequestFailure();
            List<ChatSelectedOutputFinalizationService.Selection> selected=new ArrayList<>();Set<String> unique=new HashSet<>();
            for(JsonNode item:source){if(!exactObject(item,ITEM))throw new RequestFailure();
                var value=new ChatSelectedOutputFinalizationService.Selection(text(item,"requestId",100),text(item,"stepId",100),
                        text(item,"outputId",100),sha(item,"sha256"),text(item,"title",255),text(item,"purpose",255));
                if(!unique.add(value.requestId()+"\0"+value.stepId()+"\0"+value.outputId()))throw new RequestFailure();selected.add(value);}
            return new ChatSelectedOutputFinalizationService.Command(taskVersion,assignment,conversation,summary,List.copyOf(selected));
        }catch(RequestFailure e){throw e;}catch(Exception e){throw new RequestFailure();}
    }
    private static byte[] read(HttpServletRequest request){
        try(InputStream in=request.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(MAX_BODY,8192))){
            boolean inString=false,escaped=false,pendingWhitespace=false;int value;
            while((value=in.read())>=0){
                if(!inString&&(value==' '||value=='\t'||value=='\r'||value=='\n')){
                    pendingWhitespace=true;continue;
                }
                if(pendingWhitespace){writeBounded(out,' ');pendingWhitespace=false;}
                writeBounded(out,value);
                if(inString){
                    if(escaped)escaped=false;
                    else if(value=='\\')escaped=true;
                    else if(value=='"')inString=false;
                }else if(value=='"')inString=true;
            }
            if(out.size()==0||inString||escaped)throw new RequestFailure();return out.toByteArray();
        }catch(RequestFailure e){throw e;}catch(Exception e){throw new RequestFailure();}
    }
    private static void writeBounded(ByteArrayOutputStream out,int value){
        if(out.size()>=MAX_BODY)throw new RequestFailure();out.write(value);
    }
    private static int derivedMaxBody(){
        String root="{\"expectedTaskVersion\":9007199254740991,\"expectedAssignmentRevision\":9007199254740991,"
                +"\"conversationId\":\"\",\"summary\":\"\",\"selectedOutputs\":[]}";
        String item="{\"requestId\":\"\",\"stepId\":\"\",\"outputId\":\"\",\"sha256\":\"\","
                +"\"title\":\"\",\"purpose\":\"\"}";
        int rootValues=6*(100+4_000);
        int itemValues=6*(100+100+100+255+255)+64;
        // Empty [] already contributes two bytes; populated form adds each item and 98 commas.
        int significant=root.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+rootValues-2
                +99*(item.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+itemValues)+98+2;
        // Normalization retains at most one external whitespace byte before each significant byte,
        // so legal pretty-printed JSON is accepted without retaining an unbounded whitespace run.
        return Math.addExact(Math.multiplyExact(significant,2),1);
    }
    private static boolean exactObject(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||node.size()!=fields.size())return false;for(String f:fields)if(!node.has(f))return false;return true;}
    private static String text(JsonNode n,String field,int max){JsonNode v=n.get(field);if(v==null||!v.isTextual())throw new RequestFailure();String s=v.textValue();if(s==null||s.isBlank()||s.length()>max||!s.equals(s.strip())||s.indexOf('\0')>=0||s.codePoints().anyMatch(Character::isISOControl))throw new RequestFailure();return s;}
    private static String sha(JsonNode n,String field){String s=text(n,field,64);if(!s.matches("[0-9a-f]{64}"))throw new RequestFailure();return s;}
    private static long version(JsonNode n,String field){JsonNode v=n.get(field);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0||v.longValue()>MAX_SAFE_INTEGER)throw new RequestFailure();return v.longValue();}
    private static void identifier(String value,int max){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}"))throw new RequestFailure();}
    private static void idempotencyKey(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,159}"))throw new RequestFailure();}
    private static void noQuery(HttpServletRequest request){if(request.getQueryString()!=null)throw new RequestFailure();}
    private static HttpStatus failureStatus(String code){if(code==null)return HttpStatus.INTERNAL_SERVER_ERROR;if(code.contains("BAD_REQUEST"))return HttpStatus.BAD_REQUEST;if(code.contains("NOT_FOUND")||code.contains("SOURCE_UNAVAILABLE"))return HttpStatus.NOT_FOUND;if(code.contains("CONFLICT")||code.contains("GRANT_CHANGED")
                ||"FINALIZATION_DELIVERY_CHANGES_REQUESTED".equals(code))return HttpStatus.CONFLICT;if(code.contains("STORAGE"))return HttpStatus.SERVICE_UNAVAILABLE;return HttpStatus.INTERNAL_SERVER_ERROR;}
    private static <T> ResponseEntity<JsonResult<T>> response(HttpStatus status,T value){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(JsonResult.success(value));}
    private static ResponseEntity<JsonResult<Void>> error(HttpStatus status,String code,String message){JsonResult<Void> body=JsonResult.failure(code,message);body.setStatus(status.value());return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(body);}
    private record Scope(ChatSelectedOutputFinalizationService.Scope value) { }
    private static final class RequestFailure extends RuntimeException { }
}
