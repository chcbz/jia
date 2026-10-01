package cn.jia.chat.api;

import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.service.ChatBountyInteractionV3AuthorityService;
import cn.jia.chat.service.ChatBountyInteractionV3PreviewService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
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

/** Owner HTTP for schema-3 preview and per-intent authority; no Provider work occurs here. */
@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix="chat.bounty-interactions",name="enabled",havingValue="true")
public class ChatBountyInteractionV3Controller {
    private static final String CACHE="private, no-store";
    private final ChatBountyInteractionV3AuthorityService service;
    private final ChatBountyInteractionV3PreviewService previews;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatBountyInteractionV3Controller(ChatBountyInteractionV3AuthorityService service,
            ChatBountyInteractionV3PreviewService previews,HumanSenderIdentityResolver identities,
            TenantScopeResolver tenants) {
        this.service=service;this.previews=previews;this.identities=identities;this.tenants=tenants;
    }

    @GetMapping("/{conversationId}/interactions/context")
    public ResponseEntity<JsonResult<ChatBountyInteractionV3Wire.ContextData>> context(
            @PathVariable String conversationId,Authentication authentication,HttpServletRequest request) {
        readOnlyGet(request);exactId(conversationId);return ok(previews.context(tenants.resolve(authentication),sender(),conversationId));
    }

    @PostMapping("/{conversationId}/interactions/preview")
    public ResponseEntity<JsonResult<ChatBountyInteractionV3Wire.PreviewData>> preview(
            @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String interactionKey,
            @RequestBody(required=false) String raw,Authentication authentication,HttpServletRequest request) {
        noQuery(request);var intent=parseIntent(raw,false);var value=service.preview(tenants.resolve(authentication),
                sender(),conversationId,interactionKey,intent);return ok(value);
    }

    @PostMapping("/{conversationId}/interactions/provider-consents")
    public ResponseEntity<JsonResult<cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO>> issue(
            @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String issueKey,
            @RequestBody(required=false) String raw,Authentication authentication,HttpServletRequest request) {
        noQuery(request);var issue=parseIssue(raw);var value=service.issue(tenants.resolve(authentication),
                sender(),conversationId,issueKey,issue);return ok(value);
    }

    @GetMapping("/{conversationId}/interactions/provider-consents/request")
    public ResponseEntity<JsonResult<cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO>> getIssue(
            @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String issueKey,
            Authentication authentication,HttpServletRequest request) {
        readOnlyGet(request);return ok(service.getIssue(tenants.resolve(authentication),sender(),conversationId,issueKey));
    }

    @PostMapping("/{conversationId}/interactions/provider-consents/{consentId}/revoke")
    public ResponseEntity<JsonResult<cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO>> revoke(
            @PathVariable String conversationId,@PathVariable String consentId,
            @RequestHeader("Idempotency-Key") String revokeKey,@RequestBody(required=false) String raw,
            Authentication authentication,HttpServletRequest request) {
        noQuery(request);var body=parseRevoke(raw);return ok(service.revoke(tenants.resolve(authentication),
                sender(),conversationId,consentId,revokeKey,body));
    }

    @GetMapping("/{conversationId}/interactions/request")
    public ResponseEntity<JsonResult<ChatBountyInteractionV3Wire.AcceptedData>> getInteraction(
            @PathVariable String conversationId,@RequestHeader("Idempotency-Key") String interactionKey,
            Authentication authentication,HttpServletRequest request) {
        readOnlyGet(request);return ok(service.getInteraction(tenants.resolve(authentication),sender(),
                conversationId,interactionKey));
    }

    private ServerResolvedSender sender(){try{return identities.resolve(EsContextHolder.getContext());}
        catch(IllegalStateException e){throw new ChatDeliberationException(
                ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Bounty follow-up is unavailable");}}
    private static ChatBountyInteractionV3Wire.IntentValue parseIntent(String raw,boolean authority){try{return ChatBountyInteractionV3Wire.parseIntent(raw,authority);}catch(IllegalArgumentException e){throw invalid();}}
    private static ChatBountyInteractionV3Wire.Issue parseIssue(String raw){try{return ChatBountyInteractionV3Wire.parseIssue(raw);}catch(IllegalArgumentException e){throw invalid();}}
    private static ChatBountyInteractionV3Wire.RevokeBody parseRevoke(String raw){try{return ChatBountyInteractionV3Wire.parseRevoke(raw);}catch(IllegalArgumentException e){throw invalid();}}
    private static ChatDeliberationException invalid(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Invalid schema-3 bounty follow-up");}
    private static void noQuery(HttpServletRequest request){if(request.getQueryString()!=null)throw invalid();}
    private static void readOnlyGet(HttpServletRequest request){noQuery(request);if(request.getContentLengthLong()>0||request.getHeader(HttpHeaders.TRANSFER_ENCODING)!=null)throw invalid();}
    private static void exactId(String value){if(value==null||value.isBlank()||!value.equals(value.strip())||value.codePointCount(0,value.length())>100||value.chars().anyMatch(Character::isISOControl))throw invalid();}
    private static <T> ResponseEntity<JsonResult<T>> ok(T data){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE).body(JsonResult.success(data));}

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> chatError(ChatDeliberationException error){return error(error.reason(),error.getMessage());}
    @ExceptionHandler(ControlledImageFollowupAuthorityService.Failure.class)
    public ResponseEntity<JsonResult<Void>> authorityError(ControlledImageFollowupAuthorityService.Failure error){
        return switch(error.reason()){
            case BAD_REQUEST -> error(ChatDeliberationException.Reason.INVALID_REQUEST,"Invalid schema-3 bounty follow-up");
            case NOT_FOUND_OR_FORBIDDEN -> error(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Bounty follow-up is unavailable");
            case CONFLICT -> error(ChatDeliberationException.Reason.CONFLICT,"Bounty follow-up changed");
            case UNAVAILABLE -> error(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Bounty follow-up is unavailable");
        };}
    private static ResponseEntity<JsonResult<Void>> error(ChatDeliberationException.Reason reason,String message){
        HttpStatus status=switch(reason){case INVALID_REQUEST,INVALID_ROUTE,GAP->HttpStatus.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN->HttpStatus.NOT_FOUND;case CONFLICT->HttpStatus.CONFLICT;
            case PERSISTENCE_ERROR->HttpStatus.SERVICE_UNAVAILABLE;};
        String suffix=switch(reason){case INVALID_REQUEST,INVALID_ROUTE,GAP->"INVALID_REQUEST";
            case NOT_FOUND_OR_FORBIDDEN->"NOT_FOUND_OR_FORBIDDEN";case CONFLICT->"CONFLICT";
            case PERSISTENCE_ERROR->"UNAVAILABLE";};
        JsonResult<Void> body=JsonResult.failure("BOUNTY_FOLLOWUP_V3_"+suffix,
                status==HttpStatus.NOT_FOUND?"Bounty follow-up is unavailable":message);body.setStatus(status.value());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE).body(body);
    }
}
