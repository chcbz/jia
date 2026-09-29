package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Bytes-backed owner view; no Agent URI, runtime credential or unsigned public asset URL. */
@RestController
@RequestMapping("/chat/requests")
@ConditionalOnProperty(prefix = "chat.bounty-media", name = "enabled", havingValue = "true")
public class ChatBountyMediaController {
    private static final Map<String, String> SAFE_INLINE = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp",
            "image/gif", "gif", "audio/mpeg", "mp3", "audio/wav", "wav",
            "audio/ogg", "ogg", "text/plain", "txt");
    private final ChatDeliberationService requests;
    private final PersonalWorkspaceExecutionService executions;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatBountyMediaController(ChatDeliberationService requests,
            PersonalWorkspaceExecutionService executions, HumanSenderIdentityResolver identities,
            TenantScopeResolver tenants) {
        this.requests = Objects.requireNonNull(requests);
        this.executions = Objects.requireNonNull(executions);
        this.identities = Objects.requireNonNull(identities);
        this.tenants = Objects.requireNonNull(tenants);
    }

    @GetMapping("/{requestId}/steps/{stepId}/outputs/{outputId}")
    public ResponseEntity<byte[]> content(@PathVariable String requestId, @PathVariable String stepId,
            @PathVariable String outputId, @RequestParam(defaultValue = "false") boolean download,
            Authentication authentication) {
        if (!safeId(requestId) || !safeId(stepId) || !safeId(outputId)) throw unavailable();
        String tenant = tenants.resolve(authentication);
        ServerResolvedSender sender;
        try { sender = identities.resolve(EsContextHolder.getContext()); }
        catch (IllegalStateException unavailableIdentity) { throw unavailable(); }
        if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())
                || !safeId(sender.jiacn()) || !safeId(sender.clientId())) throw unavailable();
        var scope = new PersonalWorkspaceExecutionService.OwnerScope(tenant,
                sender.clientId(), sender.jiacn());
        // The existing request projection validates the owner, live conversation generation,
        // every step and its execution link. Never accept task/run/conversation from the browser.
        var request = requests.getRequest(tenant, sender.jiacn(), sender.clientId(), requestId);
        var step = request.steps().stream().filter(candidate -> stepId.equals(candidate.stepId()))
                .findFirst().orElseThrow(ChatBountyMediaController::unavailable);
        if (!"EXECUTE".equals(step.kind()) || !safeId(step.taskId())
                || !safeId(step.executionId()) || !safeId(step.targetAgentId())) throw unavailable();
        var execution = executions.get(scope, step.executionId());
        if (execution == null || !step.executionId().equals(execution.executionId())
                || !step.taskId().equals(execution.taskId())
                || !request.conversationId().equals(execution.conversationId())
                || !step.targetAgentId().equals(execution.targetAgentId())
                || !"CONVERSATION".equals(execution.executionMode())
                || !"OUTPUT_COMMITTED".equals(execution.state()) || !safeId(execution.runId()))
            throw unavailable();
        var output = executions.readConversationOutput(scope, step.taskId(), execution.runId(), outputId);
        if (output == null || !execution.executionId().equals(output.executionId())
                || !outputId.equals(output.outputId()) || output.bytes() == null
                || output.byteLength() != output.bytes().length || !sha(output.sha256())
                || !output.sha256().equals(digest(output.bytes()))) throw unavailable();
        String extension = SAFE_INLINE.get(output.contentMimeType());
        // Unsupported/misleading MIME is never rendered as active content in the browser.
        boolean inline = extension != null && !download;
        String filename = "output." + (extension == null ? "bin" : extension);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment")
                        + "; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .eTag("\"" + output.sha256() + "\"")
                .contentType(inline ? MediaType.parseMediaType(output.contentMimeType())
                        : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(output.byteLength()).body(output.bytes());
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> missing(ChatDeliberationException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(JsonResult.<Void>failure("BOUNTY_MEDIA_UNAVAILABLE", "Media is unavailable"));
    }
    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<JsonResult<Void>> missingOutput(PersonalWorkspaceExecutionService.Failure ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(JsonResult.<Void>failure("BOUNTY_MEDIA_UNAVAILABLE", "Media is unavailable"));
    }
    private static boolean safeId(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= 100
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Media is unavailable");
    }
}
