package cn.jia.chat.api;

import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.chat.service.ChatBountyInteractionAdmissionService;
import cn.jia.chat.service.ChatBountyDiscussionAdmissionService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
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

    public ChatBountyInteractionController(ChatBountyInteractionAdmissionService admissions,
            ChatBountyDiscussionAdmissionService discussion, HumanSenderIdentityResolver identities,
            TenantScopeResolver tenants) {
        this.admissions = admissions;
        this.discussion = discussion;
        this.identities = identities;
        this.tenants = tenants;
    }

    public record Request(int schemaVersion, String taskId, long expectedAssignmentRevision,
            String content, java.util.List<?> inputRefs, Object replyTo, Object continuationOf,
            ActionProposal actionProposal) { }
    public record ActionProposal(String kind) { }
    public record Accepted(String requestId, String userMessageId, String stepId,
            java.util.List<String> turnIds, String state, String stateVersion,
            long eventCursor, String statusUrl, boolean replay) { }

    @PostMapping("/{conversationId}/interactions")
    public ResponseEntity<JsonResult<Accepted>> interact(
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Request request, Authentication authentication) {
        if (request == null || request.schemaVersion() != 2) {
            throw new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                    "Bounty interaction schemaVersion must be 2");
        }
        String tenantId = tenants.resolve(authentication);
        cn.jia.chat.service.ServerResolvedSender sender;
        try {
            sender = identities.resolve(EsContextHolder.getContext());
        } catch (IllegalStateException unavailableIdentity) {
            throw new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Bounty discussion is unavailable");
        }
        if (request.actionProposal() == null) {
            if (request.inputRefs() != null && !request.inputRefs().isEmpty()
                    || request.replyTo() != null || request.continuationOf() != null) {
                throw new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                        "Referenced discussion requires verified materials or clarification context");
            }
            var admitted = discussion.admit(tenantId, sender, conversationId, idempotencyKey,
                    request.taskId(), request.expectedAssignmentRevision(), request.content());
            var accepted = new Accepted(admitted.requestId(), admitted.userMessageId(), null,
                    admitted.turnIds(), admitted.state(), admitted.stateVersion(),
                    admitted.eventCursor(), "/chat/requests/" + admitted.requestId(), admitted.replay());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(JsonResult.success(accepted));
        }
        String operation = switch (request.actionProposal().kind() == null
                ? "" : request.actionProposal().kind()) {
            case "generate_image" -> "GENERATE_IMAGE";
            case "edit_image" -> "EDIT_IMAGE";
            case "generate_audio" -> "GENERATE_AUDIO";
            case "edit_audio" -> "EDIT_AUDIO";
            case "inspect_inputs" -> "INSPECT_INPUTS";
            default -> throw new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_ROUTE,
                    "Unsupported bounty interaction proposal");
        };
        var admitted = admissions.admit(tenantId, sender, conversationId, idempotencyKey,
                new ChatBountyInteractionAdmissionService.Intent(request.taskId(),
                        request.expectedAssignmentRevision(), request.content(), operation,
                        request.inputRefs(), request.replyTo(), request.continuationOf()));
        var accepted = new Accepted(admitted.requestId(), admitted.userMessageId(), admitted.stepId(),
                java.util.List.of(), admitted.state(), Long.toString(admitted.stateVersion()),
                admitted.eventCursor(), "/chat/requests/" + admitted.requestId(), admitted.replay());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JsonResult.success(accepted));
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
}
