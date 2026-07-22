package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.event.AgentEventPublisher;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
public class AgentWebSocketHandler extends TextWebSocketHandler implements AgentEventPublisher {
    private static final String CHANNEL = "agent";
    private static final TypeReference<Map<String, Object>> MESSAGE_TYPE = new TypeReference<>() {
    };

    private final ChatClient chatClient;
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final ChatMessageDao chatMessageDao;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final HallAnnouncementService hallAnnouncementService;
    private final AgentProtocolMessageNormalizer protocolMessageNormalizer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionAgentIds = new ConcurrentHashMap<>();
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

    @Autowired
    public AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer) {
        this.chatClient = chatClient;
        this.agentServiceProvider = agentServiceProvider;
        this.chatMessageDao = chatMessageDao;
        this.chatConversationEventBroker = chatConversationEventBroker;
        this.hallAnnouncementService = hallAnnouncementService;
        this.protocolMessageNormalizer = protocolMessageNormalizer;
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
            case AgentProtocolConstants.TYPE_WORK_PROGRESS,
                 AgentProtocolConstants.TYPE_WORK_HEARTBEAT,
                 AgentProtocolConstants.TYPE_HELP_REQUEST,
                 AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH,
                 AgentProtocolConstants.TYPE_COMMAND_ACK -> sendDeferredProtocolHandler(session, payload, normalized);
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
            request.setAbilities(asStringList(payload.get("abilities")));
            AgentRegisterResultDTO result = withSessionContext(session, () -> agentService.register(request));
            rememberSessionAgent(session.getId(), result.getAgentId());
            Map<String, Object> event = copyTrace(payload);
            event.put("agentId", result.getAgentId());
            putIfPresent(event, "runtimeInstanceId", sessionRuntimeInstanceId(session));
            event.put("status", result.getStatus());
            event.put("token", result.getToken());
            sendEvent(session, "agent_registered", event);
            sendCapabilityIndex(session, payload);
        } catch (Exception e) {
            sendError(session, payload, errorCode(e), e.getMessage());
        }
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
            Map<String, Object> event = copyTrace(payload);
            event.put("taskId", task.getId());
            event.put("status", task.getStatus());
            event.put("assignedAgentId", task.getAssignedAgentId());
            event.put("assignedAgentName", task.getAssignedAgentName());
            sendEvent(session, "task_assigned", event);
        } catch (Exception e) {
            sendError(session, payload, errorCode(e), e.getMessage());
        }
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
        event.put("messageId", entity.getId());
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

    private void sendEvent(WebSocketSession session, String type, Map<String, ?> payload) {
        if (!session.isOpen()) {
            return;
        }
        Map<String, Object> event = new HashMap<>();
        if (payload != null) {
            event.putAll(payload);
        }
        event.put("type", type);
        event.put("channel", CHANNEL);
        event.put("timestamp", System.currentTimeMillis());
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(JsonUtil.toSafeJson(event)));
            }
        } catch (Exception e) {
            log.error("Error sending OpenClaw channel event", e);
        }
    }

    @Override
    public void publishAgentStatus(AgentRuntimeDTO agent) {
        Map<String, Object> payload = new HashMap<>();
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
        broadcastEvent("agent_status", payload);
    }

    @Override
    public void publishTaskEvent(String eventType, AgentTaskDTO task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("messageType", AgentProtocolConstants.TYPE_TASK_EVENT);
        payload.put("correlationId", task.getId());
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
        broadcastEvent(AgentProtocolConstants.TYPE_TASK_EVENT, payload);
    }

    @Override
    public void publishCapabilityIndex(List<AgentCapabilityDTO> capabilities) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agents", Optional.ofNullable(capabilities).orElseGet(List::of));
        payload.put("updatedAt", System.currentTimeMillis());
        broadcastEvent("agent_capability_index", payload);
    }

    @Override
    public AgentActionDispatchResultDTO publishAgentAction(AgentActionIntentDTO intent) {
        if (intent == null || intent.getActorAgentId() == null || intent.getActorAgentId().isBlank()) {
            AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
            result.setStatus("failed");
            result.setMessage("actorAgentId is required");
            return result;
        }
        Map<String, Object> payload = buildAgentActionPayload(intent);
        boolean delivered = sendDirectMessageToAgent(intent.getActorAgentId(), payload);
        AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
        result.setIntentId(intent.getIntentId());
        result.setTaskId(intent.getTaskId());
        result.setTargetAgentId(intent.getActorAgentId());
        result.setStatus(delivered ? "dispatched" : "queued");
        result.setMessage(delivered ? "dispatched" : "Agent offline or not connected");
        if (delivered) {
            result.setDispatchedAt(System.currentTimeMillis());
        }
        return result;
    }

    private Map<String, Object> buildAgentActionPayload(AgentActionIntentDTO intent) {
        String commandId = Optional.ofNullable(intent.getCommandId())
                .orElseGet(() -> Optional.ofNullable(intent.getIntentId()).orElseGet(() -> UUID.randomUUID().toString()));
        String commandType = Optional.ofNullable(intent.getCommandType())
                .orElseGet(() -> AgentProtocolConstants.commandTypeForLegacyAction(intent.getActionType()));
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", AgentProtocolConstants.LEGACY_AGENT_ACTION);
        payload.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        payload.put("commandId", commandId);
        payload.put("commandType", commandType);
        payload.put("requestId", intent.getIntentId());
        payload.put("correlationId", Optional.ofNullable(intent.getCorrelationId()).orElse(intent.getTaskId()));
        putIfPresent(payload, "causationId", intent.getCausationId());
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

    public boolean sendDirectMessageToAgent(String agentId, Map<String, ?> payload) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        for (Map.Entry<String, Set<String>> entry : sessionAgentIds.entrySet()) {
            if (!entry.getValue().contains(agentId)) {
                continue;
            }
            WebSocketSession session = sessions.get(entry.getKey());
            if (session != null && session.isOpen()) {
                sendEvent(session, AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE,
                        prepareDirectOutboundPayload(agentId, payload));
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> prepareDirectOutboundPayload(String agentId, Map<String, ?> source) {
        Map<String, Object> payload = new HashMap<>();
        if (source != null) {
            payload.putAll(source);
        }
        String legacyType = asString(payload.get("type"));
        String messageType = asString(payload.get("messageType"));
        if (!AgentProtocolConstants.isCanonicalType(messageType)) {
            messageType = AgentProtocolConstants.LEGACY_AGENT_ACTION.equals(legacyType)
                    ? AgentProtocolConstants.TYPE_COMMAND_DISPATCH
                    : AgentProtocolConstants.TYPE_CHAT_MESSAGE;
        }
        payload.putIfAbsent("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.putIfAbsent("messageId", UUID.randomUUID().toString());
        payload.put("messageType", messageType);
        payload.put("targetAgentId", agentId);
        payload.putIfAbsent("agentId", agentId);
        if (AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(messageType)) {
            payload.putIfAbsent("commandId", Optional.ofNullable(asString(payload.get("requestId")))
                    .orElseGet(() -> UUID.randomUUID().toString()));
            payload.putIfAbsent("commandType", AgentProtocolConstants.commandTypeForLegacyAction(
                    asString(payload.get("actionType"))));
        }
        return payload;
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
            Set<String> agentIds = sessionAgentIds.get(sessionId);
            if (session.isOpen() && agentIds != null) {
                agentIds.stream()
                        .filter(agentId -> agentId != null && !agentId.isBlank())
                        .forEach(connectedAgentIds::add);
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
            sendError(session, payload, "AGENT_ID_REQUIRED", "agentId is required in WebSocket handshake");
            return null;
        }
        if (requestedAgentId != null && !requestedAgentId.isBlank()
                && sourceAgentId != null && !sourceAgentId.isBlank()
                && !requestedAgentId.equals(sourceAgentId)) {
            sendError(session, payload, "AGENT_ID_CONFLICT",
                    "agentId and sourceAgentId must identify the same canonical Agent");
            return null;
        }
        String payloadAgentId = sourceAgentId == null || sourceAgentId.isBlank() ? requestedAgentId : sourceAgentId;
        if (payloadAgentId != null && !payloadAgentId.isBlank() && !allowedAgentId.equals(payloadAgentId)) {
            sendError(session, payload, "AGENT_ID_MISMATCH",
                    "payload Agent identity does not match the WebSocket handshake");
            return null;
        }

        String runtimeInstanceId = asString(payload.get("runtimeInstanceId"));
        if (runtimeInstanceId != null && !runtimeInstanceId.isBlank()) {
            if (runtimeInstanceId.equals(allowedAgentId)) {
                sendError(session, payload, "RUNTIME_INSTANCE_ID_INVALID",
                        "runtimeInstanceId is a process identity and must not equal canonical agentId");
                return null;
            }
            String currentRuntimeInstanceId = sessionRuntimeInstanceId(session);
            if (currentRuntimeInstanceId != null && !currentRuntimeInstanceId.isBlank()
                    && !currentRuntimeInstanceId.equals(runtimeInstanceId)) {
                sendError(session, payload, "RUNTIME_INSTANCE_ID_MISMATCH",
                        "runtimeInstanceId cannot change within one WebSocket session");
                return null;
            }
            rememberSessionRuntimeInstance(session, runtimeInstanceId);
        }
        return allowedAgentId;
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

    @SuppressWarnings("unchecked")
    private java.util.List<String> asStringList(Object value) {
        if (value == null) {
            return java.util.Collections.emptyList();
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private record StreamState(String sessionId, String requestId, String conversationId, Disposable disposable) {
        private void dispose() {
            disposable.dispose();
        }
    }
}
