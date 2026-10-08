package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.*;
import cn.jia.core.context.EsContext;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatConversationAssetControllerTest {
    private final ChatBountyAssetProjector assets = mock(ChatBountyAssetProjector.class);
    private final ChatBountyMediaController media = mock(ChatBountyMediaController.class);
    private final HumanSenderIdentityResolver identities = mock(HumanSenderIdentityResolver.class);
    private final TenantScopeResolver tenants = mock(TenantScopeResolver.class);
    private final Authentication authentication = mock(Authentication.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ChatConversationAssetController controller = new ChatConversationAssetController(
            assets, media, identities, tenants);
    private final ChatBountyAssetProjector.Asset asset = new ChatBountyAssetProjector.Asset("ast_1",
            "0", "owner", "client", "42", 1, "req", "step", "exec", "run", "output_1",
            "100", "part_1", "image", "image/png", "a".repeat(64), 20, 1);
    private void ready() {
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender(
                "user", "Human", "owner", "client", DisplayNameSource.JIACN));
        when(tenants.resolve(authentication)).thenReturn("0");
        when(assets.find(new PersonalWorkspaceExecutionService.OwnerScope("0", "client", "owner"), "42", "ast_1"))
                .thenReturn(asset);
        when(media.outputs("req", "step", authentication)).thenReturn(ResponseEntity.ok(JsonResult.success(List.of(
                new ChatBountyMediaController.OutputItem("output_1", "image/png", "a".repeat(64), 20, "/preview", "/download")))));
    }
    @Test void assetMetadataAndContentOnlyResolveThroughExactScopedSource() {
        ready();
        var detail = controller.detail("42", "ast_1", authentication);
        assertEquals("/chat/conversations/42/assets/ast_1/content", detail.getBody().getData().contentUrl());
        assertEquals("no-store", detail.getHeaders().getCacheControl());
        when(media.content("req", "step", "output_1", false, null, null, request, authentication))
                .thenReturn(ResponseEntity.ok().eTag("\"" + "a".repeat(64) + "\"").body(new byte[20]));
        assertEquals(20, controller.content("42", "ast_1", false, null, null, request, authentication).getBody().length);
    }
    @Test void unknownOwnerAssetNeverFallsBackToAnUnsignedPath() {
        ready();
        assertThrows(ChatDeliberationException.class, () -> controller.detail("42", "other-asset", authentication));
        verifyNoInteractions(media);
        verify(assets).find(new PersonalWorkspaceExecutionService.OwnerScope("0", "client", "owner"), "42", "other-asset");
    }
    @Test void changedCatalogueOrDigestCannotReturnAssetBytes() {
        ready();
        when(media.outputs("req", "step", authentication)).thenReturn(ResponseEntity.ok(JsonResult.success(List.of())));
        assertThrows(ChatDeliberationException.class, () -> controller.content("42", "ast_1", false, null, null, request, authentication));
        verify(media, never()).content(any(), any(), any(), anyBoolean(), any(), any(), any(), any());
        ready();
        when(media.content("req", "step", "output_1", false, null, null, request, authentication))
                .thenReturn(ResponseEntity.ok().eTag("\"different\"").body(new byte[20]));
        assertThrows(ChatDeliberationException.class, () -> controller.content("42", "ast_1", false, null, null, request, authentication));
    }
    @Test void authorizedUnsatisfiableRangePreserves416RatherThanBecoming404() {
        ready();
        when(media.content("req", "step", "output_1", false, "bytes=100-", null, request, authentication))
                .thenReturn(ResponseEntity.status(416).header(HttpHeaders.CONTENT_RANGE, "bytes */20").build());
        var result = controller.content("42", "ast_1", false, "bytes=100-", null, request, authentication);
        assertEquals(416, result.getStatusCode().value());
        assertEquals("bytes */20", result.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertNull(result.getBody());
    }
}
