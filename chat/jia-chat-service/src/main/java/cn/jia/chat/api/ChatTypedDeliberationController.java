package cn.jia.chat.api;

import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.ChatTypedDeliberationService;
import cn.jia.chat.service.ChatTypedDiscussionAdmissionService;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix="chat.typed-deliberation",name="enabled",havingValue="true")
public final class ChatTypedDeliberationController {
    private final ChatTypedDiscussionAdmissionService admissions;
    private final ChatTypedDeliberationService typed;
    private final ChatDeliberationService deliberation;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatTypedDeliberationController(ChatTypedDiscussionAdmissionService admissions,
            ChatTypedDeliberationService typed, ChatDeliberationService deliberation,
            HumanSenderIdentityResolver identities, TenantScopeResolver tenants) {
        this.admissions=Objects.requireNonNull(admissions);this.typed=Objects.requireNonNull(typed);
        this.deliberation=Objects.requireNonNull(deliberation);this.identities=Objects.requireNonNull(identities);
        this.tenants=Objects.requireNonNull(tenants);
    }

    @PostMapping("/{conversationId}/interactions/discussion")
    public ResponseEntity<JsonResult<ChatTypedDeliberationWire.Accepted>> discuss(
            @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String key,
            @RequestBody(required=false) String rawBody,Authentication authentication) {
        if(!exact(key,100))throw invalid();Scope scope=scope(authentication);
        var receipt=admissions.admit(scope.tenantId(),scope.sender(),conversationId,key,
                ChatTypedDeliberationWire.parse(rawBody));
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(JsonResult.success(receipt));
    }

    @GetMapping("/{conversationId}/requests/{requestId}/typed-outcome")
    public ResponseEntity<JsonResult<Object>> outcome(
            @PathVariable String conversationId,@PathVariable String requestId,Authentication authentication) {
        Scope identity=scope(authentication);var request=deliberation.getRequest(identity.tenantId(),
                identity.sender().jiacn(),identity.sender().clientId(),requestId);
        if(!conversationId.equals(request.conversationId())||request.turns().size()!=1)throw unavailable();
        long generation=parse(request.conversationGeneration());long revision=parse(request.requestRevision());
        var storeScope=new ChatTypedDeliberationStore.Scope(identity.tenantId(),identity.sender().jiacn(),
                identity.sender().clientId(),conversationId,generation);
        var value=typed.read(storeScope,requestId,request.turns().getFirst().turnId(),revision);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .body(JsonResult.success(value));
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> error(ChatDeliberationException failure){HttpStatus status=switch(failure.reason()){
        case INVALID_REQUEST,INVALID_ROUTE,GAP->HttpStatus.BAD_REQUEST;case NOT_FOUND_OR_FORBIDDEN->HttpStatus.NOT_FOUND;
        case CONFLICT->HttpStatus.CONFLICT;case PERSISTENCE_ERROR->HttpStatus.SERVICE_UNAVAILABLE;};
        String message=status==HttpStatus.NOT_FOUND?"Typed discussion is unavailable":failure.getMessage();
        JsonResult<Void> result=JsonResult.failure("TYPED_DELIBERATION_"+failure.reason(),message);result.setStatus(status.value());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(result);}

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(RuntimeException failure) {
        JsonResult<Void> result=JsonResult.failure("TYPED_DELIBERATION_UNAVAILABLE",
                "Typed discussion is temporarily unavailable");
        result.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(result);
    }

    private Scope scope(Authentication authentication){try{String tenant=tenants.resolve(authentication);ServerResolvedSender sender=identities.resolve(EsContextHolder.getContext());if(sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type())||!exact(sender.jiacn(),50)||!exact(sender.clientId(),50))throw unavailable();return new Scope(tenant,sender);}catch(ChatDeliberationException e){throw e;}catch(RuntimeException denied){throw unavailable();}}
    private record Scope(String tenantId,ServerResolvedSender sender){}
    private static long parse(String value){try{return Long.parseLong(value);}catch(RuntimeException e){throw unavailable();}}
    private static boolean exact(String value,int max){return value!=null&&!value.isBlank()&&value.equals(value.strip())&&value.codePointCount(0,value.length())<=max&&value.codePoints().noneMatch(Character::isISOControl);}
    private static ChatDeliberationException invalid(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Typed discussion request is invalid");}
    private static ChatDeliberationException unavailable(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Typed discussion is unavailable");}
}
