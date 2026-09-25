package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationStates;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Durable restart-scanning relay. Socket/SSE sends are optimizations; DB state remains authoritative. */
@Slf4j
@Component
public class ChatDeliberationOutboxRelay implements SmartLifecycle, AutoCloseable {
    private static final long LEASE_MILLIS = 300_000L;
    private final ChatDeliberationOutboxService outbox;
    private final ChatDeliberationService deliberation;
    private final AgentWebSocketHandler sockets;
    private final BuiltinHallAgentSupport builtin;
    private final ChatConversationEventBroker broker;
    private final ChatConversationService conversations;
    private final ChatClient chatClient;
    private final String leaseOwner = ChatDeliberationOutboxService.leaseOwner();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;

    public ChatDeliberationOutboxRelay(ChatDeliberationOutboxService outbox,
            ChatDeliberationService deliberation, AgentWebSocketHandler sockets,
            BuiltinHallAgentSupport builtin, ChatConversationEventBroker broker,
            ChatConversationService conversations, @Lazy ChatClient chatClient) {
        this.outbox = outbox; this.deliberation = deliberation; this.sockets = sockets;
        this.builtin = builtin; this.broker = broker; this.conversations = conversations;
        this.chatClient = chatClient;
    }

    @Override public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-deliberation-outbox-relay"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(this::safePoll, 0, 500, TimeUnit.MILLISECONDS);
    }

    void pollOnce() {
        if (!running.get()) return;
        long now = System.currentTimeMillis();
        for (ChatDispatchOutboxEntity candidate : outbox.discover(now, 8)) {
            ChatDeliberationOutboxService.Claim claim = outbox.claim(candidate, leaseOwner, now, LEASE_MILLIS);
            if (claim == null) continue;
            try { deliverClaim(claim); }
            catch (RuntimeException failure) {
                log.warn("Durable chat outbox delivery failed, eventId={}", candidate.getEventId(), failure);
                try { outbox.retry(claim, failure.getClass().getSimpleName(), System.currentTimeMillis()); }
                catch (RuntimeException stale) { log.debug("Chat outbox retry lost lease", stale); }
            }
        }
    }

    private void safePoll() { try { pollOnce(); } catch (RuntimeException e) { log.warn("Chat outbox poll failed", e); } }

    void deliverClaim(ChatDeliberationOutboxService.Claim claim) {
        ChatDispatchOutboxEntity row = claim.row();
        Map<String,Object> payload = parse(row.getPayloadJson());
        switch (row.getEventType()) {
            case "DISPATCH" -> deliverDispatch(claim, payload);
            case "CANCEL_REQUESTED" -> deliverCancel(claim, payload);
            case "FINAL_PERSISTED" -> deliverFinal(claim, payload);
            default -> outbox.dead(claim, "UNSUPPORTED_EVENT_TYPE", System.currentTimeMillis());
        }
    }

    private void deliverDispatch(ChatDeliberationOutboxService.Claim claim, Map<String,Object> p) {
        ChatDispatchOutboxEntity row = claim.row();
        String agentId = text(p, "targetAgentId");
        if (builtin.isBuiltinAgent(agentId)) {
            if (claim.recoveredStaleLease()) {
                settleRecoveredBuiltin(claim);
                return;
            }
            deliberation.markDispatch(row.getTenantId(), row.getOwnerJiacn(), row.getClientId(), row.getTurnId(), true);
            try {
                runBuiltin(row, p);
                outbox.sent(claim, System.currentTimeMillis());
            } catch (RuntimeException generationFailure) {
                // A builtin model call is never retried because that could repeat generation/finalization.
                // If final commit won immediately before the failure, only settle the durable dispatch.
                if (hasPersistedFinal(row)) {
                    outbox.sent(claim, System.currentTimeMillis());
                } else {
                    deliberation.failBuiltinRecovery(row.getTenantId(), row.getOwnerJiacn(),
                            row.getClientId(), row.getTurnId());
                    outbox.dead(claim, "BUILTIN_GENERATION_FAILED", System.currentTimeMillis());
                }
            }
            return;
        }
        Map<String,Object> wire = hostedWire(row, p);
        boolean delivered = sockets.sendDirectMessageToAgent(row.getTenantId(), row.getOwnerJiacn(),
                row.getClientId(), agentId, wire);
        deliberation.markDispatch(row.getTenantId(), row.getOwnerJiacn(), row.getClientId(), row.getTurnId(), delivered);
        if (delivered) outbox.sent(claim, System.currentTimeMillis());
        else outbox.retry(claim, "AGENT_OFFLINE", System.currentTimeMillis());
    }

    private void settleRecoveredBuiltin(ChatDeliberationOutboxService.Claim claim) {
        ChatDispatchOutboxEntity row = claim.row();
        if (hasPersistedFinal(row)) {
            outbox.sent(claim, System.currentTimeMillis());
            return;
        }
        deliberation.failBuiltinRecovery(row.getTenantId(), row.getOwnerJiacn(),
                row.getClientId(), row.getTurnId());
        outbox.dead(claim, "BUILTIN_RESTART_RECOVERY_REQUIRED", System.currentTimeMillis());
    }

    private boolean hasPersistedFinal(ChatDispatchOutboxEntity row) {
        String state = deliberation.getTurn(row.getTenantId(), row.getOwnerJiacn(),
                row.getClientId(), row.getTurnId()).state();
        return ChatDeliberationStates.FINAL_PERSISTED.equals(state)
                || ChatDeliberationStates.PUBLISHED.equals(state);
    }

    private void runBuiltin(ChatDispatchOutboxEntity row, Map<String,Object> p) {
        String conversationId=text(p,"conversationId"), requestId=text(p,"requestId"), agentId=text(p,"targetAgentId");
        long generation=decimal(p,"conversationGeneration"), seqStart=0L;
        String turnId=text(p,"turnId"), dispatchId=text(p,"dispatchId"), snapshot=text(p,"contextSnapshotId"), hash=text(p,"contextHash");
        StringBuilder answer=new StringBuilder(); AtomicLong seq=new AtomicLong(seqStart);
        try {
            Flux<String> chunks = chatClient.prompt(Prompt.builder()
                    .messages(UserMessage.builder().text(text(p,"content")).build()).build())
                    .messages().stream().content();
            chunks.doOnNext(chunk -> {
                ChatDeliberationService.DeltaResult result = deliberation.acceptDelta(row.getTenantId(), row.getOwnerJiacn(),
                        row.getClientId(), conversationId, generation, agentId, requestId, turnId, dispatchId,
                        snapshot, hash, seq.incrementAndGet(), chunk);
                if (result.status() == ChatDeliberationService.DeltaStatus.TERMINAL) throw new TurnCancelled();
                if (result.status() != ChatDeliberationService.DeltaStatus.ACCEPTED) throw new IllegalStateException(result.status().name());
                answer.append(chunk);
                publishPersistedEvent(result.event());
            }).blockLast();
            if (answer.isEmpty()) answer.append("诸位稍安，宋江已收到传令。此事先记入议程，待诸位好汉回报后再作定夺。");
            deliberation.persistFinal(row.getTenantId(), row.getOwnerJiacn(), row.getClientId(), conversationId,
                    generation, agentId, requestId, turnId, dispatchId, snapshot, hash, answer.toString(),
                    BuiltinHallAgentSupport.SONGJIANG_NAME);
        } catch (TurnCancelled cancelled) {
            return;
        }
    }

    private void deliverCancel(ChatDeliberationOutboxService.Claim claim, Map<String,Object> p) {
        ChatDispatchOutboxEntity row=claim.row(); String agentId=text(p,"targetAgentId");
        if (agentId == null || builtin.isBuiltinAgent(agentId)) { outbox.sent(claim,System.currentTimeMillis()); return; }
        Map<String,Object> wire=new HashMap<>(); wire.put("schemaVersion",AgentProtocolConstants.VERSION_1);
        wire.put("messageType",AgentProtocolConstants.TYPE_CHAT_STOP); wire.put("requestId",text(p,"requestId"));
        wire.put("turnId",row.getTurnId()); wire.put("dispatchId",row.getDispatchId()); wire.put("targetAgentId",agentId);
        boolean sent=sockets.sendDirectMessageToAgent(row.getTenantId(),row.getOwnerJiacn(),row.getClientId(),agentId,wire);
        if(sent) outbox.sent(claim,System.currentTimeMillis()); else outbox.retry(claim,"AGENT_OFFLINE",System.currentTimeMillis());
    }

    private void deliverFinal(ChatDeliberationOutboxService.Claim claim, Map<String,Object> p) {
        ChatDispatchOutboxEntity row=claim.row(); String conversationId=text(p,"conversationId"); long generation=decimal(p,"conversationGeneration");
        Map<String,Object> event=new LinkedHashMap<>(p); event.put("type","agent_message");
        event.put("eventId",row.getEventId()); event.put("occurredAt",Long.toString(row.getCreatedAt()));
        boolean delivered=broker.publishIfSubscribed(conversationId,generation,
                () -> conversations.isLiveGeneration(row.getOwnerJiacn(),row.getClientId(),conversationId,generation),event);
        if (!delivered) { outbox.retry(claim,"NO_ACTIVE_SSE_SUBSCRIBER",System.currentTimeMillis()); return; }
        deliberation.markPublished(row.getTenantId(),row.getOwnerJiacn(),row.getClientId(),row.getTurnId());
        outbox.sent(claim,System.currentTimeMillis());
    }

    private void publishPersistedEvent(ChatConversationEventEntity stored) {
        if (stored == null) return;
        Map<String,Object> event=parse(stored.getPayloadJson());
        event.put("eventId",stored.getEventId()); event.put("eventSequence",Long.toString(stored.getEventSequence()));
        event.put("eventVersion",Long.toString(stored.getEventVersion())); event.put("occurredAt",Long.toString(stored.getOccurredAt()));
        broker.publishIfSubscribed(stored.getConversationId(),stored.getConversationGeneration(),
                () -> conversations.isLiveGeneration(stored.getOwnerJiacn(),stored.getClientId(),stored.getConversationId(),stored.getConversationGeneration()), event);
    }

    private Map<String,Object> hostedWire(ChatDispatchOutboxEntity row, Map<String,Object> p) {
        Map<String,Object> wire=new LinkedHashMap<>(p); wire.put("schemaVersion",AgentProtocolConstants.VERSION_1);
        wire.put("messageType",AgentProtocolConstants.TYPE_CHAT_MESSAGE); wire.put("messageId",row.getEventId());
        wire.put("correlationId",text(p,"conversationId")); wire.put("tenantId",row.getTenantId());
        wire.put("clientId",row.getClientId()); wire.put("targetAgentId",text(p,"targetAgentId"));
        wire.put("contextSnapshot",Map.of("schemaVersion","1","contextSnapshotId",text(p,"contextSnapshotId"),
                "contextHash",text(p,"contextHash"),"sourceVector",Optional.ofNullable(p.get("sourceVector")).orElse(Map.of()),
                "facts",Optional.ofNullable(p.get("factsManifest")).orElse(Map.of())));
        wire.put("payload",new LinkedHashMap<>(wire)); return wire;
    }

    @SuppressWarnings("unchecked") private Map<String,Object> parse(String json) {
        try { return JsonUtil.getMapper().readValue(json,Map.class); }
        catch(Exception e){ throw new IllegalStateException("Invalid durable outbox JSON",e); }
    }
    private String text(Map<String,Object> p,String k){Object v=p.get(k);return v==null?null:String.valueOf(v);}
    private long decimal(Map<String,Object> p,String k){try{return Long.parseLong(text(p,k));}catch(Exception e){throw new IllegalStateException("Invalid "+k);}}

    @Override public synchronized void stop(){running.set(false);if(executor!=null)executor.shutdownNow();}
    @Override public void stop(Runnable callback){stop();callback.run();}
    @Override public boolean isRunning(){return running.get();}
    @Override public boolean isAutoStartup(){return true;}
    @Override public int getPhase(){return Integer.MAX_VALUE-90;}
    @Override public void close(){stop();}
    private static final class TurnCancelled extends RuntimeException { }
}
