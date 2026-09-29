package cn.jia.chat.api;

import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.chat.service.ChatBountyInteractionAdmissionService;
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
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatBountyInteractionController(ChatBountyInteractionAdmissionService admissions,
            HumanSenderIdentityResolver identities, TenantScopeResolver tenants) {
        this.admissions = admissions;
        this.identities = identities;
        this.tenants = tenants;
    }

    public record Request(int schemaVersion, String taskId, long expectedAssignmentRevision,
            String content, java.util.List<?> inputRefs, Object replyTo, Object continuationOf,
            ActionProposal actionProposal) { }
    public record ActionProposal(String kind) { }

    @PostMapping("/{conversationId}/interactions")
    public ResponseEntity<JsonResult<ChatBountyInteractionAdmissionService.Admission>> interact(
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Request request, Authentication authentication) {
        if (request == null || request.schemaVersion() != 2 || request.actionProposal() == null) {
            throw new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                    "Explicit v2 action proposal is required until conversational routing is available");
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
        String tenantId = tenants.resolve(authentication);
        cn.jia.chat.service.ServerResolvedSender sender;
        try {
            sender = identities.resolve(EsContextHolder.getContext());
        } catch (IllegalStateException unavailableIdentity) {
            throw new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Bounty discussion is unavailable");
        }
        var admitted = admissions.admit(tenantId, sender, conversationId, idempotencyKey,
                new ChatBountyInteractionAdmissionService.Intent(request.taskId(),
                        request.expectedAssignmentRevision(), request.content(), operation,
                        request.inputRefs(), request.replyTo(), request.continuationOf()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JsonResult.success(admitted));
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
