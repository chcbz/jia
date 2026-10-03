package cn.jia.chat.service;

import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTypedInspectionContextServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatConversationArchiveStore archive = mock(ChatConversationArchiveStore.class);
    private final TypedInspectionSessionRegistry sessions = new TypedInspectionSessionRegistry();
    private final ChatTypedInspectionContextService.Scope scope = new ChatTypedInspectionContextService.Scope(
            "0", "owner", "client", "42", 1, "task", 3, "request", 1, "agent");
    private ChatTypedInspectionContextService service() {
        sessions.register("session", "0", "owner", "client", "agent", Map.ofEntries(
                Map.entry("schemaVersion", 1), Map.entry("contract", "juyiting-typed-inspection-v1"), Map.entry("enabled", true),
                Map.entry("profileId", "profile"), Map.entry("engineContractId", "engine"), Map.entry("enginePolicyDigest", digest('a')),
                Map.entry("toolPolicyDigest", digest('b')), Map.entry("inputPolicyDigest", digest('c')), Map.entry("toolPolicy", "STRICT_NO_TOOLS"),
                Map.entry("recovery", "durable-inbox-turn-readback-v1"), Map.entry("supportedInputs", List.of(
                        input("text", "text/plain", "DIRECT_TEXT"), input("image", "image/png", "LOCAL_IMAGE"),
                        input("audio", "audio/wav", "LOCAL_AUDIO"), input("file", "application/pdf", "PARSED_TEXT")))), () -> true);
        return new ChatTypedInspectionContextService(jdbc, archive, sessions, true);
    }
    @Test void all32MixedMaterialsFreezeExactRoleVersionAndMetadata() {
        var service = service();
        when(jdbc.queryForList(contains("agent_personal_workspace_task_file_link"), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            assertTrue(sql.contains("BINARY l.owner_jiacn=BINARY ?"));
            assertTrue(sql.contains("BINARY l.client_id=BINARY ?"));
            assertTrue(sql.contains("BINARY l.task_id=BINARY ?"));
            assertTrue(sql.contains("l.link_role=? AND BINARY l.link_role=BINARY ?"));
            Object[] args = Arrays.copyOfRange(inv.getArguments(), 1, inv.getArguments().length);
            assertEquals(List.of("0", "owner", "client", "task"), Arrays.asList(args).subList(0, 4));
            int i = Integer.parseInt(((String)args[4]).substring(5));
            assertEquals(2, args[5]);
            assertEquals(i % 2 == 0 ? "INPUT" : "REFERENCE", args[6]); assertEquals(args[6], args[7]);
            String mime = List.of("text/plain", "image/png", "audio/wav", "application/pdf").get(i % 4);
            return List.of(Map.of("content_mime_type", mime, "content_hash", "a".repeat(64), "byte_length", 12L));
        });
        var selectors = new ArrayList<ChatTypedInspectionWire.SourceSelector>();
        for (int i = 0; i < 32; i++) selectors.add(selector("file-" + i, i % 2 == 0 ? "INPUT" : "REFERENCE"));
        var context = service.resolve(scope, selectors);
        var sources = ChatTypedInspectionContextService.sources(context.admissionEnvelopeJson());
        assertEquals(32, sources.size()); assertEquals(selectors, context.selectors());
        assertEquals(4, sources.stream().map(s -> s.get("mediaKind")).distinct().count());
        assertFalse(context.admissionEnvelopeJson().contains("storage_uri")); verifyNoInteractions(archive);
        selectors.add(selector("file-32", "INPUT"));
        assertThrows(ChatDeliberationException.class, () -> service.resolve(scope, selectors));
    }
    @Test void invalidDirectlyConstructedRoleFailsBeforeAnySourceLookup() {
        var service = service();
        for (String role : List.of("OUTPUT", "input", "REFERENCE ")) {
            assertThrows(ChatDeliberationException.class, () -> service.resolve(scope, List.of(selector("file", role))));
        }
        verifyNoInteractions(jdbc, archive);
    }
    @Test void missingOrAmbiguousScopedLinkIsNeverSubstitutedWithAnotherRole() {
        var service = service(); var selector = selector("file", "INPUT");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        assertThrows(ChatDeliberationException.class, () -> service.resolve(scope, List.of(selector)));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(), Map.of()));
        assertThrows(ChatDeliberationException.class, () -> service.resolve(scope, List.of(selector)));
        verifyNoInteractions(archive);
    }
    private static ChatTypedInspectionWire.SourceSelector selector(String file, String role) {
        return new ChatTypedInspectionWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION", file, "2", role, null, null);
    }
    private static Map<String,Object> input(String media, String mime, String carrier) {
        return Map.of("mediaKind", media, "mimeType", mime, "carrier", carrier, "carrierContractDigest", digest('d'));
    }
    private static String digest(char c) { return "sha256:" + String.valueOf(c).repeat(64); }
}
