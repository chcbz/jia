package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatReplyPreviewRegistryTest {
    private final ChatReplyPreviewRegistry registry = new ChatReplyPreviewRegistry();
    private final ChatConversationEventBroker broker = mock(ChatConversationEventBroker.class);
    private final ChatReplyPreviewRegistry.Binding binding = new ChatReplyPreviewRegistry.Binding(
            "42", 3, "request", "turn", "dispatch", "snapshot", "sha256:context", "agent");

    @Test void streamsAreBoundToTheirAdmittedSessionAndTaskAndNeverReplayText() {
        var stream = registry.open("socket", "tenant", "client", binding, "吴用");
        assertSame(stream, registry.find("socket", "turn"));
        assertNull(registry.find("other-socket", "turn"));
        var wrong = new ChatReplyPreviewRegistry.Binding("43", 3, "request", "turn", "dispatch", "snapshot", "sha256:context", "agent");
        stream.forward(wrong, 1, "foreign", "ANSWER", broker);
        verifyNoInteractions(broker);
        stream.forward(binding, 1, "word", "ANSWER", broker);
        stream.forward(binding, 1, "duplicate", "ANSWER", broker);
        stream.forward(binding, 3, "gap", "ANSWER", broker);
        ArgumentCaptor<Map<String,Object>> frames = ArgumentCaptor.forClass(Map.class);
        verify(broker, times(2)).publishPreview(eq("42"), eq(3L), frames.capture());
        assertEquals("word", frames.getAllValues().getFirst().get("content"));
        assertEquals("gap", frames.getAllValues().getLast().get("content"));
        assertEquals("3", frames.getAllValues().getLast().get("deltaSeq"));
        assertFalse(frames.getValue().containsKey("eventSequence"));
        assertFalse(frames.getValue().containsKey("stateVersion"));
    }

    @Test void finalOrCancellationClosesOnlyTheExactScopeAndReleasesAdmission() {
        var stream = registry.open("socket", "tenant", "client", binding, "吴用");
        registry.closeTurn("other", "client", "turn");
        assertSame(stream, registry.find("socket", "turn"));
        registry.closeTurn("tenant", "client", "turn");
        assertNull(registry.find("socket", "turn"));
        stream.forward(binding, 1, "late", "ANSWER", broker);
        verifyNoInteractions(broker);
    }

    @Test void disconnectOrAdmissionRollbackReleasesMetadataAndCannotForwardLateFrames() {
        var stream = registry.open("socket", "tenant", "client", binding, "吴用");
        registry.closeSession("socket");
        assertNull(registry.find("socket", "turn"));
        stream.forward(binding, 1, "late", "ANSWER", broker);
        var retry = registry.open("next-socket", "tenant", "client", binding, "吴用");
        registry.close(retry);
        retry.forward(binding, 1, "rolled-back", "ANSWER", broker);
        verifyNoInteractions(broker);
    }

    @Test void controlProseNeverAppearsAsUserPreview() {
        var stream = registry.open("socket", "tenant", "client", binding, "吴用");
        stream.forward(binding, 1, "internal", "ACTION_REQUEST", broker);
        stream.forward(binding, 2, "unknown", null, broker);
        verifyNoInteractions(broker);
    }
}
