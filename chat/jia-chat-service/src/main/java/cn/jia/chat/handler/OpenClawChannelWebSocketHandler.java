package cn.jia.chat.handler;

import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentService;
import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket channel for external agent clients.
 *
 * <p>Inbound messages are JSON objects with a {@code type} field:
 * {@code chat}, {@code stop}, {@code ping}, {@code agent.register},
 * {@code agent.status}, {@code task.assign}, {@code task.report}. Chat
 * responses are streamed as {@code delta} events and finished with
 * {@code done}.</p>
 */
@Slf4j
@Component
public class OpenClawChannelWebSocketHandler extends TextWebSocketHandler implements AgentEventPublisher {
    private static final String CHANNEL = "agent";
    private static final TypeReference<Map<String, Object>> MESSAGE_TYPE = new TypeReference<>() {
    };

    private final ChatClient chatClient;
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, StreamState> runningStreams = new ConcurrentHashMap<>();

    public OpenClawChannelWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider) {
        this.chatClient = chatClient;
        this.agentServiceProvider = agentServiceProvider;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        sendEvent(session, "connected", Map.of(
                "sessionId", session.getId(),
                "channel", CHANNEL,
                "capabilities", new String[] {"chat.stream", "chat.stop", "ping", "agent.register",
                        "agent.status", "task.assign", "task.report", "task.event"}));
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

        String type = asString(payload.getOrDefault("type", "chat"));
        switch (type) {
            case "ping" -> sendEvent(session, "pong", copyTrace(payload));
            case "stop" -> stopStream(session, payload);
            case "chat" -> startChatStream(session, payload);
            case "agent.register", "agent_register" -> registerAgent(session, payload);
            case "agent.status", "agent_status_update" -> updateAgentStatus(session, payload);
            case "task.assign", "task_assign" -> assignTask(session, payload);
            case "task.report", "task_report" -> reportTask(session, payload);
            default -> sendError(session, payload, "Unsupported agent channel message type: " + type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        runningStreams.entrySet().removeIf(entry -> {
            StreamState stream = entry.getValue();
            if (session.getId().equals(stream.sessionId())) {
                stream.dispose();
                return true;
            }
            return false;
        });
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
            AgentRegisterDTO request = new AgentRegisterDTO();
            request.setAgentId(asString(payload.get("agentId")));
            request.setName(asString(payload.get("name")));
            request.setAvatar(asString(payload.get("avatar")));
            request.setPersonaName(asString(payload.get("personaName")));
            request.setEndpoint(asString(payload.get("endpoint")));
            request.setAbilities(asStringList(payload.get("abilities")));
            AgentRegisterResultDTO result = agentService.register(request);
            Map<String, Object> event = copyTrace(payload);
            event.put("agentId", result.getAgentId());
            event.put("status", result.getStatus());
            event.put("token", result.getToken());
            sendEvent(session, "agent_registered", event);
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
            String agentId = asString(payload.get("agentId"));
            AgentStatusDTO request = new AgentStatusDTO();
            request.setStatus(asString(payload.get("status")));
            request.setCurrentTaskId(asString(payload.get("currentTaskId")));
            request.setCurrentTaskTitle(asString(payload.get("currentTaskTitle")));
            request.setErrorMessage(asString(payload.get("errorMessage")));
            AgentRuntimeDTO agent = agentService.updateStatus(agentId, request);
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
            AgentTaskAssignDTO request = new AgentTaskAssignDTO();
            request.setAgentId(asString(payload.get("agentId")));
            request.setAllowQueue(asBoolean(payload.get("allowQueue")));
            AgentTaskDTO task = agentService.assignTask(taskId, request);
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
            AgentTaskReportDTO request = new AgentTaskReportDTO();
            request.setAgentId(asString(payload.get("agentId")));
            request.setStatus(asString(payload.get("status")));
            request.setCurrentTaskTitle(asString(payload.get("currentTaskTitle")));
            request.setFailureReason(asString(payload.get("failureReason")));
            AgentTaskDTO task = agentService.reportTask(taskId, request);
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

    private String streamKey(WebSocketSession session, String requestId, String conversationId) {
        return session.getId() + ":" + Optional.ofNullable(requestId).orElse(conversationId);
    }

    private Map<String, Object> copyTrace(Map<String, Object> payload) {
        Map<String, Object> trace = new HashMap<>();
        putIfPresent(trace, "requestId", payload.get("requestId"));
        putIfPresent(trace, "conversationId", payload.get("conversationId"));
        putIfPresent(trace, "conversationType", payload.get("conversationType"));
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

    private void sendEvent(WebSocketSession session, String type, Map<String, ?> payload) {
        if (!session.isOpen()) {
            return;
        }
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("channel", CHANNEL);
        event.put("timestamp", System.currentTimeMillis());
        if (payload != null) {
            event.putAll(payload);
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(JsonUtil.toJson(event)));
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
        broadcastEvent("agent_status", payload);
    }

    @Override
    public void publishTaskEvent(String eventType, AgentTaskDTO task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("taskId", task.getId());
        payload.put("title", task.getTitle());
        payload.put("status", task.getStatus());
        payload.put("assignedAgentId", task.getAssignedAgentId());
        payload.put("assignedAgentName", task.getAssignedAgentName());
        payload.put("updatedAt", Optional.ofNullable(task.getUpdatedAt()).orElse(System.currentTimeMillis()));
        broadcastEvent("task_event", payload);
    }

    private void broadcastEvent(String type, Map<String, ?> payload) {
        sessions.values().forEach(session -> sendEvent(session, type, payload));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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
