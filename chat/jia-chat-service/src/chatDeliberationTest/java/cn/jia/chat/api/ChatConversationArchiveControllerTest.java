package cn.jia.chat.api;

import cn.jia.chat.archive.conversation.ChatConversationArchiveException;
import cn.jia.chat.archive.conversation.ChatConversationArchiveService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.service.DisplayNameSource;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatConversationArchiveControllerTest {
    private final ChatConversationArchiveService archives = mock(ChatConversationArchiveService.class);
    private final HumanSenderIdentityResolver identities = mock(HumanSenderIdentityResolver.class);
    private final TenantScopeResolver tenants = mock(TenantScopeResolver.class);
    private final Authentication authentication = mock(Authentication.class);
    private final ChatConversationArchiveController controller =
            new ChatConversationArchiveController(archives, identities, tenants);

    private void identity() {
        when(tenants.resolve(authentication)).thenReturn("0");
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender(
                "user", "Owner", "owner", "client", DisplayNameSource.JIACN));
    }

    private Map<String, Object> body(String asset, Object revision) {
        return Map.of("mode", "create", "items", List.of(Map.of(
                "assetRef", Map.of("assetId", asset, "revision", revision))));
    }

    @Test
    void exactSingleAssetRefUsesOnlyAuthenticatedTenantClientOwnerScope() {
        identity();
        var receipt = new ChatConversationArchiveService.Receipt("arc_operation_1", "saved", "3",
                List.of(new ChatConversationArchiveService.ItemReceipt("asset_1", "1", "saved",
                        "pws_file_1", 1, null, null)));
        when(archives.archive(any(), any())).thenReturn(receipt);
        var response = controller.archive("42", "archive-key-0001", body("asset_1", "1"), authentication);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(receipt, response.getBody().getData());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(archives).archive(eq(new ChatConversationArchiveStore.Scope("0", "owner", "client")),
                eq(new ChatConversationArchiveService.Command("42", "archive-key-0001", "asset_1", 1)));
    }

    @Test
    void pathsUrlsFilenamesMimeHashesAndMultipleItemsAreRejectedBeforeIdentityOrService() {
        for (String forbidden : List.of("url", "path", "filename", "mime", "sha256", "content")) {
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("assetId", "asset_1");
            asset.put("revision", "1");
            asset.put(forbidden, "attacker-value");
            Map<String, Object> body = Map.of("mode", "create",
                    "items", List.of(Map.of("assetRef", asset)));
            assertThrows(ChatConversationArchiveException.class,
                    () -> controller.archive("42", "archive-key-0001", body, authentication), forbidden);
        }
        assertThrows(ChatConversationArchiveException.class, () -> controller.archive("42",
                "archive-key-0001", Map.of("mode", "create", "items",
                        List.of(Map.of("assetRef", Map.of("assetId", "asset_1", "revision", "1")),
                                Map.of("assetRef", Map.of("assetId", "asset_2", "revision", "1")))),
                authentication));
        verifyNoInteractions(archives, identities, tenants);
    }

    @Test
    void modeRevisionAndIdempotencyAreValidatedFailClosed() {
        identity();
        assertThrows(ChatConversationArchiveException.class, () -> controller.archive("42",
                "archive-key-0001", Map.of("mode", "copy", "items", List.of()), authentication));
        assertThrows(ChatConversationArchiveException.class, () -> controller.archive("42",
                "archive-key-0001", body("asset_1", "0"), authentication));
        assertThrows(ChatConversationArchiveException.class, () -> controller.archive("42",
                "short", body("asset_1", "1"), authentication));
        verifyNoInteractions(archives);
    }

    @Test
    void malformedOrAgentIdentityCannotReadOrWriteAnyOperation() {
        when(tenants.resolve(authentication)).thenReturn("0");
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender(
                "agent", "Agent", "owner", "client", DisplayNameSource.JIACN));
        var failure = assertThrows(ChatConversationArchiveException.class,
                () -> controller.archive("42", "archive-key-0001", body("asset_1", "1"), authentication));
        assertEquals(ChatConversationArchiveException.Reason.NOT_FOUND_OR_FORBIDDEN, failure.reason());
        assertThrows(ChatConversationArchiveException.class,
                () -> controller.status("42", "arc_operation_1", authentication));
        verifyNoInteractions(archives);
    }

    @Test
    void statusMapsOnlyTheScopedReadAndNeverInventsRecoverySideEffects() {
        identity();
        var receipt = new ChatConversationArchiveService.Receipt("arc_operation_1", "saving", "2",
                List.of(new ChatConversationArchiveService.ItemReceipt("asset_1", "1", "saving",
                        null, null, null, null)));
        when(archives.get(any(), any(), any())).thenReturn(receipt);
        var response = controller.status("42", "arc_operation_1", authentication);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(receipt, response.getBody().getData());
        verify(archives).get(new ChatConversationArchiveStore.Scope("0", "owner", "client"),
                "42", "arc_operation_1");
        verify(archives, never()).archive(any(), any());
    }

    @Test
    void stableFailureCatalogMapsConflictUnsupportedMissingAndTemporaryStatuses() {
        assertEquals(HttpStatus.CONFLICT, controller.error(new ChatConversationArchiveException(
                ChatConversationArchiveException.Reason.IDEMPOTENCY_CONFLICT, "conflict")).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, controller.error(new ChatConversationArchiveException(
                ChatConversationArchiveException.Reason.UNSUPPORTED, "unsupported")).getStatusCode());
        var missing = controller.error(new ChatConversationArchiveException(
                ChatConversationArchiveException.Reason.NOT_FOUND_OR_FORBIDDEN, "secret detail"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("Conversation asset is unavailable", missing.getBody().getMsg());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.error(new ChatConversationArchiveException(
                ChatConversationArchiveException.Reason.TEMPORARILY_UNAVAILABLE, "temporary")).getStatusCode());
    }
}
