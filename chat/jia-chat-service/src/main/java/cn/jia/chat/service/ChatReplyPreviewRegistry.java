package cn.jia.chat.service;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Connection-scoped admission metadata only. Never retains reply text or replays execution. */
@Component
public final class ChatReplyPreviewRegistry {
    public record Binding(String conversationId, long generation, String requestId, String turnId,
            String dispatchId, String snapshotId, String contextHash, String agentId) { }
    private record Key(String sessionId, String turnId) { }
    private final Map<Key, Preview> active = new ConcurrentHashMap<>();

    public Preview find(String sessionId, String turnId) { return active.get(new Key(sessionId, turnId)); }

    /** Called under the existing business admission transaction, before releasing its turn lock. */
    public Preview open(String sessionId, String tenant, String client, Binding binding, String senderName) {
        var key = new Key(sessionId, binding.turnId());
        var preview = new Preview(key, tenant, client, binding, senderName);
        var previous = active.putIfAbsent(key, preview);
        if (previous != null && !previous.binding.equals(binding)) throw new IllegalArgumentException("Preview binding changed");
        return previous == null ? preview : previous;
    }

    public void closeSession(String sessionId) {
        active.forEach((key, preview) -> { if (key.sessionId().equals(sessionId)) close(preview); });
    }
    public void closeTurn(String tenant, String client, String turnId) {
        active.forEach((key, preview) -> {
            if (preview.tenant.equals(tenant) && preview.client.equals(client) && key.turnId().equals(turnId)) close(preview);
        });
    }
    public void close(Preview preview) {
        synchronized (preview) { preview.closed = true; active.remove(preview.key, preview); }
    }

    public final class Preview {
        private final Key key;
        private final String tenant, client, senderName, streamId = UUID.randomUUID().toString();
        private final Binding binding;
        private long lastSequence;
        private boolean closed;
        private Preview(Key key, String tenant, String client, Binding binding, String senderName) {
            this.key = key; this.tenant = tenant; this.client = client; this.binding = binding; this.senderName = senderName;
        }
        public boolean matches(Binding value) { return binding.equals(value); }
        public synchronized void forward(Binding value, long sequence, String content, String purpose,
                ChatConversationEventBroker broker) {
            if (closed || !matches(value) || sequence <= lastSequence) return;
            lastSequence = sequence;
            // Unclassified/internal output is not user-visible; a missing preview is harmless.
            if (!"ANSWER".equals(purpose) && !"CLARIFY".equals(purpose)) return;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "agent_message_delta"); event.put("conversationId", binding.conversationId());
            event.put("conversationGeneration", Long.toString(binding.generation()));
            event.put("requestId", binding.requestId()); event.put("turnId", binding.turnId());
            event.put("streamId", streamId); event.put("deltaSeq", Long.toString(sequence));
            event.put("agentId", binding.agentId()); event.put("senderName", senderName);
            event.put("streamOutcomeKind", purpose); event.put("content", content);
            event.put("occurredAt", System.currentTimeMillis());
            broker.publishPreview(binding.conversationId(), binding.generation(), event);
        }
    }
}
