package cn.jia.chat.api;

import cn.jia.chat.service.ChatBountyExecutionTerminationService;
import cn.jia.chat.service.ChatBountyExecutionTerminationService.*;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit owner abandonment, not a Provider stop, refund, or retry endpoint. */
@RestController
@RequestMapping("/chat/conversations/{conversationId}/requests/{requestId}/abandon-execution")
@ConditionalOnProperty(prefix="chat.bounty-media",name="enabled",havingValue="true")
public final class ChatBountyExecutionTerminationController {
    private static final ObjectMapper STRICT=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final TypeReference<Map<String,Object>> OBJECT=new TypeReference<>(){};
    private static final Set<String> KEYS=Set.of("stepId","executionId","expectedRequestStateVersion",
            "expectedStepStateVersion","reason");
    private final ChatBountyExecutionTerminationService service;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatBountyExecutionTerminationController(ChatBountyExecutionTerminationService service,
            HumanSenderIdentityResolver identities,TenantScopeResolver tenants) {
        this.service=Objects.requireNonNull(service);this.identities=Objects.requireNonNull(identities);
        this.tenants=Objects.requireNonNull(tenants);
    }

    @PostMapping
    public ResponseEntity<JsonResult<Receipt>> abandon(@PathVariable String conversationId,
            @PathVariable String requestId,@RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) String body,Authentication authentication) {
        Scope scope=scope(authentication,conversationId);
        return ok(service.abandon(scope,requestId,key,parse(body)));
    }

    @GetMapping
    public ResponseEntity<JsonResult<Receipt>> outcome(@PathVariable String conversationId,
            @PathVariable String requestId,@RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        return ok(service.get(scope(authentication,conversationId),requestId,key));
    }

    static Command parse(String body) {
        if(body==null||body.isBlank())throw new Failure(Reason.INVALID_REQUEST);
        try {
            Map<String,Object> value=STRICT.readValue(body,OBJECT);
            if(value==null||!KEYS.equals(value.keySet())
                    ||value.values().stream().anyMatch(v->!(v instanceof String)))
                throw new Failure(Reason.INVALID_REQUEST);
            return new Command((String)value.get("stepId"),(String)value.get("executionId"),
                    (String)value.get("expectedRequestStateVersion"),(String)value.get("expectedStepStateVersion"),
                    (String)value.get("reason"));
        }catch(Failure failure){throw failure;}catch(Exception invalid){throw new Failure(Reason.INVALID_REQUEST);}
    }

    private Scope scope(Authentication authentication,String conversationId) {
        try {
            String tenant=tenants.resolve(authentication);
            ServerResolvedSender sender=identities.resolve(EsContextHolder.getContext());
            if(sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type()))
                throw new Failure(Reason.NOT_FOUND_OR_FORBIDDEN);
            return new Scope(tenant,sender.jiacn(),sender.clientId(),conversationId);
        }catch(RuntimeException denied){throw new Failure(Reason.NOT_FOUND_OR_FORBIDDEN);}
    }

    private static ResponseEntity<JsonResult<Receipt>> ok(Receipt receipt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .body(JsonResult.success(receipt));
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<JsonResult<Void>> error(Failure failure) {
        HttpStatus status=switch(failure.reason()) {
            case INVALID_REQUEST->HttpStatus.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN->HttpStatus.NOT_FOUND;
            case CONFLICT->HttpStatus.CONFLICT;
            case UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;
        };
        return error(status,"EXECUTION_ABANDON_"+failure.reason());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(RuntimeException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"EXECUTION_ABANDON_UNAVAILABLE");
    }

    private static ResponseEntity<JsonResult<Void>> error(HttpStatus status,String code) {
        JsonResult<Void> result=JsonResult.failure(code,"Execution abandonment is unavailable");
        result.setStatus(status.value());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(result);
    }
}
