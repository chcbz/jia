package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.ChatBountyAssetProjector;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** Asset ID is only a locator. Authorization still walks the exact persisted request/step/run. */
@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix="chat.bounty-asset",name="enabled",havingValue="true")
public class ChatConversationAssetController {
    private final ChatBountyAssetProjector assets;
    private final ChatBountyMediaController media;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatConversationAssetController(ChatBountyAssetProjector assets, ChatBountyMediaController media,
            HumanSenderIdentityResolver identities, TenantScopeResolver tenants) {
        this.assets = Objects.requireNonNull(assets);
        this.media = Objects.requireNonNull(media);
        this.identities = Objects.requireNonNull(identities);
        this.tenants = Objects.requireNonNull(tenants);
    }

    public record AssetView(String assetId, String conversationId, String messageId, String partId,
            String kind, String mime, String sha256, long byteLength, String revision,
            String contentUrl) { }

    @GetMapping("/{conversationId}/assets/{assetId}")
    public ResponseEntity<JsonResult<AssetView>> detail(@PathVariable String conversationId,
            @PathVariable String assetId, Authentication authentication) {
        var asset = resolve(conversationId, assetId, authentication);
        verifyCatalog(asset, authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(JsonResult.success(view(asset)));
    }

    @RequestMapping(path="/{conversationId}/assets/{assetId}/content",
            method={RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<byte[]> content(@PathVariable String conversationId, @PathVariable String assetId,
            @RequestParam(defaultValue="false") boolean download,
            @RequestHeader(value=HttpHeaders.RANGE,required=false) String range,
            @RequestHeader(value=HttpHeaders.IF_RANGE,required=false) String ifRange,
            HttpServletRequest request, Authentication authentication) {
        var asset = resolve(conversationId, assetId, authentication);
        verifyCatalog(asset, authentication);
        ResponseEntity<byte[]> response = media.content(asset.requestId(), asset.stepId(),
                asset.outputId(), download, range, ifRange, request, authentication);
        // Source bytes and ETag are checked by the owner-fenced media controller; reject
        // changed bytes even if someone mutates a supposedly immutable output row.
        if (response.getStatusCode().value() != 416
                && !Objects.equals(response.getHeaders().getETag(), "\"" + asset.sha256() + "\""))
            throw unavailable();
        return response;
    }

    private void verifyCatalog(ChatBountyAssetProjector.Asset asset, Authentication authentication) {
        var response = media.outputs(asset.requestId(), asset.stepId(), authentication);
        List<ChatBountyMediaController.OutputItem> catalog = response.getBody() == null
                ? List.of() : response.getBody().getData();
        if (catalog == null || catalog.stream().noneMatch(item -> asset.outputId().equals(item.outputId())
                && asset.mime().equals(item.contentMimeType())
                && asset.sha256().equals(item.sha256()) && asset.byteLength() == item.byteLength()))
            throw unavailable();
    }

    private ChatBountyAssetProjector.Asset resolve(String conversationId, String assetId,
            Authentication authentication) {
        ServerResolvedSender sender;
        try { sender = identities.resolve(EsContextHolder.getContext()); }
        catch (IllegalStateException denied) { throw unavailable(); }
        if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())
                || !safe(sender.clientId()) || !safe(sender.jiacn())
                || !safe(conversationId) || !safe(assetId)) throw unavailable();
        String tenant = tenants.resolve(authentication);
        var asset = assets.find(new PersonalWorkspaceExecutionService.OwnerScope(tenant,
                sender.clientId(), sender.jiacn()), conversationId, assetId);
        if (asset == null || !assetId.equals(asset.assetId())
                || !conversationId.equals(asset.conversationId())) throw unavailable();
        return asset;
    }

    private static AssetView view(ChatBountyAssetProjector.Asset asset) {
        return new AssetView(asset.assetId(), asset.conversationId(), asset.messageId(),
                asset.partId(), asset.kind(), asset.mime(), asset.sha256(), asset.byteLength(),
                Long.toString(asset.revision()), "/chat/conversations/" + asset.conversationId()
                        + "/assets/" + asset.assetId() + "/content");
    }
    private static boolean safe(String text) {
        return text != null && text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    }
    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Asset is unavailable");
    }
    @ExceptionHandler({ChatDeliberationException.class, PersonalWorkspaceExecutionService.Failure.class})
    public ResponseEntity<JsonResult<Void>> missing(Exception ignored) {
        return ResponseEntity.status(404)
                .body(JsonResult.<Void>failure("BOUNTY_ASSET_UNAVAILABLE", "Asset is unavailable"));
    }
}
