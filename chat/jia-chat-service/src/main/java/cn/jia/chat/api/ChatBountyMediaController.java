package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.ChatBountyAssetProjector;
import org.springframework.beans.factory.annotation.Autowired;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Bytes-backed owner view; no Agent URI, runtime credential or unsigned public asset URL. */
@RestController
@RequestMapping("/chat/requests")
@ConditionalOnProperty(prefix = "chat.bounty-media", name = "enabled", havingValue = "true")
public class ChatBountyMediaController {
    private static final Map<String, String> SAFE_INLINE = Map.ofEntries(
            Map.entry("image/png", "png"), Map.entry("image/jpeg", "jpg"),
            Map.entry("image/webp", "webp"), Map.entry("image/gif", "gif"),
            Map.entry("audio/mpeg", "mp3"), Map.entry("audio/wav", "wav"),
            Map.entry("audio/ogg", "ogg"), Map.entry("audio/mp4", "m4a"),
            Map.entry("audio/webm", "webm"), Map.entry("text/plain", "txt"));
    // Attachment controls disposition, not the identity of the verified representation.
    // Known passive formats retain their actual MIME so client integrity checks can
    // compare the download with the catalogue. Unknown/active formats remain opaque.
    private static final Map<String, String> SAFE_ATTACHMENT = safeAttachments();

    private static Map<String, String> safeAttachments() {
        var formats = new java.util.HashMap<>(SAFE_INLINE);
        formats.put("application/pdf", "pdf");
        formats.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        formats.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        formats.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        return Map.copyOf(formats);
    }
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

    private ChatBountyAssetProjector bountyAssets;

    @Autowired(required = false)
    public void setBountyAssets(ChatBountyAssetProjector bountyAssets) { this.bountyAssets = bountyAssets; }

    public record AssetRef(String assetId, String revision) { }
    public record OutputReplacement(String requestId, String stepId, String outputId, String sha256) { }
    public record OutputItem(String outputId, String contentMimeType, String sha256, long byteLength,
            String previewUrl, String downloadUrl, AssetRef assetRef, OutputReplacement replaces) {
        public OutputItem(String outputId, String contentMimeType, String sha256, long byteLength,
                String previewUrl, String downloadUrl, AssetRef assetRef) {
            this(outputId,contentMimeType,sha256,byteLength,previewUrl,downloadUrl,assetRef,null);
        }
        // Existing callers retain the media-only contract when asset projection is disabled.
        public OutputItem(String outputId, String contentMimeType, String sha256, long byteLength,
                String previewUrl, String downloadUrl) {
            this(outputId, contentMimeType, sha256, byteLength, previewUrl, downloadUrl, null);
        }
    }
    private record Authorized(PersonalWorkspaceExecutionService.OwnerScope scope,
            ChatDeliberationService.RequestView request, ChatDeliberationService.StepView step,
            PersonalWorkspaceExecutionService.ExecutionView execution) { }

