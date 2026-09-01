package cn.jia.chat.handler;

import cn.jia.chat.serialization.ExactWireIds;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckRejectedException;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.HallAnnouncementService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * WebSocket channel for external agent clients.
 *
 * <p>Protocol v1 messages use canonical dot-separated {@code messageType}
 * values. The legacy {@code type} field remains accepted by the normalizer;
 * only {@code command.dispatch} represents executable work, while
 * {@code chat.message}, {@code work.progress}, {@code work.result}, and
 * {@code task.event} have distinct non-command semantics.</p>
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler
        implements AgentEventPublisher, AgentRawCommandDispatcher {
    private static final String CHANNEL = "agent";
    private static final TypeReference<Map<String, Object>> MESSAGE_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper STRICT_RAW_COMMAND_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> TASK_SCOPED_OUTBOUND_TYPES = Set.of(
            AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
            AgentProtocolConstants.TYPE_COMMAND_ACK,
            AgentProtocolConstants.TYPE_WORK_PROGRESS,
            AgentProtocolConstants.TYPE_WORK_HEARTBEAT,
            AgentProtocolConstants.TYPE_WORK_RESULT,
            AgentProtocolConstants.TYPE_HELP_REQUEST,
            AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH,
            AgentProtocolConstants.TYPE_TASK_EVENT);

    private final ChatClient chatClient;
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final ChatMessageDao chatMessageDao;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final HallAnnouncementService hallAnnouncementService;
    private final AgentProtocolMessageNormalizer protocolMessageNormalizer;
    private final Supplier<AgentCommandReconnectSignal> reconnectSignalSupplier;
    private final Supplier<AgentCommandAckService> commandAckServiceSupplier;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionAgentIds = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> successfullyRegisteredAgentIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionRuntimeInstanceIds = new ConcurrentHashMap<>();
    private final Map<String, StreamState> runningStreams = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker) {
        this(chatClient, agentServiceProvider, chatMessageDao, chatConversationEventBroker, null,
                new AgentProtocolMessageNormalizer());
    }

    public AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService) {
        this(chatClient, agentServiceProvider, chatMessageDao, chatConversationEventBroker, hallAnnouncementService,
                new AgentProtocolMessageNormalizer());
    }

    public AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer) {
        this(chatClient, agentServiceProvider, chatMessageDao, chatConversationEventBroker,
                hallAnnouncementService, protocolMessageNormalizer,
                (AgentCommandReconnectSignal) null, (AgentCommandAckService) null);
    }

    @Autowired
    public AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer,
                                 ObjectProvider<AgentCommandReconnectSignal> reconnectSignalProvider,
                                 ObjectProvider<AgentCommandAckService> commandAckServiceProvider) {
        this(chatClient, agentServiceProvider, chatMessageDao, chatConversationEventBroker,
                hallAnnouncementService, protocolMessageNormalizer,
                reconnectSignalProvider == null ? () -> null : reconnectSignalProvider::getIfAvailable,
                commandAckServiceProvider == null ? () -> null : commandAckServiceProvider::getIfAvailable);
    }

    AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer,
                                 AgentCommandReconnectSignal reconnectSignal,
                                 AgentCommandAckService commandAckService) {
        this(chatClient, agentServiceProvider, chatMessageDao, chatConversationEventBroker,
                hallAnnouncementService, protocolMessageNormalizer,
                () -> reconnectSignal, () -> commandAckService);
    }

    private AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer,
                                 Supplier<AgentCommandReconnectSignal> reconnectSignalSupplier,
                                 Supplier<AgentCommandAckService> commandAckServiceSupplier) {
        this.chatClient = chatClient;
        this.agentServiceProvider = agentServiceProvider;
        this.chatMessageDao = chatMessageDao;
        this.chatConversationEventBroker = chatConversationEventBroker;
        this.hallAnnouncementService = hallAnnouncementService;
        this.protocolMessageNormalizer = protocolMessageNormalizer;
        this.reconnectSignalSupplier = reconnectSignalSupplier;
        this.commandAckServiceSupplier = commandAckServiceSupplier;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        Map<String, Object> connected = new HashMap<>();
        connected.put("sessionId", session.getId());
        connected.put("channel", CHANNEL);
        connected.put("agentId", Optional.ofNullable(sessionAgentId(session)).orElse(""));
        putIfPresent(connected, "runtimeInstanceId", sessionRuntimeInstanceId(session));
        connected.put("supportedProtocolVersions", new int[] {AgentProtocolConstants.VERSION_1});
        connected.put("legacyProtocolEnabled", true);
        connected.put("capabilities", new String[] {AgentProtocolConstants.TYPE_PROTOCOL_HELLO,
                AgentProtocolConstants.TYPE_CHAT_STREAM, AgentProtocolConstants.TYPE_CHAT_STOP,
                AgentProtocolConstants.TYPE_PING, AgentProtocolConstants.TYPE_AGENT_REGISTER,
                AgentProtocolConstants.TYPE_AGENT_PRESENCE, AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                AgentProtocolConstants.TYPE_CHAT_MESSAGE_DELTA, AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                AgentProtocolConstants.TYPE_COMMAND_ACK, AgentProtocolConstants.TYPE_WORK_PROGRESS,
                AgentProtocolConstants.TYPE_WORK_HEARTBEAT, AgentProtocolConstants.TYPE_WORK_RESULT,
                AgentProtocolConstants.TYPE_TASK_EVENT, AgentProtocolConstants.TYPE_CAPABILITY_LOOKUP,
                "agent.status", "agent.message", "task.assign", "task.report"});
        sendEvent(session, "connected", connected);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(message.getPayload(), MESSAGE_TYPE);
        } catch (Exception e) {
            sendError(session, Map.of(), "Invalid JSON message: " + e.getMessage());
            return;
        }

        AgentProtocolMessageNormalizer.NormalizedMessage normalized;
        try {
            normalized = protocolMessageNormalizer.normalizeInbound(payload);
            payload = normalized.payload();
        } catch (AgentProtocolMessageNormalizer.AgentProtocolException e) {
            sendProtocolError(session, payload, e.getCode(), e.getMessage());
            return;
        }

        switch (normalized.canonicalType()) {
            case AgentProtocolConstants.TYPE_PROTOCOL_HELLO -> sendProtocolHello(session, payload);
            case AgentProtocolConstants.TYPE_PING -> sendEvent(session, "pong", copyTrace(payload));
            case AgentProtocolConstants.TYPE_PONG -> { /* client-side response to a server heartbeat */ }
            case AgentProtocolConstants.TYPE_CHAT_STOP -> stopStream(session, payload);
            case AgentProtocolConstants.TYPE_CHAT_STREAM -> startChatStream(session, payload);
            case AgentProtocolConstants.TYPE_AGENT_REGISTER -> registerAgent(session, payload);
            case AgentProtocolConstants.TYPE_AGENT_PRESENCE -> updateAgentStatus(session, payload);
            case AgentProtocolConstants.TYPE_CHAT_MESSAGE -> saveAgentMessage(session, payload);
            case AgentProtocolConstants.TYPE_CHAT_MESSAGE_DELTA -> publishAgentMessageDelta(session, payload);
            case AgentProtocolConstants.TYPE_TASK_ASSIGN_LEGACY -> assignTask(session, payload);
            case AgentProtocolConstants.TYPE_WORK_RESULT -> {
                if (normalized.legacyTaskReport()) {
                    reportTask(session, payload);
                } else {
                    sendDeferredProtocolHandler(session, payload, normalized);
                }
            }
            case AgentProtocolConstants.TYPE_COMMAND_ACK -> handleCommandAck(session, payload);
            case AgentProtocolConstants.TYPE_WORK_PROGRESS,
                 AgentProtocolConstants.TYPE_WORK_HEARTBEAT,
                 AgentProtocolConstants.TYPE_HELP_REQUEST,
                 AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH -> sendDeferredProtocolHandler(session, payload, normalized);
            case AgentProtocolConstants.TYPE_COMMAND_DISPATCH -> sendProtocolError(session, payload,
                    "MESSAGE_DIRECTION_INVALID", "command.dispatch is server-to-Agent only");
            case AgentProtocolConstants.TYPE_TASK_EVENT -> sendProtocolError(session, payload,
                    "MESSAGE_DIRECTION_INVALID", "task.event describes an event and cannot trigger execution");
            case AgentProtocolConstants.TYPE_CAPABILITY_LOOKUP -> sendCapabilityIndex(session, payload);
            case AgentProtocolConstants.TYPE_PROTOCOL_ERROR -> log.warn(
                    "Agent reported protocol error, agentId={}, messageId={}, message={}",
                    sessionAgentId(session), payload.get("messageId"), payload.get("message"));
            default -> sendProtocolError(session, payload, "UNSUPPORTED_MESSAGE_TYPE",
                    "Unsupported Agent Protocol message type: " + normalized.canonicalType());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        sessionAgentIds.remove(session.getId());
        successfullyRegisteredAgentIds.remove(session.getId());
        sessionRuntimeInstanceIds.remove(session.getId());
        runningStreams.entrySet().removeIf(entry -> {
            StreamState stream = entry.getValue();
            if (session.getId().equals(stream.sessionId())) {
                stream.dispose();
                return true;
            }
            return false;
        });
    }

    private void sendProtocolHello(WebSocketSession session, Map<String, Object> payload) {
        Map<String, Object> event = copyTrace(payload);
        event.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        event.put("messageType", AgentProtocolConstants.TYPE_PROTOCOL_HELLO);
        event.put("supportedProtocolVersions", new int[] {AgentProtocolConstants.VERSION_1});
        event.put("legacyProtocolEnabled", true);
        event.put("executionTriggerType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        event.put("taskEventExecutionTrigger", false);
        sendEvent(session, "protocol_hello", event);
    }

    private void sendDeferredProtocolHandler(WebSocketSession session, Map<String, Object> payload,
            AgentProtocolMessageNormalizer.NormalizedMessage normalized) {
        if (requireAllowedSessionAgentId(session, payload) == null) {
            return;
        }
        sendProtocolError(session, payload, "PROTOCOL_HANDLER_NOT_AVAILABLE",
                normalized.canonicalType() + " is recognized, but its durable handler belongs to a later task");
    }

    private void startChatStream(WebSocketSession session, Map<String, Object> payload) {
        String requestId = asString(payload.get("requestId"));
        String conversationId = Optional.ofNullable(asString(payload.get("conversationId")))
                .orElse(session.getId());
        String conversationType = Optional.ofNullable(asString(payload.get("conversationType")))
                .orElse("normal");
        String senderType = asString(payload.get("senderType"));
        String senderName = asString(payload.get("senderName"));
        String content = asString(payload.get("content"));
        if (content == null || content.isBlank()) {
            sendError(session, payload, "content is required");
            return;
        }

        String streamKey = streamKey(session, requestId, conversationId);
        Optional.ofNullable(runningStreams.remove(streamKey)).ifPresent(StreamState::dispose);

        Map<String, Object> started = copyTrace(payload);
        started.put("conversationId", conversationId);
        started.put("conversationType", conversationType);
        if (senderType != null) {
            started.put("senderType", senderType);
        }
        if (senderName != null) {
            started.put("senderName", senderName);
        }
        sendEvent(session, "started", started);

        Disposable disposable = chatClient.prompt(content)
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("jiacn", Optional.ofNullable(asString(payload.get("jiacn"))).orElse("Anonymous"))
                        .param("clientId", Optional.ofNullable(asString(payload.get("clientId"))).orElse("openclaw"))
                        .param("conversationType", Optional.ofNullable(asString(payload.get("conversationType"))).orElse("normal"))
                        .param("senderType", Optional.ofNullable(asString(payload.get("senderType"))).orElse(""))
                        .param("senderName", Optional.ofNullable(asString(payload.get("senderName"))).orElse("")))
                .stream()
                .content()
                .concatMap(chunk -> Mono.fromRunnable(() -> {
                    Map<String, Object> event = copyTrace(payload);
                    event.put("conversationId", conversationId);
                    event.put("conversationType", conversationType);
                    event.put("content", chunk);
                    sendEvent(session, "delta", event);
                }))
                .doOnError(error -> sendError(session, payload, error.getMessage()))
                .doOnComplete(() -> {
                    Map<String, Object> done = copyTrace(payload);
                    done.put("conversationId", conversationId);
                    done.put("conversationType", conversationType);
                    sendEvent(session, "done", done);
                })
                .doFinally(signal -> runningStreams.remove(streamKey))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        StreamState state = new StreamState(session.getId(), requestId, conversationId, disposable);
        runningStreams.put(streamKey, state);
        if (disposable.isDisposed()) {
            runningStreams.remove(streamKey, state);
        }
    }

    private void stopStream(WebSocketSession session, Map<String, Object> payload) {
        String requestId = asString(payload.get("requestId"));
        String conversationId = Optional.ofNullable(asString(payload.get("conversationId")))
                .orElse(session.getId());
        AtomicInteger stoppedCount = new AtomicInteger();

        if (requestId != null && !requestId.isBlank()) {
            Optional.ofNullable(runningStreams.remove(streamKey(session, requestId, conversationId)))
                    .ifPresent(stream -> {
                        stream.dispose();
                        stoppedCount.incrementAndGet();
                    });
        } else {
            runningStreams.entrySet().removeIf(entry -> {
                StreamState stream = entry.getValue();
                if (session.getId().equals(stream.sessionId()) && conversationId.equals(stream.conversationId())) {
                    stream.dispose();
                    stoppedCount.incrementAndGet();
                    return true;
                }
                return false;
            });
        }

        Map<String, Object> stopped = copyTrace(payload);
        stopped.put("conversationId", conversationId);
        stopped.put("stoppedCount", stoppedCount.get());
        sendEvent(session, "stopped", stopped);
    }

    private void registerAgent(WebSocketSession session, Map<String, Object> payload) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            sendError(session, payload, "AGENT_SERVICE_UNAVAILABLE", "Agent service is unavailable");
            return;
        }
        try {
            String agentId = requireAllowedSessionAgentId(session, payload);
            if (agentId == null) {
                return;
            }
            AgentRegisterDTO request = new AgentRegisterDTO();
            request.setAgentId(agentId);
            request.setName(asString(payload.get("name")));
            request.setAvatar(asString(payload.get("avatar")));
            request.setPersonaName(asString(payload.get("personaName")));
            request.setPersonaCode(asString(payload.get("personaCode")));
            request.setEndpoint(asString(payload.get("endpoint")));
            if (payload.containsKey("abilities")) {
                request.setAbilities(asStringList(payload.get("abilities")));
            }
            AgentRegisterResultDTO result = withSessionContext(session, () -> agentService.register(request));
            if (result == null || !agentId.equals(result.getAgentId())) {
                throw new IllegalStateException("Agent registration returned a mismatched canonical identity");
            }
            rememberSessionAgent(session.getId(), result.getAgentId());
            rememberSuccessfulRegistration(session.getId(), result.getAgentId());
            Map<String, Object> event = copyTrace(payload);
            event.put("agentId", result.getAgentId());
            putIfPresent(event, "runtimeInstanceId", sessionRuntimeInstanceId(session));
            event.put("status", result.getStatus());
            event.put("token", result.getToken());
            sendEvent(session, "agent_registered", event);
            signalRegisteredReconnect(session, result.getAgentId());
            sendCapabilityIndex(session, payload);
        } catch (Exception e) {
            sendError(session, payload, errorCode(e), e.getMessage());
        }
    }

    private void signalRegisteredReconnect(WebSocketSession session, String agentId) {
        AgentCommandReconnectSignal reconnectSignal = reconnectSignalSupplier.get();
        if (reconnectSignal == null) return;
        String tenantId = sessionJiacn(session);
        String clientId = sessionClientId(session);
        if (!validExactDispatchId(tenantId, 50) || !validExactDispatchId(clientId, 50)
                || !validExactDispatchId(agentId, 100)
                || !successfullyRegisteredAgentIds(session.getId()).contains(agentId)) {
            return;
        }
        try {
            reconnectSignal.signalReconnect(new AgentCommandReconnectScope(
                    tenantId, clientId, agentId, agentId, "AGENT_RECONNECT"));
        } catch (RuntimeException failure) {
            log.warn("Agent reconnect signal was declined after successful registration");
        }
    }

    private void handleCommandAck(WebSocketSession session, Map<String, Object> payload) {
        String agentId = requireAllowedSessionAgentId(session, payload);
        if (agentId == null) return;
        if (!successfullyRegisteredAgentIds(session.getId()).contains(agentId)) {
            sendProtocolError(session, payload, "AGENT_NOT_REGISTERED",
                    "Agent must successfully register before command ACK");
            return;
        }
        AgentCommandAckService commandAckService = commandAckServiceSupplier.get();
        if (commandAckService == null) {
            sendProtocolError(session, payload, "PROTOCOL_HANDLER_NOT_AVAILABLE",
                    "command.ack durable handling is unavailable");
            return;
        }
        String tenantId = sessionJiacn(session);
        String clientId = sessionClientId(session);
        if (!validExactDispatchId(tenantId, 50) || !validExactDispatchId(clientId, 50)
                || declaredScopeConflict(payload, "tenantId", tenantId)
                || declaredScopeConflict(payload, "clientId", clientId)
                || declaredScopeConflict(payload, "targetAgentId", agentId)
                || declaredScopeConflict(payload, "receiverAgentId", agentId)
                || hasHiddenAckPayload(payload)) {
            sendCommandAckRejected(session, payload);
            return;
        }
        Long ackAt = exactLong(payload.get("ackAt"));
        try {
            commandAckService.acknowledge(new AgentCommandAck(
                    tenantId, clientId, agentId,
                    strictString(payload.get("messageId")),
                    strictString(payload.get("correlationId")),
                    strictString(payload.get("commandId")),
                    strictString(payload.get("taskId")),
                    strictString(payload.get("workItemId")),
                    strictString(payload.get("ackStatus")),
                    ackAt == null ? 0L : ackAt), System.currentTimeMillis());
        } catch (AgentCommandAckRejectedException rejected) {
            sendCommandAckRejected(session, payload);
        } catch (RuntimeException unavailable) {
            sendProtocolError(session, payload, "COMMAND_ACK_UNAVAILABLE",
                    "command ACK could not be persisted");
        }
    }

    private void sendCommandAckRejected(WebSocketSession session, Map<String, Object> payload) {
        Map<String, Object> safeTrace = new HashMap<>();
        putIfPresent(safeTrace, "messageId", payload.get("messageId"));
        sendProtocolError(session, safeTrace, AgentCommandAckRejectedException.CODE,
                "command ACK was rejected");
    }

    private boolean declaredScopeConflict(Map<String, Object> payload, String field, String expected) {
        return payload.containsKey(field)
                && !expected.equals(strictString(payload.get(field)));
    }

    private boolean hasHiddenAckPayload(Map<String, Object> payload) {
        Object nested = payload.get("payload");
        return nested instanceof Map<?, ?> map && !map.isEmpty();
    }

    private Long exactLong(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private void sendCapabilityIndex(WebSocketSession session, Map<String, Object> payload) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            sendError(session, payload, "AGENT_SERVICE_UNAVAILABLE", "Agent service is unavailable");
            return;
        }
        try {
            Map<String, Object> event = copyTrace(payload);
            event.put("agents", withSessionContext(session, agentService::listCapabilities));
            sendEvent(session, "agent_capability_index", event);
        } catch (Exception e) {
            sendError(session, payload, errorCode(e), e.getMessage());
        }
    }

    private void updateAgentStatus(WebSocketSession session, Map<String, Object> payload) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            sendError(session, payload, "AGENT_SERVICE_UNAVAILABLE", "Agent service is unavailable");
            return;
        }
        try {
            String agentId = requireAllowedSessionAgentId(session, payload);
            if (agentId == null) {
                return;
            }
            AgentStatusDTO request = new AgentStatusDTO();
            request.setStatus(asString(payload.get("status")));
            request.setCurrentTaskId(asString(payload.get("currentTaskId")));
            request.setCurrentTaskTitle(asString(payload.get("currentTaskTitle")));
            request.setErrorMessage(asString(payload.get("errorMessage")));
            if (payload.containsKey("abilities")) {
                request.setAbilities(asStringList(payload.get("abilities")));
            }
            AgentRuntimeDTO agent = withSessionContext(session, () -> agentService.updateStatus(agentId, request));
            if (agent.getAgentId() != null) {
                rememberSessionAgent(session.getId(), agent.getAgentId());
            }
            Map<String, Object> event = copyTrace(payload);
            event.put("agentId", agent.getAgentId());
            event.put("status", agent.getStatus());
            event.put("currentTaskId", agent.getCurrentTaskId());
            event.put("currentTaskTitle", agent.getCurrentTaskTitle());
            sendEvent(session, "agent_status_updated", event);
        } catch (Exception e) {
            sendError(session, payload, errorCode(e), e.getMessage());
        }
    }

    private void assignTask(WebSocketSession session, Map<String, Object> payload) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            sendError(session, payload, "AGENT_SERVICE_UNAVAILABLE", "Agent service is unavailable");
            return;
        }
        try {
            String taskId = asString(payload.get("taskId"));
            String agentId = requireAllowedSessionAgentId(session, payload);
            if (agentId == null) {
                return;
            }
            AgentTaskAssignDTO request = new AgentTaskAssignDTO();
            request.setAgentId(agentId);
            request.setAllowQueue(asBoolean(payload.get("allowQueue")));
            AgentTaskDTO task = withSessionContext(session, () -> agentService.assignTask(taskId, request));
            String tenantId = sessionJiacn(session);
            String clientId = sessionClientId(session);
            if (!isTrustedTaskAssignmentConfirmation(task, taskId, tenantId, clientId, agentId)) {
                log.warn("Refusing legacy task assignment confirmation with incomplete or mismatched trusted scope, "
                                + "tenantId={}, clientId={}, taskId={}, agentId={}",
                        tenantId, clientId, taskId, agentId);
                return;
            }
            Map<String, Object> event = new HashMap<>();
            String requestMessageId = Optional.ofNullable(asString(payload.get("messageId")))
                    .orElse(asString(payload.get("requestId")));
            event.put("schemaVersion", AgentProtocolConstants.VERSION_1);
            event.put("messageId", UUID.randomUUID().toString());
            event.put("messageType", AgentProtocolConstants.TYPE_TASK_EVENT);
            event.put("eventType", "task.assignment.accepted");
            event.put("correlationId", task.getId());
            putIfPresent(event, "causationId", requestMessageId);
            event.put("tenantId", tenantId);
            event.put("clientId", clientId);
            event.put("taskId", task.getId());
            event.put("agentId", agentId);
            event.put("targetAgentId", agentId);
            event.put("status", task.getStatus());
            event.put("assignedAgentId", agentId);
            event.put("assignedAgentName", task.getAssignedAgentName());
            if (!sendDirectMessageToAgent(agentId, event)) {
                log.warn("Legacy task assignment confirmation was not delivered, tenantId={}, clientId={}, "
                                + "taskId={}, agentId={}",
                        tenantId, clientId, task.getId(), agentId);
            }
        } catch (Exception e) {
            sendError(session, payload, errorCode(e), e.getMessage());
        }
    }

    private boolean isTrustedTaskAssignmentConfirmation(AgentTaskDTO task, String requestedTaskId,
            String tenantId, String clientId, String agentId) {
        return task != null
                && !isBlank(requestedTaskId)
                && requestedTaskId.equals(task.getId())
                && !isBlank(tenantId)
                && tenantId.equals(task.getTenantId())
                && !isBlank(clientId)
                && clientId.equals(task.getClientId())
                && !isBlank(agentId)
                && agentId.equals(task.getAssignedAgentId());
    }

    private void reportTask(WebSocketSession session, Map<String, Object> payload) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            sendError(session, payload, "AGENT_SERVICE_UNAVAILABLE", "Agent service is unavailable");
            return;
        }
        try {
            String taskId = asString(payload.get("taskId"));
            String agentId = requireAllowedSessionAgentId(session, payload);
            if (agentId == null) {
                return;
            }
            AgentTaskReportDTO request = new AgentTaskReportDTO();
            request.setAgentId(agentId);
            request.setStatus(asString(payload.get("status")));
            request.setCurrentTaskTitle(asString(payload.get("currentTaskTitle")));
            request.setFailureReason(asString(payload.get("failureReason")));
            AgentTaskDTO task = withSessionContext(session, () -> agentService.reportTask(taskId, request));
            Map<String, Object> event = copyTrace(payload);
            event.put("taskId", task.getId());
            event.put("status", task.getStatus());
            event.put("assignedAgentId", task.getAssignedAgentId());
            event.put("assignedAgentName", task.getAssignedAgentName());
            sendEvent(session, "task_reported", event);
        } catch (Exception e) {
            sendError(session, payload, errorCode(e), e.getMessage());
        }
    }

    private void saveAgentMessage(WebSocketSession session, Map<String, Object> payload) {
        String conversationId = asString(payload.get("conversationId"));
        String content = asString(payload.get("content"));
        Set<String> registeredAgentIds = registeredAgentIds(session.getId());
        String agentId = requireAllowedSessionAgentId(session, payload);
        if (agentId == null) {
            return;
        }
        if (conversationId == null || conversationId.isBlank()) {
            sendError(session, payload, "conversationId is required");
            return;
        }
        if (content == null || content.isBlank()) {
            sendError(session, payload, "content is required");
            return;
        }
        if (registeredAgentIds.isEmpty()) {
            sendError(session, payload, "AGENT_NOT_REGISTERED", "Agent must register before sending messages");
            return;
        }
        if (agentId == null || agentId.isBlank()) {
            sendError(session, payload, "AGENT_ID_REQUIRED",
                    "agentId is required when multiple agents share the same WebSocket session");
            return;
        }
        if (!registeredAgentIds.contains(agentId)) {
            sendError(session, payload, "AGENT_ID_MISMATCH", "agentId does not match the registered WebSocket session");
            return;
        }

        String conversationType = Optional.ofNullable(asString(payload.get("conversationType"))).orElse("juyiting");
        String senderName = Optional.ofNullable(asString(payload.get("senderName")))
                .orElse(Optional.ofNullable(asString(payload.get("agentName"))).orElse(agentId));
        String jiacn = Optional.ofNullable(sessionJiacn(session))
                .orElse(Optional.ofNullable(asString(payload.get("jiacn"))).orElse("Anonymous"));
        String clientId = Optional.ofNullable(sessionClientId(session))
                .orElse(Optional.ofNullable(asString(payload.get("clientId"))).orElse("openclaw"));

        ChatMessageEntity entity = new ChatMessageEntity();
        entity.init4Creation();
        entity.setJiacn(jiacn);
        entity.setClientId(clientId);
        entity.setConversationId(conversationId);
        entity.setMessageType("ASSISTANT");
        entity.setContent(content);
        entity.setSyncStatus("PENDING");
        entity.setConversationType(conversationType);
        entity.setSenderType("agent");
        entity.setSenderName(senderName);

        Map<String, Object> metadata = copyTrace(payload);
        metadata.put("agentId", agentId);
        metadata.put("senderType", "agent");
        metadata.put("senderName", senderName);
        metadata.put("conversationType", conversationType);
        entity.setMetadata(JsonUtil.toJson(metadata));
        chatMessageDao.insert(entity);

        Map<String, Object> event = copyTrace(payload);
        event.put("type", "agent_message");
        event.put("messageId", ExactWireIds.decimal(entity.getId()));
        event.put("conversationId", conversationId);
        event.put("conversationType", conversationType);
        event.put("agentId", agentId);
        event.put("senderType", "agent");
        event.put("senderName", senderName);
        event.put("content", content);
        sendEvent(session, "agent_message_saved", event);
        broadcastEvent("agent_message", event);
        chatConversationEventBroker.publish(conversationId, event);
    }

    private void publishAgentMessageDelta(WebSocketSession session, Map<String, Object> payload) {
        String conversationId = asString(payload.get("conversationId"));
        String content = asString(payload.get("content"));
        Set<String> registeredAgentIds = registeredAgentIds(session.getId());
        String agentId = requireAllowedSessionAgentId(session, payload);
        if (agentId == null) {
            return;
        }
        if (conversationId == null || conversationId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        if (registeredAgentIds.isEmpty() || agentId == null || !registeredAgentIds.contains(agentId)) {
            return;
        }

        String conversationType = Optional.ofNullable(asString(payload.get("conversationType"))).orElse("juyiting");
        String senderName = Optional.ofNullable(asString(payload.get("senderName")))
                .orElse(Optional.ofNullable(asString(payload.get("agentName"))).orElse(agentId));

        Map<String, Object> event = copyTrace(payload);
        event.put("type", "agent_message_delta");
        event.put("conversationId", conversationId);
        event.put("conversationType", conversationType);
        event.put("agentId", agentId);
        event.put("senderType", "agent");
        event.put("senderName", senderName);
        event.put("content", content);
        putIfPresent(event, "phase", payload.get("phase"));
        putIfPresent(event, "chunkIndex", payload.get("chunkIndex"));
        putIfPresent(event, "chunkCount", payload.get("chunkCount"));
        chatConversationEventBroker.publish(conversationId, event);
    }

    private String streamKey(WebSocketSession session, String requestId, String conversationId) {
        return session.getId() + ":" + Optional.ofNullable(requestId).orElse(conversationId);
    }

    private Map<String, Object> copyTrace(Map<String, Object> payload) {
        Map<String, Object> trace = new HashMap<>();
        putIfPresent(trace, "schemaVersion", payload.get("schemaVersion"));
        putIfPresent(trace, "messageId", payload.get("messageId"));
        putIfPresent(trace, "commandId", payload.get("commandId"));
        putIfPresent(trace, "correlationId", payload.get("correlationId"));
        putIfPresent(trace, "causationId", payload.get("causationId"));
        putIfPresent(trace, "requestId", payload.get("requestId"));
        putIfPresent(trace, "conversationId", payload.get("conversationId"));
        putIfPresent(trace, "conversationType", payload.get("conversationType"));
        putIfPresent(trace, "taskId", payload.get("taskId"));
        putIfPresent(trace, "workItemId", payload.get("workItemId"));
        putIfPresent(trace, "sourceAgentId", payload.get("sourceAgentId"));
        putIfPresent(trace, "targetAgentId", payload.get("targetAgentId"));
        putIfPresent(trace, "runtimeInstanceId", payload.get("runtimeInstanceId"));
        putIfPresent(trace, "agent", payload.get("agent"));
        return trace;
    }

    private void sendError(WebSocketSession session, Map<String, Object> payload, String message) {
        sendError(session, payload, null, message);
    }

    private void sendError(WebSocketSession session, Map<String, Object> payload, String code, String message) {
        Map<String, Object> event = copyTrace(payload);
        putIfPresent(event, "code", code);
        event.put("message", message);
        sendEvent(session, "error", event);
    }

    private void sendProtocolError(WebSocketSession session, Map<String, Object> payload, String code,
            String message) {
        Map<String, Object> event = copyTrace(payload);
        event.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        event.put("messageType", AgentProtocolConstants.TYPE_PROTOCOL_ERROR);
        event.put("code", code);
        event.put("message", message);
        sendEvent(session, "protocol_error", event);
    }

    private boolean sendEvent(WebSocketSession session, String type, Map<String, ?> payload) {
        if (!session.isOpen()) {
            return false;
        }
        Map<String, Object> event = new HashMap<>();
        if (payload != null) {
            event.putAll(payload);
        }
        event.put("type", type);
        event.put("channel", CHANNEL);
        if (event.containsKey("sentAt")) {
            event.put("timestamp", event.get("sentAt"));
        } else {
            event.putIfAbsent("timestamp", System.currentTimeMillis());
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(JsonUtil.toSafeJson(event)));
            }
            return true;
        } catch (Exception e) {
            log.error("Error sending OpenClaw channel event", e);
            return false;
        }
    }

    @Override
    public void publishAgentStatus(String clientId, String ownerJiacn, AgentRuntimeDTO agent) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("clientId", clientId);
        payload.put("tenantId", ownerJiacn);
        payload.put("agentId", agent.getAgentId());
        payload.put("status", agent.getStatus());
        payload.put("errorMessage", agent.getErrorMessage());
        payload.put("updatedAt", Optional.ofNullable(agent.getLastSeenAt()).orElse(System.currentTimeMillis()));
        if (agent.getCurrentTaskId() != null || agent.getCurrentTaskTitle() != null) {
            payload.put("currentTask", Map.of(
                    "id", Optional.ofNullable(agent.getCurrentTaskId()).orElse(""),
                    "title", Optional.ofNullable(agent.getCurrentTaskTitle()).orElse("")));
        }
        if (hallAnnouncementService != null) {
            hallAnnouncementService.recordAgentStatus(payload);
        }
        broadcastEventToScope(clientId, ownerJiacn, "agent_status", payload);
    }

    @Override
    public void publishTaskEvent(String eventType, AgentTaskDTO task) {
        if (task == null || isBlank(task.getTenantId()) || isBlank(task.getClientId()) || isBlank(task.getId())) {
            log.warn("Refusing task event without explicit scope, eventType={}, taskId={}",
                    eventType, task == null ? null : task.getId());
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("messageType", AgentProtocolConstants.TYPE_TASK_EVENT);
        payload.put("correlationId", task.getId());
        payload.put("tenantId", task.getTenantId());
        payload.put("clientId", task.getClientId());
        payload.put("eventType", eventType);
        payload.put("taskId", task.getId());
        payload.put("title", task.getTitle());
        payload.put("status", task.getStatus());
        payload.put("assignedAgentId", task.getAssignedAgentId());
        payload.put("assignedAgentName", task.getAssignedAgentName());
        payload.put("updatedAt", Optional.ofNullable(task.getUpdatedAt()).orElse(System.currentTimeMillis()));
        if (hallAnnouncementService != null) {
            hallAnnouncementService.recordTaskEvent(eventType, payload);
        }

        Set<String> memberAgentIds = resolveTaskMemberAgentIds(
                task.getTenantId(), task.getClientId(), task.getId());
        for (String memberAgentId : memberAgentIds) {
            Map<String, Object> targetedPayload = new HashMap<>(payload);
            targetedPayload.put("targetAgentId", memberAgentId);
            targetedPayload.put("agentId", memberAgentId);
            sendDirectMessageToAgent(memberAgentId, targetedPayload, memberAgentIds);
        }
    }

    @Override
    public void publishCapabilityIndex(String clientId, String ownerJiacn,
            List<AgentCapabilityDTO> capabilities) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("clientId", clientId);
        payload.put("tenantId", ownerJiacn);
        payload.put("agents", Optional.ofNullable(capabilities).orElseGet(List::of));
        payload.put("updatedAt", System.currentTimeMillis());
        broadcastEventToScope(clientId, ownerJiacn, "agent_capability_index", payload);
    }

    @Override
    public AgentActionDispatchResultDTO publishAgentAction(AgentActionIntentDTO intent) {
        String validationError = validateAgentActionIntent(intent);
        if (validationError != null) {
            AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
            result.setIntentId(intent == null ? null : intent.getIntentId());
            result.setTaskId(intent == null ? null : intent.getTaskId());
            result.setTargetAgentId(intent == null ? null : intent.getActorAgentId());
            result.setStatus("failed");
            result.setMessage(validationError);
            return result;
        }
        Map<String, Object> payload = buildAgentActionPayload(intent);
        boolean delivered = sendDirectMessageToAgent(intent.getActorAgentId(), payload);
        AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
        result.setIntentId(intent.getIntentId());
        result.setTaskId(intent.getTaskId());
        result.setTargetAgentId(intent.getActorAgentId());
        result.setStatus(delivered ? "dispatched" : "queued");
        result.setMessage(delivered ? "dispatched" : "Agent offline, outside scope, or not a task member");
        if (delivered) {
            result.setDispatchedAt(System.currentTimeMillis());
        }
        return result;
    }

    private String validateAgentActionIntent(AgentActionIntentDTO intent) {
        if (intent == null) {
            return "intent is required";
        }
        if (isBlank(intent.getActorAgentId())) {
            return "actorAgentId is required";
        }
        if (isBlank(intent.getTenantId())) {
            return "tenantId is required";
        }
        if (isBlank(intent.getClientId())) {
            return "clientId is required";
        }
        if (isBlank(intent.getTaskId())) {
            return "taskId is required";
        }
        return null;
    }

    private Map<String, Object> buildAgentActionPayload(AgentActionIntentDTO intent) {
        String commandId = Optional.ofNullable(intent.getCommandId())
                .orElseGet(() -> Optional.ofNullable(intent.getIntentId()).orElseGet(() -> UUID.randomUUID().toString()));
        String commandType = Optional.ofNullable(intent.getCommandType())
                .orElseGet(() -> AgentProtocolConstants.commandTypeForLegacyAction(intent.getActionType()));
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", AgentProtocolConstants.LEGACY_AGENT_ACTION);
        payload.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.put("messageId", messageId);
        payload.put("messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        payload.put("commandId", commandId);
        payload.put("commandType", commandType);
        payload.put("requestId", messageId);
        payload.put("correlationId", Optional.ofNullable(intent.getCorrelationId()).orElse(intent.getTaskId()));
        putIfPresent(payload, "causationId", intent.getCausationId());
        payload.put("tenantId", intent.getTenantId());
        payload.put("clientId", intent.getClientId());
        payload.put("conversationId", Optional.ofNullable(intent.getTaskId()).orElse(intent.getIntentId()));
        payload.put("conversationType", Optional.ofNullable(intent.getConversationType()).orElse("juyiting"));
        payload.put("taskId", intent.getTaskId());
        payload.put("targetAgentId", intent.getActorAgentId());
        payload.put("agentId", intent.getActorAgentId());
        payload.put("actionType", intent.getActionType());
        payload.put("content", intent.getInstruction());
        payload.put("issuedAt", Optional.ofNullable(intent.getCreatedAt()).orElse(System.currentTimeMillis()));
        putIfPresent(payload, "expiresAt", intent.getExpiresAt());

        Map<String, Object> metadata = new HashMap<>();
        putIfPresent(metadata, "taskId", intent.getTaskId());
        putIfPresent(metadata, "targetAgentIds", intent.getTargetAgentIds());
        putIfPresent(metadata, "reason", intent.getReason());
        putIfPresent(metadata, "autonomyLevel", intent.getAutonomyLevel());
        putIfPresent(metadata, "requiresApproval", intent.getRequiresApproval());
        putIfPresent(metadata, "context", intent.getContext());
        metadata.put("autonomy", true);
        payload.put("metadata", metadata);
        payload.put("payload", Map.of(
                "actionType", Optional.ofNullable(intent.getActionType()).orElse(""),
                "instruction", Optional.ofNullable(intent.getInstruction()).orElse(""),
                "metadata", metadata));
        return payload;
    }

    private void broadcastEvent(String type, Map<String, ?> payload) {
        sessions.values().forEach(session -> sendEvent(session, type, payload));
    }

    private void broadcastEventToScope(String clientId, String ownerJiacn,
            String type, Map<String, ?> payload) {
        if (isBlank(clientId) || isBlank(ownerJiacn)) {
            log.warn("Refusing Agent broadcast without explicit scope, type={}", type);
            return;
        }
        sessions.values().stream()
                .filter(session -> clientId.equals(sessionClientId(session))
                        && ownerJiacn.equals(sessionJiacn(session)))
                .forEach(session -> sendEvent(session, type, payload));
    }

    @Override
    public AgentRawCommandDispatchResult dispatchExactRawCommand(
            String tenantId,
            String clientId,
            String taskId,
            String targetAgentId,
            byte[] rawWireBytes) {
        if (!validExactDispatchId(tenantId, 50)
                || !validExactDispatchId(clientId, 50)
                || !validExactDispatchId(taskId, 100)
                || !validExactDispatchId(targetAgentId, 100)
                || !validRawCommandEnvelope(
                        tenantId, clientId, taskId, targetAgentId, rawWireBytes)) {
            log.warn("Refusing invalid exact-scope raw Agent command dispatch");
            return AgentRawCommandDispatchResult.rejected();
        }

        byte[] raw = java.util.Arrays.copyOf(rawWireBytes, rawWireBytes.length);
        int matchingSessions = 0;
        int sentSessions = 0;
        for (Map.Entry<String, Set<String>> entry : successfullyRegisteredAgentIds.entrySet()) {
            String sessionId = entry.getKey();
            if (!entry.getValue().contains(targetAgentId)
                    || !registeredAgentIds(sessionId).contains(targetAgentId)) {
                continue;
            }
            WebSocketSession session = sessions.get(sessionId);
            if (session == null || !session.isOpen()
                    || !targetAgentId.equals(sessionAgentId(session))
                    || !tenantId.equals(sessionJiacn(session))
                    || !clientId.equals(sessionClientId(session))) {
                continue;
            }
            matchingSessions++;
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(raw));
                }
                sentSessions++;
            } catch (Exception sendFailure) {
                log.warn("Exact-scope raw Agent command WebSocket send failed");
            }
        }
        if (matchingSessions == 0) {
            return AgentRawCommandDispatchResult.offline();
        }
        if (sentSessions == 0) {
            return AgentRawCommandDispatchResult.sendFailed(matchingSessions);
        }
        return AgentRawCommandDispatchResult.sent(matchingSessions, sentSessions);
    }

    private boolean validRawCommandEnvelope(
            String tenantId,
            String clientId,
            String taskId,
            String targetAgentId,
            byte[] rawWireBytes) {
        if (rawWireBytes == null || rawWireBytes.length == 0
                || rawWireBytes.length > cn.jia.agent.common.AgentCommandAmqpContract.MAX_WIRE_BYTES
                || !validUtf8(rawWireBytes)) {
            return false;
        }
        try {
            JsonNode root = STRICT_RAW_COMMAND_JSON.readTree(rawWireBytes);
            if (root == null || !root.isObject()) return false;
            String commandType = textJson(root, "commandType");
            if (!integralJsonEquals(root, "schemaVersion", AgentProtocolConstants.VERSION_1)
                    || !AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(textJson(root, "messageType"))
                    || !tenantId.equals(textJson(root, "tenantId"))
                    || !clientId.equals(textJson(root, "clientId"))
                    || !taskId.equals(textJson(root, "taskId"))
                    || !targetAgentId.equals(textJson(root, "targetAgentId"))
                    || !validExactDispatchId(textJson(root, "messageId"), 100)
                    || !validExactDispatchId(textJson(root, "commandId"), 100)
                    || !validExactDispatchId(commandType, 64)
                    || !positiveIntegralJson(root, "attempt")
                    || !positiveIntegralJson(root, "expiresAt")
                    || root.has("eventId") || root.has("deliveryId")
                    || (root.has("agentId")
                            && !targetAgentId.equals(textJson(root, "agentId")))
                    || !validTaskInviteCompatibility(
                            root, commandType, taskId, targetAgentId)) {
                return false;
            }
            JsonNode payload = root.get("payload");
            return nestedJsonMatches(payload, "tenantId", tenantId)
                    && nestedJsonMatches(payload, "clientId", clientId)
                    && nestedJsonMatches(payload, "taskId", taskId)
                    && nestedJsonMatches(payload, "targetAgentId", targetAgentId)
                    && nestedJsonMatches(payload, "agentId", targetAgentId);
        } catch (Exception malformed) {
            return false;
        }
    }

    private boolean validTaskInviteCompatibility(
            JsonNode root, String commandType, String taskId, String targetAgentId) {
        boolean hallTaskInvite = AgentProtocolConstants.COMMAND_TASK_INVITE.equals(commandType)
                && root.has("intentId");
        boolean compatibilityDeclared = root.has("type") || root.has("actionType")
                || root.has("content") || root.has("metadata");
        if (!hallTaskInvite) {
            return !root.has("type") && !root.has("actionType")
                    && !root.has("content") && !root.has("metadata");
        }
        if (!compatibilityDeclared) return true;
        JsonNode payload = root.get("payload");
        JsonNode metadata = root.get("metadata");
        return AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE.equals(textJson(root, "type"))
                && targetAgentId.equals(textJson(root, "agentId"))
                && payload != null && payload.isObject()
                && java.util.Objects.equals(
                        textJson(root, "actionType"), textJson(payload, "actionType"))
                && java.util.Objects.equals(
                        textJson(root, "content"), textJson(payload, "instruction"))
                && metadata != null && metadata.isObject() && metadata.size() == 6
                && taskId.equals(textJson(metadata, "taskId"))
                && java.util.Objects.equals(metadata.get("reason"), payload.get("reason"))
                && java.util.Objects.equals(
                        metadata.get("autonomyLevel"), payload.get("autonomyLevel"))
                && java.util.Objects.equals(
                        metadata.get("requiresApproval"), payload.get("requiresApproval"))
                && java.util.Objects.equals(metadata.get("context"), payload.get("context"))
                && metadata.path("autonomy").isBoolean()
                && metadata.path("autonomy").booleanValue();
    }

    private boolean validUtf8(byte[] rawWireBytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawWireBytes));
            return true;
        } catch (CharacterCodingException malformed) {
            return false;
        }
    }

    private boolean nestedJsonMatches(JsonNode payload, String field, String expected) {
        if (payload == null || !payload.isObject() || !payload.has(field)) {
            return true;
        }
        return expected.equals(textJson(payload, field));
    }

    private String textJson(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private boolean integralJsonEquals(JsonNode root, String field, long expected) {
        JsonNode value = root.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() == expected;
    }

    private boolean positiveIntegralJson(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() > 0;
    }

    private boolean validExactDispatchId(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    public boolean sendDirectMessageToAgent(String agentId, Map<String, ?> payload) {
        return sendDirectMessageToAgent(agentId, payload, null);
    }

    private boolean sendDirectMessageToAgent(String agentId, Map<String, ?> payload,
            Set<String> trustedTaskMemberAgentIds) {
        if (isBlank(agentId)) {
            return false;
        }
        Map<String, Object> outbound = prepareDirectOutboundPayload(agentId, payload);
        if (outbound == null) {
            return false;
        }
        String messageType = asString(outbound.get("messageType"));
        TaskDeliveryScope taskScope = taskDeliveryScope(outbound);
        if (requiresTaskScope(messageType)) {
            Set<String> memberAgentIds = trustedTaskMemberAgentIds == null
                    ? resolveTaskMemberAgentIds(taskScope.tenantId(), taskScope.clientId(), taskScope.taskId())
                    : trustedTaskMemberAgentIds;
            if (!memberAgentIds.contains(agentId)) {
                log.warn("Refusing task delivery to non-member, tenantId={}, clientId={}, taskId={}, agentId={}",
                        taskScope.tenantId(), taskScope.clientId(), taskScope.taskId(), agentId);
                return false;
            }
        }

        String outerType = directCompatibilityType(messageType)
                ? AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE
                : safeCanonicalOutboundType(messageType);
        if (outerType == null) {
            log.warn("Refusing unsafe Agent direct delivery, agentId={}, messageType={}", agentId, messageType);
            return false;
        }

        boolean commandDispatch = AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(messageType);
        Map<String, Set<String>> candidateSessionAgentIds = commandDispatch
                ? successfullyRegisteredAgentIds : sessionAgentIds;
        boolean delivered = false;
        for (Map.Entry<String, Set<String>> entry : candidateSessionAgentIds.entrySet()) {
            String sessionId = entry.getKey();
            if (!entry.getValue().contains(agentId)
                    || (commandDispatch && !registeredAgentIds(sessionId).contains(agentId))) {
                continue;
            }
            WebSocketSession session = sessions.get(sessionId);
            if (session == null || !session.isOpen() || !agentId.equals(sessionAgentId(session))) {
                continue;
            }
            if (taskScope != null && (!taskScope.tenantId().equals(sessionJiacn(session))
                    || !taskScope.clientId().equals(sessionClientId(session)))) {
                continue;
            }
            delivered = sendEvent(session, outerType, outbound) || delivered;
        }
        return delivered;
    }

    private boolean directCompatibilityType(String messageType) {
        return AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(messageType)
                || AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(messageType);
    }

    private String safeCanonicalOutboundType(String messageType) {
        if (!AgentProtocolConstants.isCanonicalType(messageType)
                || AgentProtocolConstants.TYPE_TASK_ASSIGN_LEGACY.equals(messageType)) {
            return null;
        }
        return messageType;
    }

    private Map<String, Object> prepareDirectOutboundPayload(String agentId, Map<String, ?> source) {
        Map<String, Object> payload = new HashMap<>();
        if (source != null) {
            payload.putAll(source);
        }
        String legacyType = asString(payload.get("type"));
        String messageType = asString(payload.get("messageType"));
        if (payload.containsKey("messageType") && !AgentProtocolConstants.isCanonicalType(messageType)) {
            log.warn("Refusing Agent direct delivery with invalid explicit messageType, agentId={}", agentId);
            return null;
        }
        if (!AgentProtocolConstants.isCanonicalType(messageType)) {
            if (AgentProtocolConstants.LEGACY_AGENT_ACTION.equals(legacyType)) {
                messageType = AgentProtocolConstants.TYPE_COMMAND_DISPATCH;
            } else if (AgentProtocolConstants.LEGACY_TASK_EVENT.equals(legacyType)
                    || "task_assigned".equals(legacyType)) {
                messageType = AgentProtocolConstants.TYPE_TASK_EVENT;
            } else if (AgentProtocolConstants.TYPE_TASK_ASSIGN_LEGACY.equals(legacyType)) {
                messageType = AgentProtocolConstants.TYPE_TASK_ASSIGN_LEGACY;
            } else {
                messageType = AgentProtocolConstants.TYPE_CHAT_MESSAGE;
            }
        }
        if (requiresTaskScope(messageType) && !hasConsistentTaskScope(payload, agentId)) {
            log.warn("Refusing task-scoped Agent delivery with missing or conflicting scope, agentId={}, messageType={}",
                    agentId, messageType);
            return null;
        }
        payload.putIfAbsent("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.putIfAbsent("messageId", UUID.randomUUID().toString());
        payload.put("messageType", messageType);
        payload.put("targetAgentId", agentId);
        payload.put("agentId", agentId);
        if (requiresTaskScope(messageType)) {
            TaskDeliveryScope trustedScope = taskDeliveryScope(payload);
            payload.put("tenantId", trustedScope.tenantId());
            payload.put("clientId", trustedScope.clientId());
            payload.put("taskId", trustedScope.taskId());
        }
        if (AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(messageType)) {
            payload.putIfAbsent("commandId", Optional.ofNullable(asString(payload.get("requestId")))
                    .orElseGet(() -> UUID.randomUUID().toString()));
            payload.putIfAbsent("commandType", AgentProtocolConstants.commandTypeForLegacyAction(
                    asString(payload.get("actionType"))));
        }
        return payload;
    }

    private boolean hasConsistentTaskScope(Map<String, Object> payload, String trustedTargetAgentId) {
        String tenantId = strictString(payload.get("tenantId"));
        String clientId = strictString(payload.get("clientId"));
        String taskId = strictString(payload.get("taskId"));
        String targetAgentId = strictString(payload.get("targetAgentId"));
        if (isBlank(tenantId) || isBlank(clientId) || isBlank(taskId) || isBlank(targetAgentId)
                || !trustedTargetAgentId.equals(targetAgentId)) {
            return false;
        }
        String agentId = strictString(payload.get("agentId"));
        if (!isBlank(agentId) && !trustedTargetAgentId.equals(agentId)) {
            return false;
        }
        return nestedFieldMatches(payload, "tenantId", tenantId)
                && nestedFieldMatches(payload, "clientId", clientId)
                && nestedFieldMatches(payload, "taskId", taskId)
                && nestedFieldMatches(payload, "targetAgentId", trustedTargetAgentId);
    }

    private boolean nestedFieldMatches(Map<String, Object> payload, String field, String expectedValue) {
        Object nestedPayload = payload.get("payload");
        if (!(nestedPayload instanceof Map<?, ?> nested) || !nested.containsKey(field)) {
            return true;
        }
        return expectedValue.equals(strictString(nested.get(field)));
    }

    private TaskDeliveryScope taskDeliveryScope(Map<String, Object> payload) {
        String messageType = asString(payload.get("messageType"));
        if (!requiresTaskScope(messageType)) {
            return null;
        }
        return new TaskDeliveryScope(
                asString(payload.get("tenantId")),
                asString(payload.get("clientId")),
                asString(payload.get("taskId")));
    }

    private boolean requiresTaskScope(String messageType) {
        return TASK_SCOPED_OUTBOUND_TYPES.contains(messageType);
    }

    private Set<String> resolveTaskMemberAgentIds(String tenantId, String clientId, String taskId) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            log.warn("Refusing task delivery because AgentService is unavailable, tenantId={}, clientId={}, taskId={}",
                    tenantId, clientId, taskId);
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(Optional.ofNullable(
                    agentService.listTaskMemberAgentIds(tenantId, clientId, taskId)).orElseGet(List::of));
        } catch (RuntimeException e) {
            log.warn("Refusing task delivery because task members could not be resolved, tenantId={}, clientId={}, taskId={}",
                    tenantId, clientId, taskId, e);
            return Set.of();
        }
    }

    private String strictString(Object value) {
        return value instanceof String text ? text : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public boolean isExactAgentConnected(String tenantId, String clientId, String targetAgentId) {
        if (isBlank(tenantId) || isBlank(clientId) || isBlank(targetAgentId)) return false;
        for (Map.Entry<String, Set<String>> entry : successfullyRegisteredAgentIds.entrySet()) {
            String sessionId = entry.getKey();
            if (!entry.getValue().contains(targetAgentId)
                    || !registeredAgentIds(sessionId).contains(targetAgentId)) {
                continue;
            }
            WebSocketSession session = sessions.get(sessionId);
            if (session != null && session.isOpen()
                    && targetAgentId.equals(sessionAgentId(session))
                    && tenantId.equals(sessionJiacn(session))
                    && clientId.equals(sessionClientId(session))) {
                return true;
            }
        }
        return false;
    }

    public boolean isAgentConnected(String tenantId, String clientId, String agentId) {
        if (isBlank(tenantId) || isBlank(clientId) || isBlank(agentId)) {
            return false;
        }
        for (Map.Entry<String, Set<String>> entry : sessionAgentIds.entrySet()) {
            if (!entry.getValue().contains(agentId)) {
                continue;
            }
            WebSocketSession session = sessions.get(entry.getKey());
            if (session != null && session.isOpen() && agentId.equals(sessionAgentId(session))
                    && tenantId.equals(sessionJiacn(session)) && clientId.equals(sessionClientId(session))) {
                return true;
            }
        }
        return false;
    }

    public boolean isAgentConnected(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        for (Map.Entry<String, Set<String>> entry : sessionAgentIds.entrySet()) {
            if (!entry.getValue().contains(agentId)) {
                continue;
            }
            WebSocketSession session = sessions.get(entry.getKey());
            if (session != null && session.isOpen()) {
                return true;
            }
        }
        return false;
    }

    public List<AgentRuntimeDTO> getConnectedAgents() {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            return List.of();
        }

        Set<String> connectedAgentIds = connectedAgentIds();

        List<AgentRuntimeDTO> connectedAgents = new ArrayList<>();
        for (String agentId : connectedAgentIds) {
            try {
                connectedAgents.add(agentService.get(agentId));
            } catch (Exception e) {
                log.warn("Unable to resolve connected agent {}", agentId, e);
            }
        }
        return connectedAgents;
    }

    @Override
    public Set<String> connectedAgentIds() {
        Set<String> connectedAgentIds = new LinkedHashSet<>();
        sessions.forEach((sessionId, session) -> {
            if (session.isOpen()) {
                String agentId = sessionAgentId(session);
                if (agentId != null && !agentId.isBlank()) {
                    connectedAgentIds.add(agentId);
                }
            }
        });
        return connectedAgentIds;
    }

    private void rememberSessionAgent(String sessionId, String agentId) {
        if (sessionId == null || sessionId.isBlank() || agentId == null || agentId.isBlank()) {
            return;
        }
        sessionAgentIds.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(agentId);
    }

    private Set<String> registeredAgentIds(String sessionId) {
        return Optional.ofNullable(sessionAgentIds.get(sessionId)).orElseGet(Set::of);
    }

    private void rememberSuccessfulRegistration(String sessionId, String agentId) {
        if (sessionId == null || sessionId.isBlank() || agentId == null || agentId.isBlank()) return;
        successfullyRegisteredAgentIds.computeIfAbsent(
                sessionId, key -> ConcurrentHashMap.newKeySet()).add(agentId);
    }

    private Set<String> successfullyRegisteredAgentIds(String sessionId) {
        return Optional.ofNullable(successfullyRegisteredAgentIds.get(sessionId)).orElseGet(Set::of);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String requireAllowedSessionAgentId(WebSocketSession session, Map<String, Object> payload) {
        String allowedAgentId = sessionAgentId(session);
        String requestedAgentId = asString(payload.get("agentId"));
        String sourceAgentId = asString(payload.get("sourceAgentId"));
        if (allowedAgentId == null || allowedAgentId.isBlank()) {
            sendSessionIdentityError(session, payload, "AGENT_ID_REQUIRED",
                    "agentId is required in WebSocket handshake");
            return null;
        }
        if (requestedAgentId != null && !requestedAgentId.isBlank()
                && sourceAgentId != null && !sourceAgentId.isBlank()
                && !requestedAgentId.equals(sourceAgentId)) {
            sendSessionIdentityError(session, payload, "AGENT_ID_CONFLICT",
                    "agentId and sourceAgentId must identify the same canonical Agent");
            return null;
        }
        String payloadAgentId = sourceAgentId == null || sourceAgentId.isBlank() ? requestedAgentId : sourceAgentId;
        if (payloadAgentId != null && !payloadAgentId.isBlank() && !allowedAgentId.equals(payloadAgentId)) {
            sendSessionIdentityError(session, payload, "AGENT_ID_MISMATCH",
                    "payload Agent identity does not match the WebSocket handshake");
            return null;
        }

        boolean protocolV1 = isProtocolV1(payload);
        boolean registration = AgentProtocolConstants.TYPE_AGENT_REGISTER.equals(asString(payload.get("messageType")));
        String runtimeInstanceId = asString(payload.get("runtimeInstanceId"));
        String currentRuntimeInstanceId = sessionRuntimeInstanceId(session);
        if (protocolV1 && (runtimeInstanceId == null || runtimeInstanceId.isBlank())) {
            sendSessionIdentityError(session, payload, "RUNTIME_INSTANCE_ID_REQUIRED",
                    "runtimeInstanceId is required for Protocol v1 Agent messages");
            return null;
        }
        if (runtimeInstanceId != null && !runtimeInstanceId.isBlank()) {
            if (runtimeInstanceId.equals(allowedAgentId)) {
                sendSessionIdentityError(session, payload, "RUNTIME_INSTANCE_ID_INVALID",
                        "runtimeInstanceId is a process identity and must not equal canonical agentId");
                return null;
            }
            if (currentRuntimeInstanceId == null || currentRuntimeInstanceId.isBlank()) {
                if (!registration || !registeredAgentIds(session.getId()).isEmpty()) {
                    sendSessionIdentityError(session, payload, "RUNTIME_INSTANCE_ID_NOT_FIXED",
                            "runtimeInstanceId must be fixed at WebSocket authentication or initial registration");
                    return null;
                }
                rememberSessionRuntimeInstance(session, runtimeInstanceId);
            } else if (!currentRuntimeInstanceId.equals(runtimeInstanceId)) {
                sendSessionIdentityError(session, payload, "RUNTIME_INSTANCE_ID_MISMATCH",
                        "runtimeInstanceId cannot change within one WebSocket session");
                return null;
            }
        }
        return allowedAgentId;
    }

    private boolean isProtocolV1(Map<String, Object> payload) {
        return Integer.valueOf(AgentProtocolConstants.VERSION_1).equals(payload.get("schemaVersion"));
    }

    private void sendSessionIdentityError(WebSocketSession session, Map<String, Object> payload,
            String code, String message) {
        if (isProtocolV1(payload)) {
            sendProtocolError(session, payload, code, message);
        } else {
            sendError(session, payload, code, message);
        }
    }

    private void rememberSessionRuntimeInstance(WebSocketSession session, String runtimeInstanceId) {
        if (session == null || runtimeInstanceId == null || runtimeInstanceId.isBlank()) {
            return;
        }
        sessionRuntimeInstanceIds.putIfAbsent(session.getId(), runtimeInstanceId);
    }

    private <T> T withSessionContext(WebSocketSession session, Supplier<T> action) {
        EsContext context = new EsContext();
        context.setClientId(sessionClientId(session));
        context.setJiacn(sessionJiacn(session));
        EsContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            EsContextHolder.setContext(new EsContext());
        }
    }

    private String sessionAgentId(WebSocketSession session) {
        return sessionAttribute(session, "agentId");
    }

    private String sessionRuntimeInstanceId(WebSocketSession session) {
        String remembered = sessionRuntimeInstanceIds.get(session.getId());
        if (remembered != null && !remembered.isBlank()) {
            return remembered;
        }
        String handshakeRuntimeInstanceId = sessionAttribute(session, "runtimeInstanceId");
        rememberSessionRuntimeInstance(session, handshakeRuntimeInstanceId);
        return handshakeRuntimeInstanceId;
    }

    private String sessionClientId(WebSocketSession session) {
        return sessionAttribute(session, "clientId");
    }

    private String sessionJiacn(WebSocketSession session) {
        return sessionAttribute(session, "jiacn");
    }

    private String sessionAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private String errorCode(Exception e) {
        try {
            Object code = e.getClass().getMethod("getCode").invoke(e);
            return code == null ? null : String.valueOf(code);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private java.util.List<String> asStringList(Object value) {
        if (!(value instanceof java.util.List<?> list)) {
            throw new IllegalArgumentException("abilities must be an array");
        }
        java.util.List<String> abilities = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String ability) || ability.isBlank()) {
                throw new IllegalArgumentException("abilities must contain non-blank strings");
            }
            abilities.add(ability);
        }
        return java.util.List.copyOf(abilities);
    }

    private record TaskDeliveryScope(String tenantId, String clientId, String taskId) {
    }

    private record StreamState(String sessionId, String requestId, String conversationId, Disposable disposable) {
        private void dispose() {
            disposable.dispose();
        }
    }
}
