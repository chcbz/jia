package cn.jia.chat.api;

import cn.jia.chat.service.ChatBountyTopicService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/chat/conversations")
public class ChatBountyTopicController {
    private final ChatBountyTopicService topics;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;
    public ChatBountyTopicController(ChatBountyTopicService topics, HumanSenderIdentityResolver identities,
            TenantScopeResolver tenants) { this.topics=topics;this.identities=identities;this.tenants=tenants; }

    @PostMapping("/{sourceConversationId}/topics")
    public ResponseEntity<JsonResult<ChatBountyTopicService.Receipt>> create(
            @PathVariable String sourceConversationId,@RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonRequest<Map<String,Object>> request,Authentication authentication) {
        Object value=request==null||request.data()==null?null:request.data().get("expectedConversationGeneration");
        if(!(value instanceof String generation)||!generation.matches("[1-9][0-9]{0,18}"))throw invalid();
        long expected;
        try { expected=Long.parseLong(generation); } catch(NumberFormatException error) { throw invalid(); }
        return ok(topics.create(tenants.resolve(authentication),sender(),sourceConversationId,key,expected));
    }
    @GetMapping("/{sourceConversationId}/topics/{operationKey}")
    public ResponseEntity<JsonResult<ChatBountyTopicService.Receipt>> get(
            @PathVariable String sourceConversationId,@PathVariable String operationKey,Authentication authentication) {
        return ok(topics.get(tenants.resolve(authentication),sender(),sourceConversationId,operationKey));
    }
    private ServerResolvedSender sender() {
        try { return identities.resolve(EsContextHolder.getContext()); }
        catch(IllegalStateException failure) { throw new ChatDeliberationException(
                ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Topic is unavailable"); }
    }
    private static ResponseEntity<JsonResult<ChatBountyTopicService.Receipt>> ok(ChatBountyTopicService.Receipt receipt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .body(JsonResult.success(receipt));
    }
    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> error(ChatDeliberationException failure) {
        HttpStatus status=switch(failure.reason()) {
            case INVALID_REQUEST,INVALID_ROUTE,GAP->HttpStatus.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN->HttpStatus.NOT_FOUND;
            case CONFLICT->HttpStatus.CONFLICT;
            case PERSISTENCE_ERROR->HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
                JsonResult.failure("BOUNTY_TOPIC_"+failure.reason(),failure.getMessage()));
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            org.springframework.transaction.TransactionException.class, IllegalStateException.class})
    public ResponseEntity<JsonResult<Void>> unavailable(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(JsonResult.failure("BOUNTY_TOPIC_UNAVAILABLE","Topic service is temporarily unavailable"));
    }
    private static ChatDeliberationException invalid() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Invalid topic request");
    }
}
