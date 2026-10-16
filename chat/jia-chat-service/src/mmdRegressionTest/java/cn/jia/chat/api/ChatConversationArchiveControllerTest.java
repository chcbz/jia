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

    private Map<String, Object> textBody(Object start, Object end, String sha256) {
        return Map.of("mode", "create", "items", List.of(Map.of("textSelection", Map.of(
                "messageId", "1675335", "startCodePoint", start,
                "endCodePoint", end, "sha256", sha256))));
    }







    @Test
    void statusMapsOnlyTheScopedReadAndNeverInventsRecoverySideEffects() {
        identity();
        var receipt = new ChatConversationArchiveService.Receipt("arc_operation_1", "saving", "2",
                List.of(new ChatConversationArchiveService.ItemReceipt("assetRef", "asset_1", "1", null,
                        "saving", null, null, null, null)));
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
        assertEquals("Conversation archive source is unavailable", missing.getBody().getMsg());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.error(new ChatConversationArchiveException(
                ChatConversationArchiveException.Reason.TEMPORARILY_UNAVAILABLE, "temporary")).getStatusCode());
    }
}
