package cn.jia.chat.api;

import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.chat.service.ChatBountyInteractionAdmissionService;
import cn.jia.chat.service.ChatBountyInteractionV3AuthorityService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import tools.jackson.databind.ObjectMapper;
import cn.jia.chat.service.ChatBountyDiscussionAdmissionService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Separate v2 admission surface; the legacy /chat/stream still cannot create EXECUTE steps. */
@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix = "chat.bounty-interactions", name = "enabled", havingValue = "true")
public class ChatBountyInteractionController {
    private final ChatBountyInteractionAdmissionService admissions;
    private final ChatBountyDiscussionAdmissionService discussion;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;
    private final ChatBountyInteractionV3AuthorityService v3;
    private final ObjectMapper json;

    public ChatBountyInteractionController(ChatBountyInteractionAdmissionService admissions,
            ChatBountyDiscussionAdmissionService discussion, HumanSenderIdentityResolver identities,
            TenantScopeResolver tenants, ChatBountyInteractionV3AuthorityService v3,
            ObjectMapper json) {
        this.admissions = admissions;
        this.discussion = discussion;
        this.identities = identities;
        this.tenants = tenants;
        this.v3 = v3;
        this.json = json;
    }

    public record Request(int schemaVersion, String taskId, long expectedAssignmentRevision,
            String content, java.util.List<?> inputRefs, Object replyTo, Object continuationOf,
            ActionProposal actionProposal) { }
    public record ActionProposal(String kind) { }
    public record Accepted(String requestId, String userMessageId, String stepId,
            java.util.List<String> turnIds, String state, String stateVersion,
            long eventCursor, String statusUrl, boolean replay) { }

    @PostMapping("/{conversationId}/interactions")
    public ResponseEntity<JsonResult<Void>> interact(
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required=false) String raw, Authentication authentication,
            HttpServletRequest servletRequest) {
        return RetiredSkillWriteEndpoint.response();
    }

    private static ControlledImageFollowupAuthorityService.Failure translateV3(ChatDeliberationException failure) {
        var reason=switch(failure.reason()){
            case INVALID_REQUEST,INVALID_ROUTE,GAP->ControlledImageFollowupAuthorityService.Reason.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN->ControlledImageFollowupAuthorityService.Reason.NOT_FOUND_OR_FORBIDDEN;
            case CONFLICT->ControlledImageFollowupAuthorityService.Reason.CONFLICT;
            case PERSISTENCE_ERROR->ControlledImageFollowupAuthorityService.Reason.UNAVAILABLE;
        };
        return new ControlledImageFollowupAuthorityService.Failure(reason,failure);
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> error(ChatDeliberationException error) {
        HttpStatus status = switch (error.reason()) {
            case INVALID_REQUEST, INVALID_ROUTE, GAP -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PERSISTENCE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var result = JsonResult.<Void>failure("BOUNTY_INTERACTION_" + error.reason(),
                status == HttpStatus.NOT_FOUND ? "Bounty discussion is unavailable" : error.getMessage());
        result.setStatus(status.value());
        return ResponseEntity.status(status).body(result);
    }

    @ExceptionHandler(AgentTaskExecutionGrantException.class)
    public ResponseEntity<JsonResult<Void>> grantError(AgentTaskExecutionGrantException error) {
        // Never leak owner, persisted grant ID or workspace paths from the grant store.
        HttpStatus status = HttpStatus.valueOf(error.status());
        var result = JsonResult.<Void>failure("BOUNTY_INTERACTION_AUTHORIZATION_" + error.reason(),
                "Task authorization is unavailable for this operation");
        result.setStatus(status.value());
        return ResponseEntity.status(status).body(result);
    }
    @ExceptionHandler(ControlledImageFollowupAuthorityService.Failure.class)
    public ResponseEntity<JsonResult<Void>> v3AuthorityError(ControlledImageFollowupAuthorityService.Failure error) {
        HttpStatus status=switch(error.reason()){case BAD_REQUEST->HttpStatus.BAD_REQUEST;case NOT_FOUND_OR_FORBIDDEN->HttpStatus.NOT_FOUND;case CONFLICT->HttpStatus.CONFLICT;case UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;};
        String code=switch(error.reason()){case BAD_REQUEST->"INVALID_REQUEST";case NOT_FOUND_OR_FORBIDDEN->"NOT_FOUND_OR_FORBIDDEN";case CONFLICT->"CONFLICT";case UNAVAILABLE->"UNAVAILABLE";};
        var result=JsonResult.<Void>failure("BOUNTY_FOLLOWUP_V3_"+code,status==HttpStatus.NOT_FOUND?"Bounty follow-up is unavailable":"Bounty follow-up request failed");result.setStatus(status.value());return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(result);
    }

}