    /** All IDs are resolved from an owner-scoped persisted request/step/execution chain, never a client manifest. */
    @GetMapping("/{requestId}/steps/{stepId}/outputs")
    public ResponseEntity<JsonResult<List<OutputItem>>> outputs(@PathVariable String requestId,
            @PathVariable String stepId, Authentication authentication) {
        var allowed = authorize(requestId, stepId, authentication);
        var items = executions.listConversationOutputs(allowed.scope(), allowed.step().taskId(),
                allowed.execution().runId());
        if (items == null || items.isEmpty() || items.size() > 128) throw unavailable();
        var body = new java.util.ArrayList<OutputItem>(items.size());
        for (var item : items) {
            if (item == null || !allowed.execution().executionId().equals(item.executionId())
                    || !catalogId(item.outputId()) || !sha(item.sha256()) || item.byteLength() < 0
                    || item.contentMimeType() == null)
                throw unavailable();
            String url = "/chat/requests/" + encode(requestId) + "/steps/" + encode(stepId)
                    + "/outputs/" + encode(item.outputId());
            body.add(new OutputItem(item.outputId(), item.contentMimeType(), item.sha256(),
                    item.byteLength(), SAFE_INLINE.containsKey(item.contentMimeType()) ? url : null,
                    url + "?download=true", assetRef(allowed, item), replacement(allowed,item)));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(JsonResult.success(List.copyOf(body)));
    }

    /** Both sides of an edit stay in the same live owner/task/conversation generation. */
    private OutputReplacement replacement(Authorized allowed,
            PersonalWorkspaceExecutionService.ConversationOutputInfo output) {
        var source=output.replaces();
        if (source==null) return null;
        if (!catalogId(source.requestId()) || !catalogId(source.stepId())
                || !catalogId(source.outputId()) || !sha(source.sha256())
                || allowed.request().requestId().equals(source.requestId())) throw unavailable();
        var parent=requests.getRequest(allowed.scope().tenantId(),allowed.scope().ownerJiacn(),
                allowed.scope().clientId(),source.requestId());
        if (parent==null || !source.requestId().equals(parent.requestId())
                || !allowed.request().conversationId().equals(parent.conversationId())
                || !allowed.request().conversationGeneration().equals(parent.conversationGeneration())) throw unavailable();
        var step=parent.steps().stream().filter(s->source.stepId().equals(s.stepId())).findFirst().orElseThrow(ChatBountyMediaController::unavailable);
        if (!"EXECUTE".equals(step.kind()) || !allowed.step().taskId().equals(step.taskId())
                || !allowed.step().targetAgentId().equals(step.targetAgentId())) throw unavailable();
        return new OutputReplacement(source.requestId(),source.stepId(),source.outputId(),source.sha256());
    }

    /** Catalogue reads only locate an already persisted asset; never run its projector. */
    private AssetRef assetRef(Authorized allowed,
            PersonalWorkspaceExecutionService.ConversationOutputInfo output) {
        if (bountyAssets == null) return null;
        var asset = bountyAssets.findOutput(allowed.scope(), allowed.request().conversationId(),
                allowed.request().requestId(), allowed.step().stepId(),
                allowed.execution().executionId(), allowed.execution().runId(), output.outputId());
        if (asset == null) return null; // Output bytes can be ready before the async projection commits.
        if (!allowed.scope().tenantId().equals(asset.tenantId())
                || !allowed.scope().ownerJiacn().equals(asset.ownerJiacn())
                || !allowed.scope().clientId().equals(asset.clientId())
                || !allowed.request().conversationId().equals(asset.conversationId())
                || !allowed.request().conversationGeneration().equals(Long.toString(asset.generation()))
                || !allowed.request().requestId().equals(asset.requestId())
                || !allowed.step().stepId().equals(asset.stepId())
                || !allowed.execution().executionId().equals(asset.executionId())
                || !allowed.execution().runId().equals(asset.runId())
                || !output.outputId().equals(asset.outputId())
                || !output.contentMimeType().equals(asset.mime())
                || !output.sha256().equals(asset.sha256())
                || output.byteLength() != asset.byteLength()
                || !catalogId(asset.assetId()) || asset.revision() < 1) throw unavailable();
        return new AssetRef(asset.assetId(), Long.toString(asset.revision()));
    }

    private static String encode(String part) { return UriUtils.encodePathSegment(part, StandardCharsets.UTF_8); }

    @RequestMapping(path = "/{requestId}/steps/{stepId}/outputs/{outputId}",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<byte[]> content(@PathVariable String requestId, @PathVariable String stepId,
            @PathVariable String outputId, @RequestParam(defaultValue = "false") boolean download,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange,
            HttpServletRequest request, Authentication authentication) {
        if (!safeId(requestId) || !safeId(stepId) || !safeId(outputId)) throw unavailable();
        var allowed = authorize(requestId, stepId, authentication);
        var scope = allowed.scope();
        var step = allowed.step();
        var execution = allowed.execution();
        var output = executions.readConversationOutput(scope, step.taskId(), execution.runId(), outputId);
        if (output == null || !execution.executionId().equals(output.executionId())
                || !outputId.equals(output.outputId()) || output.bytes() == null
                || output.byteLength() != output.bytes().length || !sha(output.sha256())
                || !output.sha256().equals(digest(output.bytes()))) throw unavailable();
        String extension = SAFE_INLINE.get(output.contentMimeType());
        // Unsupported/misleading MIME is never rendered as active content in the browser.
        boolean inline = extension != null && !download;
        String attachmentExtension = SAFE_ATTACHMENT.get(output.contentMimeType());
        String filename = "output." + (attachmentExtension == null ? "bin" : attachmentExtension);
        MediaType responseMime = attachmentExtension == null ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(output.contentMimeType());
        byte[] bytes = output.bytes();
        boolean head = "HEAD".equals(request.getMethod());
        String etag = "\"" + output.sha256() + "\"";
        var response = ResponseEntity.status(HttpStatus.OK).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment")
                        + "; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .eTag(etag)
                .contentType(responseMime);
        // Never interpret a Range before authenticating and verifying the committed bytes.
        // A mismatched If-Range requests the full representation, not a stale partial result.
        if (range != null && (ifRange == null || ifRange.equals(etag))) {
            ByteRange wanted = singleRange(range, bytes.length);
            if (wanted == null) return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + bytes.length)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes").build();
            int start = (int) wanted.start();
            int endExclusive = (int) wanted.end() + 1;
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment")
                            + "; filename=\"" + filename + "\"")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + wanted.end() + "/" + bytes.length)
                    .eTag(etag)
                    .contentType(responseMime)
                    .contentLength(endExclusive - start)
                    .body(head ? null : Arrays.copyOfRange(bytes, start, endExclusive));
        }
        return response.contentLength(bytes.length).body(head ? null : bytes);
    }

    private record ByteRange(long start, long end) { }

    /** Single byte range only; never allocate for unbounded or multi-part requests. */
    private static ByteRange singleRange(String value, int size) {
        if (size < 1 || value == null || !value.startsWith("bytes=")) return null;
        String spec = value.substring("bytes=".length());
        if (spec.indexOf(',') >= 0 || !spec.matches("(?:[0-9]+-[0-9]*|-[0-9]+)")) return null;
        int dash = spec.indexOf('-');
        try {
            if (dash == 0) {
                long suffix = Long.parseLong(spec.substring(1));
                if (suffix < 1) return null;
                return new ByteRange(Math.max(0L, (long) size - suffix), size - 1L);
            }
            long start = Long.parseLong(spec.substring(0, dash));
            if (start >= size) return null;
            long end = dash == spec.length() - 1 ? size - 1L
                    : Math.min(Long.parseLong(spec.substring(dash + 1)), size - 1L);
            return end < start ? null : new ByteRange(start, end);
        } catch (NumberFormatException invalid) { return null; }
    }

    private Authorized authorize(String requestId, String stepId, Authentication authentication) {
        if (!safeId(requestId) || !safeId(stepId)) throw unavailable();
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
        return new Authorized(scope, request, step, execution);
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
    private static boolean catalogId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
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
