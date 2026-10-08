package cn.jia.chat.handler;

import cn.jia.chat.serialization.ExactWireIds;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCapabilityDTO;
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
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentExecutionReportService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.NativeBountyExecutionSessionLookup;
import cn.jia.agent.service.NativeProviderCredentialBindingLookup;
import cn.jia.agent.service.ControlledImageExecutionSessionLookup;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.AgentSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedAgentSender;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.ChatDeliberationOutboxService;
import cn.jia.chat.service.ConversationMetadataPolicy;
import cn.jia.chat.service.HallAnnouncementService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        implements AgentEventPublisher, AgentRawCommandDispatcher,
        NativeBountyExecutionSessionLookup, NativeProviderCredentialBindingLookup, ControlledImageExecutionSessionLookup,
        ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup {
    private static final String CHANNEL = "agent";
    private static final TypeReference<Map<String, Object>> MESSAGE_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper STRICT_RAW_COMMAND_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> EXECUTION_REPORT_TYPES = Set.of(
            AgentProtocolConstants.TYPE_WORK_PROGRESS,
            AgentProtocolConstants.TYPE_WORK_HEARTBEAT,
            AgentProtocolConstants.TYPE_WORK_RESULT,
            AgentProtocolConstants.TYPE_HELP_REQUEST,
            AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH);
    private static final String CODEX_EXECUTION_RESULT = "CODEX_EXECUTION_RESULT";

    private static final Set<String> TASK_SCOPED_OUTBOUND_TYPES = Set.of(
            AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
            AgentProtocolConstants.TYPE_COMMAND_ACK,
            AgentProtocolConstants.TYPE_WORK_PROGRESS,
            AgentProtocolConstants.TYPE_WORK_HEARTBEAT,
            AgentProtocolConstants.TYPE_WORK_RESULT,
            AgentProtocolConstants.TYPE_HELP_REQUEST,
            AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH,
            AgentProtocolConstants.TYPE_TASK_EVENT);

    private cn.jia.agent.security.AgentRuntimeAuthenticationService runtimeAuthentication;
    @Autowired
    public void setRuntimeAuthentication(cn.jia.agent.security.AgentRuntimeAuthenticationService service) {
        this.runtimeAuthentication = service;
    }

    private cn.jia.agent.skill.SkillInstallResultService skillResults;
    @Autowired
    public void setSkillResults(cn.jia.agent.skill.SkillInstallResultService results) { this.skillResults=results; }
    private Supplier<AgentExecutionReportService> executionReportServiceSupplier = () -> null;
    @Autowired
    public void setExecutionReportServiceProvider(ObjectProvider<AgentExecutionReportService> services) {
        this.executionReportServiceSupplier = services == null ? () -> null : services::getIfAvailable;
    }
    void setExecutionReportService(AgentExecutionReportService service) {
        this.executionReportServiceSupplier = () -> service;
    }
    private ChatConversationService chatConversationService;
    @Autowired
    public void setChatConversationService(ChatConversationService service) {
        this.chatConversationService = service;
    }
    private ChatDeliberationService chatDeliberationService;
    @Autowired(required = false)
    public void setChatDeliberationService(ChatDeliberationService service) {
        this.chatDeliberationService = service;
    }
    private ChatDeliberationOutboxService chatDeliberationOutboxService;
    private TypedDeliberationSessionRegistry typedDeliberationSessions;
    private TypedInspectionSessionRegistry typedInspectionSessions;
    @Autowired(required = false)
    public void setChatDeliberationOutboxService(ChatDeliberationOutboxService service) {
        this.chatDeliberationOutboxService = service;
    }
    @Autowired(required = false)
    public void setTypedDeliberationSessions(TypedDeliberationSessionRegistry sessions) {
        this.typedDeliberationSessions = sessions;
    }
    @Autowired(required = false)
    public void setTypedInspectionSessions(TypedInspectionSessionRegistry sessions) {
        this.typedInspectionSessions = sessions;
    }
    private final ChatClient chatClient;
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final ChatMessageDao chatMessageDao;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final HallAnnouncementService hallAnnouncementService;
    private final AgentProtocolMessageNormalizer protocolMessageNormalizer;
    private final Supplier<AgentCommandReconnectSignal> reconnectSignalSupplier;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionAgentIds = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> successfullyRegisteredAgentIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionRuntimeInstanceIds = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionReadyCommandTypes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionDurableStateHealthy = new ConcurrentHashMap<>();
    private final Map<String, AgentRuntimeCapabilities> sessionRuntimeCapabilities = new ConcurrentHashMap<>();
    private final Map<String, NativeBountyExecutionDeclaration> sessionNativeBountyExecution =
            new ConcurrentHashMap<>();
    private final Map<String, NativeProviderCredentialBindingDeclaration>
            sessionNativeProviderCredentialBinding = new ConcurrentHashMap<>();
    private final Map<String, ControlledImageBountyExecutionDeclaration>
            sessionControlledImageBountyExecution = new ConcurrentHashMap<>();
    private final Map<String, ControlledImageV3Declaration> sessionControlledImageV3 = new ConcurrentHashMap<>();
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
                (AgentCommandReconnectSignal) null);
    }

    @Autowired
    public AgentWebSocketHandler(@Lazy ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer,
                                 ObjectProvider<AgentCommandReconnectSignal> reconnectSignalProvider) {
        this(chatClient, agentServiceProvider, chatMessageDao, chatConversationEventBroker,
                hallAnnouncementService, protocolMessageNormalizer,
                reconnectSignalProvider == null ? () -> null : reconnectSignalProvider::getIfAvailable);
    }

    AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer,
                                 AgentCommandReconnectSignal reconnectSignal) {
        this(chatClient, agentServiceProvider, chatMessageDao, chatConversationEventBroker,
                hallAnnouncementService, protocolMessageNormalizer,
                () -> reconnectSignal);
    }

    private AgentWebSocketHandler(ChatClient chatClient, ObjectProvider<AgentService> agentServiceProvider,
                                 ChatMessageDao chatMessageDao, ChatConversationEventBroker chatConversationEventBroker,
                                 HallAnnouncementService hallAnnouncementService,
                                 AgentProtocolMessageNormalizer protocolMessageNormalizer,
                                 Supplier<AgentCommandReconnectSignal> reconnectSignalSupplier) {
        this.chatClient = chatClient;
        this.agentServiceProvider = agentServiceProvider;
        this.chatMessageDao = chatMessageDao;
        this.chatConversationEventBroker = chatConversationEventBroker;
        this.hallAnnouncementService = hallAnnouncementService;
        this.protocolMessageNormalizer = protocolMessageNormalizer;
        this.reconnectSignalSupplier = reconnectSignalSupplier;
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
                AgentProtocolConstants.TYPE_CHAT_MESSAGE_DELTA, AgentProtocolConstants.TYPE_CHAT_DISPATCH_ACK,
                AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                AgentProtocolConstants.TYPE_WORK_PROGRESS,
                AgentProtocolConstants.TYPE_WORK_HEARTBEAT, AgentProtocolConstants.TYPE_WORK_RESULT,
                AgentProtocolConstants.TYPE_HELP_REQUEST, AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH,
                AgentProtocolConstants.TYPE_TASK_EVENT, AgentProtocolConstants.TYPE_CAPABILITY_LOOKUP,
                "agent.status", "agent.message", "task.assign", "task.report"});
        sendEvent(session, "connected", connected);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Map<String, Object> payload = Map.of();
        try {
            if (runtimeAuthentication == null) throw new IllegalArgumentException("Runtime authority unavailable");
            runtimeAuthentication.validateCurrent(sessionProof(session), false);
            payload = objectMapper.readValue(message.getPayload(), MESSAGE_TYPE);
            validateSessionEnvelope(session, payload);
        } catch (cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException rejected) {
            sendSessionIdentityError(session, payload, rejected.getCode(), "Runtime message proof rejected");
            return;
        } catch (Exception e) {
            sendError(session, Map.of(), "Runtime message rejected");
            return;
        }

        // Dedicated durable skill branch BEFORE generic task normalization; never completes coding work.
        if ("SKILL_INSTALL_RESULT".equals(payload.get("resultType"))) {
            try {
                handleSkillInstallResult(session,STRICT_RAW_COMMAND_JSON.readValue(message.getPayload(),MESSAGE_TYPE));
            } catch (RuntimeException malformed) {
                sendProtocolError(session,Map.of(),"SKILL_RESULT_REJECTED","Malformed skill result");
            }
            return;
        }
        String declaredMessageType = strictString(payload.get("messageType"));
        String declaredLegacyType = strictString(payload.get("type"));
        Map<?, ?> declaredBody = payload.get("payload") instanceof Map<?, ?> body
                ? body : Map.of();
        boolean declaredRegistration = AgentProtocolConstants.TYPE_AGENT_REGISTER.equals(declaredMessageType)
                || AgentProtocolConstants.TYPE_AGENT_REGISTER.equals(declaredLegacyType)
                || AgentProtocolConstants.TYPE_AGENT_REGISTER.equals(
                        strictString(declaredBody.get("messageType")))
                || AgentProtocolConstants.TYPE_AGENT_REGISTER.equals(
                        strictString(declaredBody.get("type")));
        if (declaredRegistration) {
            try {
                // Registration declarations are authority inputs. Preserve strict duplicate-key
                // detection before the generic normalizer can collapse them into a Map.
                payload = STRICT_RAW_COMMAND_JSON.readValue(message.getPayload(), MESSAGE_TYPE);
            } catch (Exception malformed) {
                sendProtocolError(session, Map.of(), "AGENT_REGISTRATION_INVALID",
                        "Agent registration payload is invalid");
                return;
            }
        }
        boolean declaredDurableFinal = (AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(declaredMessageType)
                || AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(declaredLegacyType)
                || AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(strictString(declaredBody.get("messageType")))
                || AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(strictString(declaredBody.get("type"))))
                && (hasDurableTurnBinding(payload) || hasDurableTurnBinding(declaredBody));
        if (declaredDurableFinal) {
            try {
                payload = strictDurableFinalPayload(message.getPayload());
            } catch (Exception malformed) {
                sendProtocolError(session, Map.of(), "CHAT_TURN_FINAL_REJECTED",
                        "Durable chat final was rejected");
                return;
            }
        }
        if (declaredMessageType != null && EXECUTION_REPORT_TYPES.contains(declaredMessageType)) {
            try {
                Map<String, Object> strict = STRICT_RAW_COMMAND_JSON.readValue(
                        message.getPayload(), MESSAGE_TYPE);
                handleExecutionReport(session, strict, declaredMessageType);
            } catch (RuntimeException malformed) {
                sendReportError(session, payload, "REPORT_INVALID", "Execution report was rejected");
            } catch (Exception malformed) {
                sendReportError(session, payload, "REPORT_INVALID", "Execution report was rejected");
            }
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
            case AgentProtocolConstants.TYPE_CHAT_DISPATCH_ACK -> acknowledgeChatDispatch(session, payload);
            case AgentProtocolConstants.TYPE_TASK_ASSIGN_LEGACY -> assignTask(session, payload);
            case AgentProtocolConstants.TYPE_WORK_RESULT -> {
                if (normalized.legacyTaskReport()) {
                    reportTask(session, payload);
                } else {
                    handleExecutionReport(session, payload, normalized.canonicalType());
                }
            }
            case AgentProtocolConstants.TYPE_COMMAND_ACK -> sendProtocolError(session, payload,
                    "COMMAND_ACK_HTTP_REQUIRED", "Command ACK must be committed through Runtime HTTP");
            case AgentProtocolConstants.TYPE_WORK_PROGRESS,
                 AgentProtocolConstants.TYPE_WORK_HEARTBEAT,
                 AgentProtocolConstants.TYPE_HELP_REQUEST,
                 AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH ->
                    handleExecutionReport(session, payload, normalized.canonicalType());
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
        if (runtimeAuthentication != null) runtimeAuthentication.disconnect(session.getId());
        sessions.remove(session.getId());
        sessionAgentIds.remove(session.getId());
        successfullyRegisteredAgentIds.remove(session.getId());
        sessionRuntimeInstanceIds.remove(session.getId());
        sessionRuntimeCapabilities.remove(session.getId());
        sessionDurableStateHealthy.remove(session.getId());
        sessionReadyCommandTypes.remove(session.getId());
        sessionNativeBountyExecution.remove(session.getId());
        sessionNativeProviderCredentialBinding.remove(session.getId());
        sessionControlledImageBountyExecution.remove(session.getId());
        sessionControlledImageV3.remove(session.getId());
        removeTypedDeliberationSession(session.getId());
        removeTypedInspectionSession(session.getId());
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

    private void handleExecutionReport(WebSocketSession session, Map<String, Object> payload,
            String messageType) {
        String reportMessageId = exactReportString(payload.get("messageId"), 100);
        String agentId = sessionAgentId(session);
        String runtimeInstanceId = sessionRuntimeInstanceId(session);
        String ownerJiacn = sessionJiacn(session);
        String clientId = sessionClientId(session);
        boolean codexResult = AgentProtocolConstants.TYPE_WORK_RESULT.equals(messageType)
                && CODEX_EXECUTION_RESULT.equals(payload.get("resultType"));
        Set<String> businessFields = reportBusinessFields(messageType, codexResult);
        Set<String> allowed = new java.util.HashSet<>(Set.of(
                "schemaVersion", "messageType", "messageId",
                "sourceAgentId", "agentId", "targetAgentId", "runtimeInstanceId",
                "commandId", "correlationId"));
        allowed.addAll(businessFields);
        if (!codexResult) {
            allowed.addAll(Set.of("tenantId", "clientId", "ownerJiacn", "reportId",
                    "executionRef", "grantRevision", "attempt", "fencingToken",
                    "sequence", "occurredAt", "payload"));
        }
        if (!Integer.valueOf(AgentProtocolConstants.VERSION_1).equals(payload.get("schemaVersion"))
                || !messageType.equals(strictString(payload.get("messageType")))
                || reportMessageId == null || agentId == null || runtimeInstanceId == null
                || ownerJiacn == null || clientId == null || !allowed.containsAll(payload.keySet())
                || !validExactDispatchId(agentId, 100)
                || !validExactDispatchId(runtimeInstanceId, 100)
                || !validExactDispatchId(ownerJiacn, 50)
                || !validExactDispatchId(clientId, 50)
                || !successfullyRegisteredAgentIds(session.getId()).contains(agentId)
                || !exactReportSourceIdentity(payload, agentId, codexResult)
                || !agentId.equals(exactReportString(payload.get("targetAgentId"), 100))
                || !runtimeInstanceId.equals(exactReportString(payload.get("runtimeInstanceId"), 100))
                || declaredReportScopeConflict(payload, ownerJiacn, clientId)
                || exactReportString(payload.get("commandId"), 100) == null
                || exactReportString(payload.get("correlationId"), 100) == null) {
            sendReportError(session, payload, "REPORT_INVALID", "Execution report was rejected");
            return;
        }

        Map<String, Object> reportPayload = extractReportPayload(payload, businessFields, codexResult);
        if (reportPayload == null) {
            sendReportError(session, payload, "REPORT_INVALID", "Execution report was rejected");
            return;
        }
        String reportId = codexResult ? reportMessageId
                : exactReportString(payload.get("reportId"), 100);
        String executionRef = codexResult ? null
                : exactReportString(payload.get("executionRef"), 100);
        Long grantRevision = codexResult ? 0L : exactReportLong(payload.get("grantRevision"));
        Integer attempt = codexResult ? 0 : exactReportInteger(payload.get("attempt"));
        String fencingToken = codexResult ? null
                : exactReportString(payload.get("fencingToken"), 100);
        Long sequence = codexResult ? 0L : exactReportLong(payload.get("sequence"));
        Long occurredAt = codexResult ? 0L : exactReportLong(payload.get("occurredAt"));
        if (!codexResult && (reportId == null || executionRef == null || grantRevision == null
                || grantRevision < 1 || attempt == null || attempt < 1 || fencingToken == null
                || sequence == null || sequence < 1 || occurredAt == null || occurredAt < 1)) {
            sendReportError(session, payload, "REPORT_INVALID", "Execution report was rejected");
            return;
        }

        AgentExecutionReportService service = executionReportServiceSupplier.get();
        if (service == null) {
            sendReportError(session, payload, "REPORT_UNAVAILABLE",
                    "Execution report could not be committed");
            return;
        }
        try {
            AgentExecutionReportService.ReportReceipt receipt = runtimeAuthentication.withFence(sessionProof(session), false, () -> service.accept(
                    new AgentExecutionReportService.RuntimeScope(
                            "0", clientId, ownerJiacn, agentId, runtimeInstanceId),
                    new AgentExecutionReportService.ReportCommand(messageType, reportMessageId, reportId,
                            exactReportString(payload.get("commandId"), 100),
                            exactReportString(payload.get("correlationId"), 100), executionRef,
                            grantRevision == null ? 0L : grantRevision, attempt == null ? 0 : attempt,
                            fencingToken, sequence == null ? 0L : sequence,
                            occurredAt == null ? 0L : occurredAt, reportPayload)));
            sendExecutionReportReceipt(session, codexResult, reportMessageId, agentId,
                    exactReportString(payload.get("commandId"), 100), receipt);
        } catch (AgentExecutionReportService.Failure failure) {
            String code = switch (failure.reason()) {
                case INVALID_REQUEST -> "REPORT_INVALID";
                case NOT_FOUND -> "REPORT_NOT_FOUND";
                case CONFLICT -> "REPORT_CONFLICT";
                case STORAGE_UNAVAILABLE -> "REPORT_UNAVAILABLE";
            };
            String message = failure.reason() == AgentExecutionReportService.Reason.NOT_FOUND
                    ? "Execution report target was not found"
                    : failure.reason() == AgentExecutionReportService.Reason.CONFLICT
                            ? "Execution report conflicted with committed state"
                            : failure.reason() == AgentExecutionReportService.Reason.INVALID_REQUEST
                                    ? "Execution report was rejected"
                                    : "Execution report could not be committed";
            sendReportError(session, payload, code, message);
        } catch (RuntimeException unavailable) {
            sendReportError(session, payload, "REPORT_UNAVAILABLE",
                    "Execution report could not be committed");
        }
    }

    private Set<String> reportBusinessFields(String messageType, boolean codexResult) {
        if (codexResult) return Set.of("resultType", "status", "exitCode");
        return switch (messageType) {
            case AgentProtocolConstants.TYPE_WORK_PROGRESS -> Set.of("status", "summary", "percent");
            case AgentProtocolConstants.TYPE_WORK_HEARTBEAT -> Set.of("status");
            case AgentProtocolConstants.TYPE_WORK_RESULT -> Set.of("outcome", "exitCode",
                    "failureCode", "summary", "manifestDigest", "outputStageRefs");
            case AgentProtocolConstants.TYPE_HELP_REQUEST -> Set.of("reasonCode", "summary");
            case AgentProtocolConstants.TYPE_ARTIFACT_PUBLISH -> Set.of("artifactId",
                    "artifactVersion", "stageRef", "sha256", "byteLength", "contentType");
            default -> Set.of();
        };
    }

    private Map<String, Object> extractReportPayload(Map<String, Object> envelope,
            Set<String> businessFields, boolean codexResult) {
        Object nestedValue = envelope.get("payload");
        Map<?, ?> nested = nestedValue instanceof Map<?, ?> map ? map : Map.of();
        if (codexResult && nestedValue != null) return null;
        if (nestedValue != null && !(nestedValue instanceof Map<?, ?>)
                || !businessFields.containsAll(nested.keySet())) return null;
        Map<String, Object> projected = new HashMap<>();
        for (String field : businessFields) {
            Object outer = envelope.get(field);
            Object inner = nested.get(field);
            if (outer != null && inner != null && !java.util.Objects.deepEquals(outer, inner)) return null;
            Object value = outer != null ? outer : inner;
            if (value != null) projected.put(field, value);
        }
        return Map.copyOf(projected);
    }

    private boolean declaredReportScopeConflict(Map<String, Object> payload,
            String ownerJiacn, String clientId) {
        return payload.containsKey("tenantId") && !"0".equals(strictString(payload.get("tenantId")))
                || payload.containsKey("clientId")
                        && !clientId.equals(strictString(payload.get("clientId")))
                || payload.containsKey("ownerJiacn")
                        && !ownerJiacn.equals(strictString(payload.get("ownerJiacn")));
    }

    private boolean exactReportSourceIdentity(Map<String, Object> payload, String expected,
            boolean requireAgentAlias) {
        if (!expected.equals(exactReportString(payload.get("sourceAgentId"), 100))) return false;
        if (requireAgentAlias) {
            return expected.equals(exactReportString(payload.get("agentId"), 100));
        }
        return !payload.containsKey("agentId")
                || expected.equals(exactReportString(payload.get("agentId"), 100));
    }

    private String exactReportString(Object value, int maxLength) {
        if (!(value instanceof String text) || !validExactDispatchId(text, maxLength)) return null;
        return text;
    }

    private Long exactReportLong(Object value) {
        if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,15}")) return null;
        try { return Long.valueOf(text); } catch (NumberFormatException invalid) { return null; }
    }

    private Integer exactReportInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer)) return null;
        return ((Number) value).intValue();
    }

    private void sendExecutionReportReceipt(WebSocketSession session, boolean codexResult,
            String reportMessageId, String targetAgentId, String commandId,
            AgentExecutionReportService.ReportReceipt receipt) {
        Map<String, Object> event = new HashMap<>();
        event.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        event.put("messageType", codexResult ? "work.result.receipt" : "work.report.receipt");
        event.put("messageId", reportReceiptMessageId(reportMessageId, receipt.resultRef()));
        event.put("correlationId", reportMessageId);
        event.put("commandId", commandId);
        event.put("targetAgentId", targetAgentId);
        event.put("resultRef", receipt.resultRef());
        event.put("committedVersion", receipt.committedVersion());
        event.put("duplicate", receipt.duplicate());
        if (codexResult) {
            event.put("resultType", CODEX_EXECUTION_RESULT);
            event.put("receiptStatus", "ACCEPTED");
        } else {
            event.put("reportId", receipt.reportId());
        }
        sendEvent(session, codexResult ? "work.result.receipt" : "work.report.receipt", event);
    }

    private String reportReceiptMessageId(String reportMessageId, String resultRef) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    ("report-receipt\n" + reportMessageId + "\n" + resultRef)
                            .getBytes(StandardCharsets.UTF_8));
            return "wrr_" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void sendReportError(WebSocketSession session, Map<String, Object> payload,
            String code, String message) {
        Map<String, Object> event = new HashMap<>();
        event.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        event.put("messageType", AgentProtocolConstants.TYPE_PROTOCOL_ERROR);
        String messageId = exactReportString(payload == null ? null : payload.get("messageId"), 100);
        if (messageId != null) event.put("correlationId", messageId);
        event.put("code", code);
        event.put("message", message);
        event.put("cacheControl", "private, no-store");
        sendEvent(session, "protocol_error", event);
    }

    private void startChatStream(WebSocketSession session, Map<String, Object> payload) {
        String agentId = requireAllowedSessionAgentId(session, payload);
        if (agentId == null || !registeredAgentIds(session.getId()).contains(agentId)) {
            sendError(session, payload, "AGENT_NOT_REGISTERED",
                    "Agent must register before starting a conversation stream");
            return;
        }
        String requestId = asString(payload.get("requestId"));
        String conversationId = asString(payload.get("conversationId"));
        ChatConversationEntity conversation = requireOwnedConversation(session, payload, conversationId);
        if (conversation == null || !requireConversationAgentScope(
                session, payload, conversation, agentId)) {
            return;
        }
        String conversationType = Optional.ofNullable(conversation.getConversationType()).orElse("normal");
        String ownerJiacn = sessionJiacn(session);
        String ownerClientId = sessionClientId(session);
        long generation = lifecycleGeneration(conversation);
        ServerResolvedAgentSender sender = resolveAgentSender(session, payload, agentId);
        if (sender == null) {
            return;
        }
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
        started.put("senderType", sender.type());
        started.put("senderName", sender.displayName());
        boolean live = chatConversationEventBroker.runIfLive(
                conversationId, generation,
                () -> chatConversationService.isLiveGeneration(
                        ownerJiacn, ownerClientId, conversationId, generation),
                () -> sendEvent(session, "started", started));
        if (!live) {
            return;
        }

        Disposable disposable = chatClient.prompt(content)
                .advisors(advisor -> advisor
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param("jiacn", ownerJiacn)
                        .param("clientId", ownerClientId)
                        .param("conversationType", conversationType)
                        .param(cn.jia.chat.advisor.DatabaseChatMemoryAdvisor.SERVER_RESOLVED_SENDER, sender))
                .stream()
                .content()
                .concatMap(chunk -> Mono.fromRunnable(() -> {
                    Map<String, Object> event = copyTrace(payload);
                    event.put("conversationId", conversationId);
                    event.put("conversationType", conversationType);
                    event.put("senderType", sender.type());
                    event.put("senderName", sender.displayName());
                    event.put("content", chunk);
                    chatConversationEventBroker.runIfLive(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation),
                            () -> sendEvent(session, "delta", event));
                }))
                .takeUntilOther(chatConversationEventBroker.deletionSignal(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, conversationId, generation)))
                .doOnError(error -> chatConversationEventBroker.runIfLive(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, conversationId, generation),
                        () -> sendError(session, payload, error.getMessage())))
                .doOnComplete(() -> {
                    Map<String, Object> done = copyTrace(payload);
                    done.put("conversationId", conversationId);
                    done.put("conversationType", conversationType);
                    done.put("senderType", sender.type());
                    done.put("senderName", sender.displayName());
                    chatConversationEventBroker.runIfLive(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation),
                            () -> sendEvent(session, "done", done));
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

        if (requestId == null || requestId.isBlank()) {
            sendProtocolError(session, payload, "CHAT_STOP_BINDING_REQUIRED", "Cancellation requires the original requestId");
            return;
        }
        Optional.ofNullable(runningStreams.remove(streamKey(session, requestId, conversationId)))
                .ifPresent(stream -> { stream.dispose(); stoppedCount.incrementAndGet(); });

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
        String stage = "session_identity_validate";
        try {
            String agentId = requireAllowedSessionAgentId(session, payload);
            if (agentId == null) return;
            validateSessionEnvelope(session, payload);
            var proof = sessionProof(session);
            if (!Objects.equals(proof.installationId(), payload.get("installationId"))
                    || !Objects.equals(proof.hostId(), payload.get("hostId"))
                    || !Objects.equals(proof.scope().runtimeInstanceId(), payload.get("runtimeInstanceId"))
                    || !Objects.equals(proof.sessionGeneration(), exactLong(payload.get("sessionGeneration")))) {
                throw new IllegalArgumentException("Registration proof mismatch");
            }
            // Identity and generation are fixed by installation-derived native handshake proof.
            stage = "runtime_disconnect";
            if (runtimeAuthentication != null) runtimeAuthentication.disconnect(session.getId());
            // A re-registration on the same socket must first retire its old declaration. The
            // replacement is not observable until register, auth bind and receipt delivery all
            // succeed below.
            successfullyRegisteredAgentIds.remove(session.getId());
            sessionRuntimeCapabilities.remove(session.getId());
            sessionDurableStateHealthy.remove(session.getId());
            sessionReadyCommandTypes.remove(session.getId());
            sessionNativeBountyExecution.remove(session.getId());
            sessionNativeProviderCredentialBinding.remove(session.getId());
            sessionControlledImageBountyExecution.remove(session.getId());
            sessionControlledImageV3.remove(session.getId());
            removeTypedDeliberationSession(session.getId());
            removeTypedInspectionSession(session.getId());
            stage = "registration_payload";
            Set<String> readyCommandTypes = parseReadyCommandTypes(payload.get("readyCommandTypes"));
            if (payload.containsKey("durableStateHealthy") && !(payload.get("durableStateHealthy") instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid durable-state health declaration");
            }
            AgentRuntimeCapabilities runtimeCapabilities = AgentRuntimeCapabilities.parse(
                    payload.get("runtimeCapabilities"));
            NativeBountyExecutionDeclaration nativeBountyExecution =
                    NativeBountyExecutionDeclaration.parse(payload.get("nativeBountyExecution"));
            NativeProviderCredentialBindingDeclaration nativeProviderCredentialBinding =
                    NativeProviderCredentialBindingDeclaration.parse(
                            payload.get("nativeProviderCredentialBinding"));
            ControlledImageBountyExecutionDeclaration controlledImageBountyExecution =
                    ControlledImageBountyExecutionDeclaration.parse(
                            payload.get("controlledImageBountyExecution"));
            ControlledImageV3Declaration controlledImageV3 =
                    ControlledImageV3Declaration.parse(payload.get("controlledImageBountyExecutionV3"));
            TypedDeliberationDeclaration typedDeliberation =
                    TypedDeliberationDeclaration.parse(payload.get("typedDeliberation"));
            TypedInspectionDeclaration typedInspection =
                    TypedInspectionDeclaration.parse(payload.get("typedInspection"));
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
            stage = "runtime_register";
            AgentRegisterResultDTO result = withSessionContext(session, () -> runtimeAuthentication.withFence(sessionProof(session), true, () -> agentService.register(request)));
            if (result == null || !agentId.equals(result.getAgentId())) {
                throw new IllegalStateException("Agent registration returned a mismatched canonical identity");
            }
            rememberSessionAgent(session.getId(), result.getAgentId());
            Map<String, Object> event = copyTrace(payload);
            event.put("agentId", result.getAgentId());
            putIfPresent(event, "runtimeInstanceId", sessionRuntimeInstanceId(session));
            event.put("status", result.getStatus());
            event.put("installationId", sessionProof(session).installationId());
            event.put("hostId", sessionProof(session).hostId());
            event.put("sessionGeneration", sessionProof(session).sessionGeneration());
            event.put("readyCommandTypes", readyCommandTypes);
            event.put("durableStateHealthy", Boolean.TRUE.equals(payload.get("durableStateHealthy")));
            event.put("runtimeCapabilities", runtimeCapabilities.normalizedForReceipt());
            event.put("nativeBountyExecution", nativeBountyExecution.normalizedForReceipt());
            event.put("nativeProviderCredentialBinding",
                    nativeProviderCredentialBinding.normalizedForReceipt());
            event.put("controlledImageBountyExecution",
                    controlledImageBountyExecution.normalizedForReceipt());
            event.put("controlledImageBountyExecutionV3",controlledImageV3.normalizedForReceipt());
            if (payload.containsKey("typedDeliberation")) {
                event.put("typedDeliberation", typedDeliberation.frozenReceipt());
            }
            if (payload.containsKey("typedInspection")) {
                event.put("typedInspection", typedInspection.frozenReceipt());
            }
            stage = "runtime_bind";
            event.put("runtimeAuth", runtimeAuthentication.bind(session.getId(), sessionProof(session), session::isOpen));
            stage = "registration_receipt";
            if (!sendEvent(session, "agent_registered", event)) {
                if (runtimeAuthentication != null) runtimeAuthentication.disconnect(session.getId());
                successfullyRegisteredAgentIds.remove(session.getId());
                sessionRuntimeCapabilities.remove(session.getId());
                sessionDurableStateHealthy.remove(session.getId());
                sessionReadyCommandTypes.remove(session.getId());
                sessionNativeBountyExecution.remove(session.getId());
                sessionNativeProviderCredentialBinding.remove(session.getId());
                sessionControlledImageBountyExecution.remove(session.getId());
                sessionControlledImageV3.remove(session.getId());
                removeTypedDeliberationSession(session.getId());
                removeTypedInspectionSession(session.getId());
                return;
            }
            // Activate all successful-registration evidence only after the authenticated
            // registration receipt was actually delivered.
            rememberSuccessfulRegistration(session.getId(), result.getAgentId());
            sessionRuntimeCapabilities.put(session.getId(), runtimeCapabilities);
            sessionDurableStateHealthy.put(session.getId(), Boolean.TRUE.equals(payload.get("durableStateHealthy")));
            sessionReadyCommandTypes.put(session.getId(), readyCommandTypes);
            sessionNativeBountyExecution.put(session.getId(), nativeBountyExecution);
            sessionNativeProviderCredentialBinding.put(session.getId(), nativeProviderCredentialBinding);
            sessionControlledImageBountyExecution.put(session.getId(), controlledImageBountyExecution);
            sessionControlledImageV3.put(session.getId(),controlledImageV3);
            if (typedDeliberationSessions != null) {
                typedDeliberationSessions.register(session.getId(), sessionTenantId(session),
                        sessionJiacn(session), sessionClientId(session), result.getAgentId(),
                        payload.get("typedDeliberation"),
                        () -> typedDeliberationSessionCurrent(session, result.getAgentId()));
            }
            if (typedInspectionSessions != null) {
                typedInspectionSessions.register(session.getId(), sessionTenantId(session),
                        sessionJiacn(session), sessionClientId(session), result.getAgentId(),
                        payload.get("typedInspection"),
                        () -> typedDeliberationSessionCurrent(session, result.getAgentId()));
            }
            signalRegisteredReconnect(session, result.getAgentId());
            sendCapabilityIndex(session, payload);
        } catch (Exception e) {
            // Keep peer-facing errors generic; stage and exception type are sufficient for safe operations diagnosis.
            log.warn("Agent registration unavailable at stage={}, failure={}", stage,
                    e.getClass().getSimpleName());
            if (runtimeAuthentication != null) runtimeAuthentication.disconnect(session.getId());
            successfullyRegisteredAgentIds.remove(session.getId());
            sessionRuntimeCapabilities.remove(session.getId());
            sessionDurableStateHealthy.remove(session.getId());
            sessionReadyCommandTypes.remove(session.getId());
            sessionNativeBountyExecution.remove(session.getId());
            sessionNativeProviderCredentialBinding.remove(session.getId());
            sessionControlledImageBountyExecution.remove(session.getId());
            sessionControlledImageV3.remove(session.getId());
            removeTypedDeliberationSession(session.getId());
            removeTypedInspectionSession(session.getId());
            sendError(session, payload, "AGENT_REGISTRATION_UNAVAILABLE", "Agent registration is unavailable");
        }
    }

    private void signalRegisteredReconnect(WebSocketSession session, String agentId) {
        AgentCommandReconnectSignal reconnectSignal = reconnectSignalSupplier.get();
        if (reconnectSignal == null) return;
        String tenantId = sessionTenantId(session);
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

    private void handleSkillInstallResult(WebSocketSession session,Map<String,Object> payload) {
        String agent=sessionAgentId(session), tenant=sessionJiacn(session), client=sessionClientId(session);
        if (skillResults==null || !validExactDispatchId(agent,100)
                || !successfullyRegisteredAgentIds(session.getId()).contains(agent)
                || !validExactDispatchId(tenant,50) || !validExactDispatchId(client,50)
                || declaredScopeConflict(payload,"tenantId",tenant) || declaredScopeConflict(payload,"clientId",client)
                || !agent.equals(payload.get("targetAgentId"))
                || sessionRuntimeInstanceId(session)==null
                || !validExactDispatchId(strictString(payload.get("runtimeInstanceId")),100)) {
            sendProtocolError(session,payload,"SKILL_RESULT_REJECTED","Skill result identity unavailable"); return;
        }
        try {
            Map<String,Object> receipt=skillResults.accept(tenant,client,agent,sessionAttribute(session,"managedApiKeyId"),payload);
            sendEvent(session,"work.result.receipt",receipt); // accept() returns only after durable commit
        } catch (RuntimeException failure) {
            sendProtocolError(session,payload,"SKILL_RESULT_REJECTED","Skill result could not be committed");
        }
    }

    private boolean declaredScopeConflict(Map<String, Object> payload, String field, String expected) {
        return payload.containsKey(field)
                && !expected.equals(strictString(payload.get(field)));
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
            if (payload.containsKey("durableStateHealthy") && !(payload.get("durableStateHealthy") instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid durable-state health declaration");
            }
            AgentRuntimeDTO agent = withSessionContext(session, () -> runtimeAuthentication.withFence(sessionProof(session), false, () -> agentService.updateStatus(agentId, request)));
            if (payload.containsKey("durableStateHealthy")) {
                sessionDurableStateHealthy.put(session.getId(), Boolean.TRUE.equals(payload.get("durableStateHealthy")));
            }
            if (AgentConstants.STATUS_OFFLINE.equals(agent.getStatus())
                    || AgentConstants.STATUS_ERROR.equals(agent.getStatus())) {
                if (runtimeAuthentication != null) runtimeAuthentication.disconnect(session.getId());
                successfullyRegisteredAgentIds.remove(session.getId());
                sessionRuntimeCapabilities.remove(session.getId());
                sessionDurableStateHealthy.remove(session.getId());
                sessionReadyCommandTypes.remove(session.getId());
                sessionNativeBountyExecution.remove(session.getId());
                sessionNativeProviderCredentialBinding.remove(session.getId());
                sessionControlledImageBountyExecution.remove(session.getId());
                sessionControlledImageV3.remove(session.getId());
                removeTypedDeliberationSession(session.getId());
                removeTypedInspectionSession(session.getId());
            }
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
            AgentTaskDTO task = withSessionContext(session, () -> runtimeAuthentication.withFence(sessionProof(session), false, () -> agentService.assignTask(taskId, request)));
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
            AgentTaskDTO task = withSessionContext(session, () -> runtimeAuthentication.withFence(sessionProof(session), false, () -> agentService.reportTask(taskId, request)));
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

    private void acknowledgeHostedReceipt(WebSocketSession session, String agentId, String dispatchId) {
        if (chatDeliberationOutboxService == null) return;
        try {
            runtimeAuthentication.withFence(sessionProof(session), false, () -> {
                chatDeliberationOutboxService.acknowledgeHostedReceipt(sessionTenantId(session),
                        sessionJiacn(session), sessionClientId(session), agentId, dispatchId, System.currentTimeMillis());
                return null;
            });
        } catch (RuntimeException ignored) {
            // The durable final/delta remains authoritative; explicit chat.dispatch.ack can be retried.
        }
    }

    private void acknowledgeChatDispatch(WebSocketSession session, Map<String, Object> payload) {
        String agentId = requireAllowedSessionAgentId(session, payload);
        if (agentId == null || chatDeliberationOutboxService == null) {
            sendProtocolError(session, payload, "CHAT_DISPATCH_ACK_UNAVAILABLE",
                    "Durable chat dispatch acknowledgement is unavailable");
            return;
        }
        String dispatchId = strictString(payload.get("dispatchId"));
        String messageId = strictString(payload.get("messageId"));
        try {
            boolean acknowledged = runtimeAuthentication.withFence(sessionProof(session), false, () -> chatDeliberationOutboxService.acknowledgeHostedDispatch(
                    sessionTenantId(session), sessionJiacn(session), sessionClientId(session),
                    agentId, dispatchId, messageId, System.currentTimeMillis()));
            if (!acknowledged) {
                sendProtocolError(session, payload, "CHAT_DISPATCH_ACK_REJECTED",
                        "Durable chat dispatch acknowledgement was rejected");
                return;
            }
            sendEvent(session, "chat_dispatch_acknowledged", Map.of(
                    "messageId", messageId, "dispatchId", dispatchId, "duplicateSafe", true));
        } catch (RuntimeException rejected) {
            sendProtocolError(session, payload, "CHAT_DISPATCH_ACK_REJECTED",
                    "Durable chat dispatch acknowledgement was rejected");
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

        String jiacn = sessionJiacn(session);
        String clientId = sessionClientId(session);
        ChatConversationEntity conversation = requireOwnedConversation(session, payload, conversationId);
        if (conversation == null || !requireConversationAgentScope(
                session, payload, conversation, agentId)) {
            return;
        }
        String conversationType = Optional.ofNullable(conversation.getConversationType()).orElse("normal");
        long generation = lifecycleGeneration(conversation);
        ServerResolvedAgentSender sender = resolveAgentSender(session, payload, agentId);
        if (sender == null) {
            return;
        }
        String senderName = sender.displayName();

        if (hasDurableTurnBinding(payload)) {
            if (chatDeliberationService == null) {
                sendError(session, payload, "CHAT_TURN_STATE_UNAVAILABLE", "Durable chat turn service is unavailable");
                return;
            }
            Long declaredGeneration = exactPositiveLong(payload.get("conversationGeneration"));
            if (declaredGeneration == null || declaredGeneration != generation) {
                sendError(session, payload, "CHAT_TURN_FINAL_REJECTED", "Durable chat generation was rejected");
                return;
            }
            ChatDeliberationService.FinalResult finalResult;
            try {
                finalResult = runtimeAuthentication.withFence(sessionProof(session), false, () -> chatDeliberationService.persistFinal(
                        sessionTenantId(session), jiacn, clientId, conversationId, generation, agentId,
                        asString(payload.get("requestId")), asString(payload.get("turnId")),
                        asString(payload.get("dispatchId")), contextValue(payload, "contextSnapshotId"),
                        contextValue(payload, "contextHash"), content,
                        typedOutcomeContractVersion(payload),
                        strictString(payload.get("__typedRawInteractionOutcomeJson")),
                        strictString(payload.get("__typedRawInspectionInputReceiptJson")), sender));
            } catch (RuntimeException rejected) {
                sendError(session, payload, "CHAT_TURN_FINAL_REJECTED", "Durable chat final was rejected");
                return;
            }
            acknowledgeHostedReceipt(session, agentId, asString(payload.get("dispatchId")));
            if (finalResult.status() == ChatDeliberationService.FinalStatus.DUPLICATE) {
                sendEvent(session, "agent_message_saved", Map.of(
                        "turnId", finalResult.turn().getTurnId(),
                        "messageId", ExactWireIds.decimal(finalResult.messageId()),
                        "duplicate", true));
                return;
            }
            // The FINAL_PERSISTED outbox is the sole conversation-delivery authority.
            // This socket receipt is after the final transaction committed and does not mark the outbox SENT.
            sendEvent(session, "agent_message_saved", Map.of(
                    "turnId", finalResult.turn().getTurnId(),
                    "messageId", ExactWireIds.decimal(finalResult.messageId()),
                    "eventId", finalResult.eventId(), "duplicate", false));
            return;
        }

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

        Map<String, Object> metadata = ConversationMetadataPolicy.copyAllowed(copyTrace(payload));
        metadata.put("agentId", agentId);
        metadata.put("senderType", "agent");
        metadata.put("senderName", senderName);
        metadata.put("conversationType", conversationType);
        entity.setMetadata(JsonUtil.toJson(metadata));
        try {
            ChatMessageEntity proposed = entity;
            entity = runtimeAuthentication.withFence(sessionProof(session), false,
                    () -> chatConversationService.appendOwnedMessage(jiacn, clientId, proposed, generation));
        } catch (RuntimeException denied) {
            sendError(session, payload, "CONVERSATION_NOT_AVAILABLE",
                    "Conversation is not available to this agent session");
            return;
        }

        Map<String, Object> event = copyTrace(payload);
        event.put("type", "agent_message");
        event.put("messageId", ExactWireIds.decimal(entity.getId()));
        event.put("conversationId", conversationId);
        event.put("conversationType", conversationType);
        event.put("agentId", agentId);
        event.put("senderType", "agent");
        event.put("senderName", senderName);
        event.put("content", content);
        addEventMetadata(event);
        chatConversationEventBroker.runIfLive(
                conversationId, generation,
                () -> chatConversationService.isLiveGeneration(
                        jiacn, clientId, conversationId, generation),
                () -> {
                    sendEvent(session, "agent_message_saved", event);
                    broadcastConversationEventToTargets(
                            clientId, jiacn,
                            currentConversationRecipientAgentIds(conversation, jiacn, clientId),
                            "agent_message", event);
                    chatConversationEventBroker.publishIfLive(
                            conversationId, generation, () -> true, event);
                });
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

        ChatConversationEntity conversation = requireOwnedConversation(session, payload, conversationId);
        if (conversation == null || !requireConversationAgentScope(
                session, payload, conversation, agentId)) {
            return;
        }
        String conversationType = Optional.ofNullable(conversation.getConversationType()).orElse("normal");
        long generation = lifecycleGeneration(conversation);
        ServerResolvedAgentSender sender = resolveAgentSender(session, payload, agentId);
        if (sender == null) {
            return;
        }
        String senderName = sender.displayName();

        if (hasDurableTurnBinding(payload)) {
            if (chatDeliberationService == null) return;
            Long declaredGeneration = exactPositiveLong(payload.get("conversationGeneration"));
            if (declaredGeneration == null || declaredGeneration != generation) {
                sendError(session, payload, "CHAT_DELTA_REJECTED", "Durable chat generation was rejected");
                return;
            }
            Long deltaSeq = exactPositiveLong(payload.get("deltaSeq"));
            if (deltaSeq == null) {
                sendError(session, payload, "CHAT_DELTA_SEQUENCE_REQUIRED", "deltaSeq is required");
                return;
            }
            ChatDeliberationService.DeltaResult result;
            try {
                result = runtimeAuthentication.withFence(sessionProof(session), false, () -> chatDeliberationService.acceptDelta(
                        sessionTenantId(session), sessionJiacn(session), sessionClientId(session),
                        conversationId, generation, agentId,
                        asString(payload.get("requestId")), asString(payload.get("turnId")),
                        asString(payload.get("dispatchId")), contextValue(payload, "contextSnapshotId"),
                        contextValue(payload, "contextHash"), deltaSeq, content, sender));
            } catch (RuntimeException rejected) {
                sendError(session, payload, "CHAT_DELTA_REJECTED", "Durable chat delta was rejected");
                return;
            }
            acknowledgeHostedReceipt(session, agentId, asString(payload.get("dispatchId")));
            if (result.status() == ChatDeliberationService.DeltaStatus.GAP) {
                publishStoredConversationEvent(result.event());
                sendError(session, payload, "CHAT_DELTA_GAP", "deltaSeq is not contiguous");
                return;
            }
            if (result.status() != ChatDeliberationService.DeltaStatus.ACCEPTED) return;
            publishStoredConversationEvent(result.event());
            return;
        }

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
        addEventMetadata(event);
        chatConversationEventBroker.publishIfLive(
                conversationId, generation,
                () -> chatConversationService.isLiveGeneration(
                        sessionJiacn(session), sessionClientId(session),
                        conversationId, generation),
                event);
    }

    private Map<String, Object> strictDurableFinalPayload(String wire) throws Exception {
        JsonNode root = STRICT_RAW_COMMAND_JSON.readTree(wire);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("object required");
        Map<String, Object> strict = STRICT_RAW_COMMAND_JSON.readValue(wire, MESSAGE_TYPE);
        if (strict.containsKey("__typedRawInteractionOutcomeJson")
                || strict.containsKey("__typedRawInspectionInputReceiptJson")) {
            throw new IllegalArgumentException("reserved field");
        }
        JsonNode nested = root.get("payload");
        if (nested != null && nested.isObject()
                && (nested.has("__typedRawInteractionOutcomeJson")
                        || nested.has("__typedRawInspectionInputReceiptJson")
                        || nested.has("interactionOutcome")
                        || nested.has("outcomeContractVersion")
                        || nested.has("inspectionInputReceipt"))) {
            throw new IllegalArgumentException("typed sidecar must be top-level");
        }
        JsonNode outcome = root.get("interactionOutcome");
        JsonNode version = root.get("outcomeContractVersion");
        JsonNode receipt = root.get("inspectionInputReceipt");
        if (outcome == null && version == null && receipt == null) return strict;
        if (outcome == null || version == null || !outcome.isObject()) {
            throw new IllegalArgumentException("incomplete typed sidecar");
        }
        if (semanticInteger(version, 1)) {
            if (receipt != null) throw new IllegalArgumentException("inspection receipt is forbidden for v1");
            strict.put("outcomeContractVersion", 1);
        } else if (semanticInteger(version, 2)) {
            if (receipt == null || !receipt.isObject()) {
                throw new IllegalArgumentException("inspection receipt is required for v2");
            }
            strict.put("outcomeContractVersion", 2);
            strict.put("__typedRawInspectionInputReceiptJson", receipt.toString());
        } else if (semanticInteger(version, 3)) {
            if (receipt != null && !receipt.isObject()) throw new IllegalArgumentException("invalid inspection receipt");
            strict.put("outcomeContractVersion", 3);
            if (receipt != null) strict.put("__typedRawInspectionInputReceiptJson", receipt.toString());
        } else {
            throw new IllegalArgumentException("invalid typed sidecar version");
        }
        strict.put("__typedRawInteractionOutcomeJson", outcome.toString());
        return strict;
    }

    private Integer typedOutcomeContractVersion(Map<String, Object> payload) {
        Object value = payload.get("outcomeContractVersion");
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            int version = ((Number) value).intValue();
            return version == 1 || version == 2 || version == 3 ? version : null;
        }
        return null;
    }

    private boolean semanticInteger(JsonNode value, int expected) {
        if (value == null || !value.isNumber()) return false;
        try {
            return value.decimalValue().compareTo(java.math.BigDecimal.valueOf(expected)) == 0;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void removeTypedDeliberationSession(String sessionId) {
        if (typedDeliberationSessions != null) typedDeliberationSessions.remove(sessionId);
    }

    private void removeTypedInspectionSession(String sessionId) {
        if (typedInspectionSessions != null) typedInspectionSessions.remove(sessionId);
    }

    private boolean typedDeliberationSessionCurrent(WebSocketSession session, String agentId) {
        if (runtimeAuthentication == null || session == null || !session.isOpen()
                || !successfullyRegisteredAgentIds(session.getId()).contains(agentId)
                || !Boolean.TRUE.equals(sessionDurableStateHealthy.get(session.getId()))) return false;
        String runtime = sessionRuntimeInstanceId(session);
        if (runtime == null) return false;
        try {
            return runtimeAuthentication.isCurrentBinding(session.getId(), sessionTenantId(session),
                    sessionClientId(session), sessionJiacn(session), agentId, runtime);
        } catch (RuntimeException unavailable) { return false; }
    }

    private boolean hasDurableTurnBinding(Map<?, ?> payload) {
        return payload != null && (payload.containsKey("turnId")
                || payload.containsKey("dispatchId") || payload.containsKey("contextSnapshotId")
                || payload.containsKey("contextHash") || payload.containsKey("deltaSeq"));
    }

    @SuppressWarnings("unchecked")
    private String contextValue(Map<String, Object> payload, String key) {
        String direct = asString(payload.get(key));
        if (direct != null) return direct;
        Object nested = payload.get("contextSnapshot");
        return nested instanceof Map<?, ?> map ? asString(((Map<String, Object>) map).get(key)) : null;
    }

    private Long exactPositiveLong(Object value) {
        long result;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            result = ((Number) value).longValue();
        } else if (value instanceof String text && text.matches("[1-9][0-9]*")) {
            try { result = Long.parseLong(text); } catch (NumberFormatException invalid) { return null; }
        } else return null;
        return result > 0 ? result : null;
    }

    @SuppressWarnings("unchecked")
    private void publishStoredConversationEvent(cn.jia.chat.deliberation.ChatConversationEventEntity stored) {
        if (stored == null) return;
        try {
            Map<String,Object> event = JsonUtil.getMapper().readValue(stored.getPayloadJson(), Map.class);
            event.put("eventId", stored.getEventId());
            event.put("eventSequence", Long.toString(stored.getEventSequence()));
            event.put("eventVersion", Long.toString(stored.getEventVersion()));
            event.put("occurredAt", Long.toString(stored.getOccurredAt()));
            requireNoActiveTransactionForChatDelivery();
            chatConversationEventBroker.publishIfSubscribed(stored.getConversationId(), stored.getConversationGeneration(),
                    () -> chatConversationService.isLiveGeneration(stored.getOwnerJiacn(), stored.getClientId(),
                            stored.getConversationId(), stored.getConversationGeneration()), event);
        } catch (Exception ignored) {
            // Durable DB replay remains available; a best-effort live publication never changes persisted state.
        }
    }

    private Map<String, Object> durableEvent(Map<String, Object> payload, String type,
            cn.jia.chat.deliberation.ChatTurnEntity turn) {
        Map<String, Object> event = copyTrace(payload);
        event.put("type", type);
        event.put("requestId", turn.getRequestId());
        event.put("turnId", turn.getTurnId());
        event.put("dispatchId", turn.getDispatchId());
        event.put("targetAgentId", turn.getTargetAgentId());
        event.put("contextSnapshotId", turn.getSnapshotId());
        event.put("route", turn.getRoute());
        addEventMetadata(event);
        return event;
    }

    private void addEventMetadata(Map<String, Object> event) {
        event.putIfAbsent("eventId", UUID.randomUUID().toString());
        event.putIfAbsent("eventVersion", 1);
        event.putIfAbsent("occurredAt", System.currentTimeMillis());
    }

    private boolean requireConversationAgentScope(
            WebSocketSession session, Map<String, Object> payload,
            ChatConversationEntity conversation, String agentId) {
        if (!isCanonicalScopeId(agentId)) {
            return denyConversationAgentScope(session, payload);
        }

        List<String> persistedTargets;
        try {
            persistedTargets = persistedConversationTargetAgentIds(conversation);
        } catch (RuntimeException invalidScope) {
            return denyConversationAgentScope(session, payload);
        }
        if (persistedTargets.isEmpty() || !persistedTargets.contains(agentId)) {
            return denyConversationAgentScope(session, payload);
        }

        String scopeType = conversation.getConversationScopeType();
        String taskId = conversation.getTaskId();
        String scopeKey = conversation.getConversationScopeKey();
        boolean taskScoped = "bounty".equals(scopeType)
                || (taskId != null && !taskId.isBlank())
                || (scopeKey != null && scopeKey.startsWith("task:"));
        if ("private".equals(scopeType) && persistedTargets.size() != 1) {
            return denyConversationAgentScope(session, payload);
        }
        if (!taskScoped) {
            return true;
        }
        if (!("public".equals(scopeType) || "bounty".equals(scopeType)
                || "private".equals(scopeType))) {
            return denyConversationAgentScope(session, payload);
        }
        if (!isCanonicalScopeId(taskId)) {
            return denyConversationAgentScope(session, payload);
        }
        String expectedScopeKey = "private".equals(scopeType)
                ? "task:" + taskId + ":agent:" + agentId
                : "task:" + taskId;
        if (!expectedScopeKey.equals(scopeKey)) {
            return denyConversationAgentScope(session, payload);
        }
        // The WebSocket jiacn is the owner, not the task tenant (always "0").
        // The persisted conversation and the receiving session were matched above.
        Set<String> authoritativeMembers = resolveConversationTaskMembers(
                sessionJiacn(session), sessionClientId(session), taskId);
        if (!authoritativeMembers.contains(agentId)) {
            return denyConversationAgentScope(session, payload);
        }
        // Persisted recipients are mandatory; current writable membership further narrows task scope.
        return true;
    }

    private boolean denyConversationAgentScope(
            WebSocketSession session, Map<String, Object> payload) {
        sendError(session, payload, "CONVERSATION_AGENT_SCOPE_MISMATCH",
                "Agent is outside the conversation target scope");
        return false;
    }

    private List<String> persistedConversationTargetAgentIds(
            ChatConversationEntity conversation) {
        List<String> persistedTargets = parsePersistedTargetAgentIds(
                conversation.getTargetAgentIds());
        String legacyTarget = conversation.getTargetAgentId();
        if (legacyTarget == null || legacyTarget.isBlank()) {
            return persistedTargets;
        }
        if (!isCanonicalScopeId(legacyTarget)) {
            throw new IllegalArgumentException("Invalid legacy target agent scope");
        }
        if (persistedTargets.isEmpty()) {
            return List.of();
        }
        if (!persistedTargets.contains(legacyTarget)) {
            throw new IllegalArgumentException("Conflicting persisted target agent scope");
        }
        return persistedTargets;
    }

    private Set<String> resolveConversationTaskMembers(String ownerJiacn, String clientId,
            String taskId) {
        if (!validExactDispatchId(ownerJiacn, 50) || !validExactDispatchId(clientId, 50)
                || !isCanonicalScopeId(taskId)) return Set.of();
        EsContext previous = EsContextHolder.getContext();
        EsContext ownerContext = new EsContext();
        ownerContext.setJiacn(ownerJiacn);
        ownerContext.setClientId(clientId);
        EsContextHolder.setContext(ownerContext);
        try {
            return resolveTaskMemberAgentIds("0", clientId, taskId);
        } finally {
            EsContextHolder.setContext(previous);
        }
    }

    private List<String> currentConversationRecipientAgentIds(
            ChatConversationEntity conversation, String tenantId, String clientId) {
        List<String> persistedTargets = persistedConversationTargetAgentIds(conversation);
        String scopeType = conversation.getConversationScopeType();
        String taskId = conversation.getTaskId();
        String scopeKey = conversation.getConversationScopeKey();
        boolean taskScoped = "bounty".equals(scopeType)
                || (taskId != null && !taskId.isBlank())
                || (scopeKey != null && scopeKey.startsWith("task:"));
        if (!taskScoped) {
            return persistedTargets;
        }
        Set<String> currentMembers = resolveConversationTaskMembers(tenantId, clientId, taskId);
        if (currentMembers.isEmpty()) {
            return List.of();
        }
        return persistedTargets.stream()
                .filter(currentMembers::contains)
                .toList();
    }

    private List<String> parsePersistedTargetAgentIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> values = objectMapper.readValue(json, List.class);
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (Object value : values) {
                if (!(value instanceof String text) || !isCanonicalScopeId(text)
                        || !normalized.add(text)) {
                    throw new IllegalArgumentException("Invalid persisted target agent scope");
                }
            }
            return List.copyOf(normalized);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid persisted target agent scope", invalid);
        }
    }

    private boolean isCanonicalScopeId(String value) {
        return value != null && !value.isBlank() && value.length() <= 100
                && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private long lifecycleGeneration(ChatConversationEntity conversation) {
        Long generation = conversation == null ? null : conversation.getLifecycleGeneration();
        if (generation == null) {
            return 1L;
        }
        if (generation < 1) {
            throw new IllegalStateException("Conversation generation is unavailable");
        }
        return generation;
    }

    private ChatConversationEntity requireOwnedConversation(
            WebSocketSession session, Map<String, Object> payload, String conversationId) {
        String jiacn = sessionJiacn(session);
        String clientId = sessionClientId(session);
        if (!isExactConversationIdentity(jiacn)
                || !isExactConversationIdentity(clientId)
                || !isCanonicalConversationId(conversationId)
                || chatConversationService == null) {
            sendError(session, payload, "CONVERSATION_NOT_AVAILABLE",
                    "Conversation owner scope is unavailable");
            return null;
        }
        try {
            ChatConversationEntity conversation = chatConversationService.getOwned(jiacn, clientId, conversationId);
            if (!isExactConversationIdentity(sessionTenantId(session))
                    || !sessionTenantId(session).equals(conversation.getTenantId())) {
                throw new IllegalStateException("tenant mismatch");
            }
            return conversation;
        } catch (RuntimeException denied) {
            sendError(session, payload, "CONVERSATION_NOT_AVAILABLE",
                    "Conversation is not available to this agent session");
            return null;
        }
    }

    private boolean isExactConversationIdentity(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean isCanonicalConversationId(String value) {
        if (!isExactConversationIdentity(value)) {
            return false;
        }
        try {
            long id = Long.parseLong(value);
            return id > 0 && Long.toString(id).equals(value);
        } catch (NumberFormatException invalid) {
            return false;
        }
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
        return sendEvent(session, type, payload, false);
    }

    private boolean sendEvent(WebSocketSession session, String type, Map<String, ?> payload,
            boolean preserveAuthenticatedInspectionContext) {
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
                // Authenticated INSPECT facts and selectors are integrity-bound protocol data.
                // The logging sanitizer truncates deep maps and redacts DATA strings, which
                // changes contextHash and makes root/payload copies conflict. Never log this wire.
                String wireJson = preserveAuthenticatedInspectionContext
                        ? JsonUtil.toJson(event) : JsonUtil.toSafeJson(event);
                session.sendMessage(new TextMessage(wireJson));
            }
            return true;
        } catch (Exception e) {
            if ("agent_registered".equals(type)) log.error("AGENT_REGISTRATION_DELIVERY_UNAVAILABLE");
            else log.error("Error sending OpenClaw channel event", e);
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

        // Tenant "0" is shared by all task owners; the task DTO carries no owner jiacn.
        // Candidate ids are only hints. Every delivery below rechecks membership against
        // the authenticated receiving session's owner/client before sending a byte.
        Set<String> memberAgentIds = "0".equals(task.getTenantId())
                ? Set.copyOf(Optional.ofNullable(task.getAssignedAgentIds()).orElseGet(List::of))
                : resolveTaskMemberAgentIds(task.getTenantId(), task.getClientId(), task.getId());
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

    private void broadcastConversationEventToTargets(
            String clientId, String ownerJiacn, List<String> targetAgentIds,
            String type, Map<String, ?> payload) {
        if (isBlank(clientId) || isBlank(ownerJiacn)
                || targetAgentIds == null || targetAgentIds.isEmpty()) {
            log.warn("Refusing Agent conversation broadcast without explicit recipients, type={}", type);
            return;
        }
        Set<String> targets = Set.copyOf(targetAgentIds);
        sessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .filter(session -> clientId.equals(sessionClientId(session))
                        && ownerJiacn.equals(sessionJiacn(session)))
                .filter(session -> targets.contains(sessionAgentId(session))
                        && successfullyRegisteredAgentIds
                                .getOrDefault(session.getId(), Set.of())
                                .contains(sessionAgentId(session)))
                .forEach(session -> sendEvent(session, type, payload));
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
        String commandType;
        try { commandType = textJson(STRICT_RAW_COMMAND_JSON.readTree(raw), "commandType"); }
        catch (Exception invalid) { return AgentRawCommandDispatchResult.rejected(); }
        List<WebSocketSession> current = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : successfullyRegisteredAgentIds.entrySet()) {
            if (!entry.getValue().contains(targetAgentId)) continue;
            WebSocketSession session = sessions.get(entry.getKey());
            if (exactCommandSessionReady(session, tenantId, clientId, targetAgentId, commandType)) current.add(session);
        }
        // A duplicate current execution channel is a broken ownership invariant, not a broadcast.
        if (current.isEmpty()) return AgentRawCommandDispatchResult.offline();
        if (current.size() != 1) return AgentRawCommandDispatchResult.rejected();
        WebSocketSession selected = current.getFirst();
        try {
            synchronized (selected) {
                // Recheck after channel selection and transport serialization. Socket maps are
                // readiness evidence only; runtime authentication must revalidate persisted ownership.
                if (!exactCommandSessionReady(selected, tenantId, clientId, targetAgentId, commandType)) {
                    return AgentRawCommandDispatchResult.offline();
                }
                selected.sendMessage(new TextMessage(raw));
            }
            return AgentRawCommandDispatchResult.sent(1, 1); // transport only, NOT a D06 commit receipt
        } catch (Exception sendFailure) {
            log.warn("Exact-scope raw Agent command WebSocket send failed");
            return AgentRawCommandDispatchResult.sendFailed(1);
        }
    }

    private boolean exactCommandSessionReady(WebSocketSession session, String tenantId,
            String clientId, String agentId, String commandType) {
        if (session == null || !tenantId.equals(sessionTenantId(session))
                || !clientId.equals(sessionClientId(session)) || !agentId.equals(sessionAgentId(session))
                || !typedDeliberationSessionCurrent(session, agentId)) return false;
        Set<String> ready = sessionReadyCommandTypes.getOrDefault(session.getId(), Set.of());
        return commandType == null ? !ready.isEmpty() : ready.contains(commandType);
    }

    private Set<String> parseReadyCommandTypes(Object value) {
        if (value == null) return Set.of();
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("Invalid command readiness declaration");
        Set<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String type)
                    || !cn.jia.agent.service.impl.AgentCommandCanonicalCodec.isSupportedCommandType(type)
                    || !result.add(type)) throw new IllegalArgumentException("Invalid command readiness declaration");
        }
        return Set.copyOf(result);
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

    @Override
    public NativeBountyExecutionSessionLookup.Snapshot current(
            NativeBountyExecutionSessionLookup.Scope scope) {
        if (scope == null || !validExactDispatchId(scope.tenantId(), 50)
                || !validExactDispatchId(scope.clientId(), 50)
                || !validExactDispatchId(scope.ownerJiacn(), 50)
                || !validExactDispatchId(scope.canonicalAgentId(), 100)) {
            return new NativeBountyExecutionSessionLookup.Snapshot(
                    NativeBountyExecutionSessionLookup.State.OFFLINE, null, null, List.of());
        }
        if (runtimeAuthentication == null) {
            throw new NativeBountyExecutionSessionLookup.SourceUnavailable(
                    new IllegalStateException("Agent runtime authentication is unavailable"));
        }
        List<NativeBountyExecutionDeclaration> current = new ArrayList<>();
        try {
            for (Map.Entry<String, Set<String>> entry : successfullyRegisteredAgentIds.entrySet()) {
                String sessionId = entry.getKey();
                if (!entry.getValue().contains(scope.canonicalAgentId())
                        || !registeredAgentIds(sessionId).contains(scope.canonicalAgentId())) continue;
                WebSocketSession session = sessions.get(sessionId);
                String runtimeInstanceId = session == null ? null : sessionRuntimeInstanceId(session);
                if (session == null || !session.isOpen()
                        || !scope.tenantId().equals(sessionTenantId(session))
                        || !scope.clientId().equals(sessionClientId(session))
                        || !scope.ownerJiacn().equals(sessionJiacn(session))
                        || !scope.canonicalAgentId().equals(sessionAgentId(session))
                        || runtimeInstanceId == null) continue;
                boolean bound;
                try {
                    bound = runtimeAuthentication.isCurrentBinding(sessionId, scope.tenantId(),
                            scope.clientId(), scope.ownerJiacn(), scope.canonicalAgentId(),
                            runtimeInstanceId);
                } catch (IllegalArgumentException invalidBinding) {
                    bound = false;
                }
                if (bound && Boolean.TRUE.equals(sessionDurableStateHealthy.get(sessionId))) current.add(sessionNativeBountyExecution.getOrDefault(sessionId,
                        NativeBountyExecutionDeclaration.parse(null)));
            }
        } catch (NativeBountyExecutionSessionLookup.SourceUnavailable failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new NativeBountyExecutionSessionLookup.SourceUnavailable(unavailable);
        }
        if (current.isEmpty()) return new NativeBountyExecutionSessionLookup.Snapshot(
                NativeBountyExecutionSessionLookup.State.OFFLINE, null, null, List.of());
        if (current.size() != 1) return new NativeBountyExecutionSessionLookup.Snapshot(
                NativeBountyExecutionSessionLookup.State.AMBIGUOUS, null, null, List.of());
        return current.getFirst().snapshot();
    }

    @Override
    public NativeProviderCredentialBindingLookup.Snapshot current(
            NativeProviderCredentialBindingLookup.Scope scope) {
        if (scope == null || !validExactDispatchId(scope.tenantId(), 50)
                || !validExactDispatchId(scope.clientId(), 50)
                || !validExactDispatchId(scope.ownerJiacn(), 50)
                || !validExactDispatchId(scope.canonicalAgentId(), 100)) {
            return emptyProviderBinding(NativeProviderCredentialBindingLookup.State.OFFLINE);
        }
        if (runtimeAuthentication == null) {
            throw new NativeProviderCredentialBindingLookup.SourceUnavailable(
                    new IllegalStateException("Agent runtime authentication is unavailable"));
        }
        List<NativeProviderCredentialBindingDeclaration> current = new ArrayList<>();
        try {
            for (Map.Entry<String, Set<String>> entry : successfullyRegisteredAgentIds.entrySet()) {
                String sessionId = entry.getKey();
                if (!entry.getValue().contains(scope.canonicalAgentId())
                        || !registeredAgentIds(sessionId).contains(scope.canonicalAgentId())) continue;
                WebSocketSession session = sessions.get(sessionId);
                String runtimeInstanceId = session == null ? null : sessionRuntimeInstanceId(session);
                if (session == null || !session.isOpen()
                        || !scope.tenantId().equals(sessionTenantId(session))
                        || !scope.clientId().equals(sessionClientId(session))
                        || !scope.ownerJiacn().equals(sessionJiacn(session))
                        || !scope.canonicalAgentId().equals(sessionAgentId(session))
                        || runtimeInstanceId == null) continue;
                boolean bound;
                try {
                    bound = runtimeAuthentication.isCurrentBinding(sessionId, scope.tenantId(),
                            scope.clientId(), scope.ownerJiacn(), scope.canonicalAgentId(),
                            runtimeInstanceId);
                } catch (IllegalArgumentException invalidBinding) { bound = false; }
                if (bound && Boolean.TRUE.equals(sessionDurableStateHealthy.get(sessionId))) current.add(sessionNativeProviderCredentialBinding.getOrDefault(sessionId,
                        NativeProviderCredentialBindingDeclaration.parse(null)));
            }
        } catch (NativeProviderCredentialBindingLookup.SourceUnavailable failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new NativeProviderCredentialBindingLookup.SourceUnavailable(unavailable);
        }
        if (current.isEmpty()) return emptyProviderBinding(
                NativeProviderCredentialBindingLookup.State.OFFLINE);
        if (current.size()!=1) return emptyProviderBinding(
                NativeProviderCredentialBindingLookup.State.AMBIGUOUS);
        return current.getFirst().snapshot();
    }

    @Override
    public ControlledImageExecutionSessionLookup.Snapshot current(
            ControlledImageExecutionSessionLookup.Scope scope) {
        if (scope == null || !validExactDispatchId(scope.tenantId(),50)
                || !validExactDispatchId(scope.clientId(),50)
                || !validExactDispatchId(scope.ownerJiacn(),50)
                || !validExactDispatchId(scope.canonicalAgentId(),100)) {
            return emptyControlled(ControlledImageExecutionSessionLookup.State.OFFLINE);
        }
        if (runtimeAuthentication == null) throw new ControlledImageExecutionSessionLookup.SourceUnavailable(
                new IllegalStateException("Agent runtime authentication is unavailable"));
        List<ControlledImageExecutionSessionLookup.Snapshot> current=new ArrayList<>();
        try {
            for (Map.Entry<String,Set<String>> entry:successfullyRegisteredAgentIds.entrySet()) {
                String sessionId=entry.getKey();
                if (!entry.getValue().contains(scope.canonicalAgentId())
                        || !registeredAgentIds(sessionId).contains(scope.canonicalAgentId())) continue;
                WebSocketSession session=sessions.get(sessionId);
                String runtimeId=session==null?null:sessionRuntimeInstanceId(session);
                if (session==null || !session.isOpen() || runtimeId==null
                        || !scope.tenantId().equals(sessionTenantId(session))
                        || !scope.clientId().equals(sessionClientId(session))
                        || !scope.ownerJiacn().equals(sessionJiacn(session))
                        || !scope.canonicalAgentId().equals(sessionAgentId(session))) continue;
                boolean bound;
                try { bound=runtimeAuthentication.isCurrentBinding(sessionId,scope.tenantId(),
                        scope.clientId(),scope.ownerJiacn(),scope.canonicalAgentId(),runtimeId); }
                catch (IllegalArgumentException invalid) { bound=false; }
                if (!bound || !Boolean.TRUE.equals(sessionDurableStateHealthy.get(sessionId))) continue;
                var execution=sessionControlledImageBountyExecution.getOrDefault(sessionId,
                        ControlledImageBountyExecutionDeclaration.parse(null));
                var binding=sessionNativeProviderCredentialBinding.getOrDefault(sessionId,
                        NativeProviderCredentialBindingDeclaration.parse(null)).snapshot();
                ControlledImageExecutionSessionLookup.State state=execution.state();
                if (state==ControlledImageExecutionSessionLookup.State.READY) {
                    state=switch(binding.state()) {
                        case READY -> ControlledImageExecutionSessionLookup.State.READY;
                        case OFFLINE -> ControlledImageExecutionSessionLookup.State.OFFLINE;
                        case UNDECLARED -> ControlledImageExecutionSessionLookup.State.UNDECLARED;
                        case DISABLED -> ControlledImageExecutionSessionLookup.State.DISABLED;
                        case UNSUPPORTED -> ControlledImageExecutionSessionLookup.State.UNSUPPORTED;
                        case AMBIGUOUS -> ControlledImageExecutionSessionLookup.State.AMBIGUOUS;
                    };
                }
                current.add(state==ControlledImageExecutionSessionLookup.State.READY
                        ? new ControlledImageExecutionSessionLookup.Snapshot(state,runtimeId,1,
                            ControlledImageBountyExecutionDeclaration.TRANSPORT,List.of("GENERATE_IMAGE"),
                            binding.providerLane(),binding.bindingId(),binding.bindingEpoch(),binding.modelId(),
                            binding.maxInputItems(),binding.maxOutboundRequestAttempts(),binding.precallFenceVersion())
                        : emptyControlled(state));
            }
        } catch (ControlledImageExecutionSessionLookup.SourceUnavailable failure) { throw failure; }
        catch (RuntimeException unavailable) { throw new ControlledImageExecutionSessionLookup.SourceUnavailable(unavailable); }
        if (current.isEmpty()) return emptyControlled(ControlledImageExecutionSessionLookup.State.OFFLINE);
        if (current.size()!=1) return emptyControlled(ControlledImageExecutionSessionLookup.State.AMBIGUOUS);
        return current.getFirst();
    }

    @Override
    public ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration currentSession(
            ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.DeclarationScope scope) {
        if(scope==null||!validExactDispatchId(scope.tenantId(),50)||!validExactDispatchId(scope.clientId(),50)
                ||!validExactDispatchId(scope.ownerJiacn(),50)||!validExactDispatchId(scope.targetAgentId(),100)
                ||runtimeAuthentication==null) {
            return new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration(
                    ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE,null,List.of());
        }
        List<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration> found=new ArrayList<>();
        for(var entry:successfullyRegisteredAgentIds.entrySet()) {
            String sessionId=entry.getKey();
            if(!entry.getValue().contains(scope.targetAgentId())
                    ||!registeredAgentIds(sessionId).contains(scope.targetAgentId())) continue;
            WebSocketSession session=sessions.get(sessionId);
            String runtime=session==null?null:sessionRuntimeInstanceId(session);
            if(session==null||!session.isOpen()||runtime==null
                    ||!scope.tenantId().equals(sessionTenantId(session))
                    ||!scope.clientId().equals(sessionClientId(session))
                    ||!scope.ownerJiacn().equals(sessionJiacn(session))
                    ||!scope.targetAgentId().equals(sessionAgentId(session))) continue;
            boolean bound;
            try { bound=runtimeAuthentication.isCurrentBinding(sessionId,scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),scope.targetAgentId(),runtime); }
            catch(RuntimeException invalid) { bound=false; }
            if(!bound || !Boolean.TRUE.equals(sessionDurableStateHealthy.get(sessionId))) continue;
            ControlledImageV3Declaration declaration=sessionControlledImageV3.getOrDefault(
                    sessionId,ControlledImageV3Declaration.parse(null));
            found.add(declaration.session(runtime));
        }
        if(found.isEmpty()) return new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration(
                ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE,null,List.of());
        if(found.size()!=1) return new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration(
                ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.AMBIGUOUS,null,List.of());
        return found.getFirst();
    }

    @Override
    public ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration current(
            ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.DeclarationScope scope) {
        if(scope==null||!validExactDispatchId(scope.tenantId(),50)||!validExactDispatchId(scope.clientId(),50)
                ||!validExactDispatchId(scope.ownerJiacn(),50)||!validExactDispatchId(scope.targetAgentId(),100))
            return ControlledImageV3Declaration.empty(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE);
        if(runtimeAuthentication==null) return ControlledImageV3Declaration.empty(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE);
        List<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration> found=new ArrayList<>();
        for(var entry:successfullyRegisteredAgentIds.entrySet()){
            String sessionId=entry.getKey();if(!entry.getValue().contains(scope.targetAgentId())||!registeredAgentIds(sessionId).contains(scope.targetAgentId()))continue;
            WebSocketSession session=sessions.get(sessionId);String runtime=session==null?null:sessionRuntimeInstanceId(session);
            if(session==null||!session.isOpen()||runtime==null||!scope.tenantId().equals(sessionTenantId(session))
                    ||!scope.clientId().equals(sessionClientId(session))||!scope.ownerJiacn().equals(sessionJiacn(session))
                    ||!scope.targetAgentId().equals(sessionAgentId(session)))continue;
            boolean bound;try{bound=runtimeAuthentication.isCurrentBinding(sessionId,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.targetAgentId(),runtime);}catch(RuntimeException invalid){bound=false;}
            if(!bound || !Boolean.TRUE.equals(sessionDurableStateHealthy.get(sessionId)))continue;
            var declared=sessionControlledImageV3.getOrDefault(sessionId,ControlledImageV3Declaration.parse(null));
            var binding=sessionNativeProviderCredentialBinding.getOrDefault(sessionId,NativeProviderCredentialBindingDeclaration.parse(null)).snapshot();
            found.add(declared.combine(runtime,binding));
        }
        if(found.isEmpty())return ControlledImageV3Declaration.empty(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.OFFLINE);
        if(found.size()!=1)return ControlledImageV3Declaration.empty(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.AMBIGUOUS);
        return found.getFirst();
    }

    private static ControlledImageExecutionSessionLookup.Snapshot emptyControlled(
            ControlledImageExecutionSessionLookup.State state) {
        return new ControlledImageExecutionSessionLookup.Snapshot(state,null,null,null,List.of(),
                null,null,null,null,null,null,null);
    }

    private static NativeProviderCredentialBindingLookup.Snapshot emptyProviderBinding(
            NativeProviderCredentialBindingLookup.State state) {
        return new NativeProviderCredentialBindingLookup.Snapshot(
                state,null,null,null,null,null,null,null,null);
    }

    /**
     * Sends durable deliberation chat only to exact authenticated connections that advertised the
     * requested profile. A modern declaration is authoritative and is never downgraded to legacy.
     */
    public CapabilityDispatchResult sendNegotiatedChatMessageToAgent(
            String tenantId, String ownerJiacn, String clientId, String agentId,
            InteractionRoute route, Map<String, ?> payload) {
        requireNoActiveTransactionForChatDelivery();
        if (!validExactDispatchId(tenantId, 50) || !validExactDispatchId(ownerJiacn, 50)
                || !validExactDispatchId(clientId, 50) || !validExactDispatchId(agentId, 100)
                || route == null || route == InteractionRoute.EXECUTE) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.UNSUPPORTED, false, Map.of());
        }
        Map<String, AgentRuntimeCapabilities> exact = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : successfullyRegisteredAgentIds.entrySet()) {
            String sessionId = entry.getKey();
            if (!entry.getValue().contains(agentId) || !registeredAgentIds(sessionId).contains(agentId)) continue;
            WebSocketSession session = sessions.get(sessionId);
            if (session == null || !session.isOpen()
                    || !typedDeliberationSessionCurrent(session, agentId)
                    || !agentId.equals(sessionAgentId(session))
                    || !tenantId.equals(sessionTenantId(session))
                    || !ownerJiacn.equals(sessionJiacn(session))
                    || !clientId.equals(sessionClientId(session))) continue;
            exact.put(sessionId, sessionRuntimeCapabilities.getOrDefault(
                    sessionId, AgentRuntimeCapabilities.legacy()));
        }
        if (exact.isEmpty()) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.OFFLINE, false, Map.of());
        }

        // INSPECT has its own measured declaration and exact-session registry. The frozen
        // CHAT transport capability contract deliberately declares legacy INSPECT unavailable.
        if (route == InteractionRoute.INSPECT) {
            return sendTypedInspectionToExactSession(tenantId, ownerJiacn, clientId, agentId,
                    payload, exact.keySet());
        }

        boolean hasModern = exact.values().stream().anyMatch(AgentRuntimeCapabilities::modern);
        Map<String, AgentRuntimeCapabilities> eligible = new LinkedHashMap<>();
        AgentRuntimeCapabilities.Decision blocked = AgentRuntimeCapabilities.Decision.UNSUPPORTED;
        for (Map.Entry<String, AgentRuntimeCapabilities> entry : exact.entrySet()) {
            AgentRuntimeCapabilities capabilities = entry.getValue();
            if (hasModern && !capabilities.modern()) continue;
            AgentRuntimeCapabilities.Decision decision = capabilities.decision(route);
            if (decision == AgentRuntimeCapabilities.Decision.READY
                    || decision == AgentRuntimeCapabilities.Decision.LEGACY_COMPATIBLE) {
                eligible.put(entry.getKey(), capabilities);
            } else if (decision == AgentRuntimeCapabilities.Decision.WAITING_DISABLED) {
                blocked = decision;
            }
        }
        if (eligible.isEmpty()) {
            CapabilityDispatchStatus status = blocked == AgentRuntimeCapabilities.Decision.WAITING_DISABLED
                    ? CapabilityDispatchStatus.WAITING_DISABLED : CapabilityDispatchStatus.UNSUPPORTED;
            AgentRuntimeCapabilities sample = exact.values().stream()
                    .filter(capability -> !hasModern || capability.modern()).findFirst().orElse(exact.values().iterator().next());
            return new CapabilityDispatchResult(status, false,
                    sample.negotiatedProfile(route, sample.decision(route)));
        }

        Map<String, Object> outbound = prepareDirectOutboundPayload(agentId, payload);
        if (outbound == null || !AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(outbound.get("messageType"))) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.UNSUPPORTED, false, Map.of());
        }
        boolean delivered = false;
        Map<String, Object> negotiated = Map.of();
        for (Map.Entry<String, AgentRuntimeCapabilities> entry : eligible.entrySet()) {
            WebSocketSession session = sessions.get(entry.getKey());
            if (session == null || !session.isOpen()) continue;
            AgentRuntimeCapabilities.Decision decision = entry.getValue().decision(route);
            Map<String, Object> wire = new LinkedHashMap<>(outbound);
            negotiated = entry.getValue().negotiatedProfile(route, decision);
            wire.put("targetCapability", negotiated);
            delivered = sendEvent(session, AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE, wire) || delivered;
        }
        CapabilityDispatchStatus status = eligible.values().stream().allMatch(AgentRuntimeCapabilities::modern)
                ? CapabilityDispatchStatus.READY : CapabilityDispatchStatus.LEGACY_COMPATIBLE;
        return new CapabilityDispatchResult(status, delivered, negotiated);
    }

    private CapabilityDispatchResult sendTypedInspectionToExactSession(
            String tenantId, String ownerJiacn, String clientId, String agentId,
            Map<String, ?> payload, Set<String> exactSessionIds) {
        if (typedInspectionSessions == null) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.UNSUPPORTED, false, Map.of());
        }
        TypedInspectionSessionRegistry.Ready ready;
        try {
            ready = typedInspectionSessions.requireSingleReady(
                    new TypedInspectionSessionRegistry.Scope(tenantId, ownerJiacn, clientId), agentId);
        } catch (IllegalStateException unavailable) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.UNSUPPORTED, false, Map.of());
        }
        if (!exactSessionIds.contains(ready.sessionId())) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.UNSUPPORTED, false, Map.of());
        }
        // Never send an admitted manifest to a replacement runtime with different policies.
        if (!(payload.get("contextSnapshot") instanceof Map<?, ?> snapshot)
                || !(snapshot.get("facts") instanceof Map<?, ?> facts)
                || !(facts.get("typedInspection") instanceof Map<?, ?> inspection)
                || !(inspection.get("manifest") instanceof Map<?, ?> manifest)
                || !ready.manifestProfile().equals(manifest.get("profile"))) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.UNSUPPORTED, false, Map.of());
        }
        Map<String, Object> outbound = prepareDirectOutboundPayload(agentId, payload);
        if (outbound == null || !AgentProtocolConstants.TYPE_CHAT_MESSAGE.equals(outbound.get("messageType"))) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.UNSUPPORTED, false, Map.of());
        }
        WebSocketSession selected = sessions.get(ready.sessionId());
        if (selected == null || !selected.isOpen()) {
            return new CapabilityDispatchResult(CapabilityDispatchStatus.OFFLINE, false, Map.of());
        }
        Map<String, Object> negotiated = Map.of("decision", "READY", "profile", "INSPECT",
                "capabilityContractVersion", 1, "policy", ready.frozenDeclaration());
        Map<String, Object> wire = new LinkedHashMap<>(outbound);
        wire.put("targetCapability", negotiated);
        boolean delivered = sendEvent(selected, AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE, wire, true);
        return new CapabilityDispatchResult(CapabilityDispatchStatus.READY, delivered, negotiated);
    }

    public enum CapabilityDispatchStatus {
        READY, LEGACY_COMPATIBLE, WAITING_DISABLED, UNSUPPORTED, OFFLINE
    }

    public record CapabilityDispatchResult(CapabilityDispatchStatus status, boolean delivered,
            Map<String, Object> negotiatedProfile) { }

    public boolean sendDirectMessageToAgent(String agentId, Map<String, ?> payload) {
        return sendDirectMessageToAgent(agentId, payload, null, null, null, null);
    }

    /** Exact tenant/owner/client-scoped delivery for generic conversation traffic. */
    public boolean sendDirectMessageToAgent(String tenantId, String ownerJiacn, String clientId,
            String agentId, Map<String, ?> payload) {
        if (!validExactDispatchId(tenantId, 50) || !validExactDispatchId(ownerJiacn, 50)
                || !validExactDispatchId(clientId, 50)) return false;
        return sendDirectMessageToAgent(agentId, payload, null, tenantId, ownerJiacn, clientId);
    }

    /** Legacy exact owner/client overload; retained for non-deliberation callers. */
    public boolean sendDirectMessageToAgent(
            String ownerJiacn, String clientId, String agentId, Map<String, ?> payload) {
        if (!validExactDispatchId(ownerJiacn, 50) || !validExactDispatchId(clientId, 50)) return false;
        return sendDirectMessageToAgent(agentId, payload, null, null, ownerJiacn, clientId);
    }

    private boolean sendDirectMessageToAgent(String agentId, Map<String, ?> payload,
            Set<String> trustedTaskMemberAgentIds) {
        return sendDirectMessageToAgent(agentId, payload, trustedTaskMemberAgentIds, null, null, null);
    }

    private boolean sendDirectMessageToAgent(
            String agentId, Map<String, ?> payload, Set<String> trustedTaskMemberAgentIds,
            String requiredTenantId, String requiredOwnerJiacn, String requiredClientId) {
        if (isBlank(agentId)) {
            return false;
        }
        Map<String, Object> outbound = prepareDirectOutboundPayload(agentId, payload);
        if (outbound == null) {
            return false;
        }
        String messageType = asString(outbound.get("messageType"));
        TaskDeliveryScope taskScope = taskDeliveryScope(outbound);
        boolean ownerScopedTask = taskScope != null && "0".equals(taskScope.tenantId());
        if (requiresTaskScope(messageType) && !ownerScopedTask) {
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
            if (requiredTenantId != null && !requiredTenantId.equals(sessionTenantId(session))) {
                continue;
            }
            if (requiredOwnerJiacn != null
                    && (!requiredOwnerJiacn.equals(sessionJiacn(session))
                    || !requiredClientId.equals(sessionClientId(session)))) {
                continue;
            }
            if (taskScope != null && !taskScope.clientId().equals(sessionClientId(session))) {
                continue;
            }
            if (ownerScopedTask) {
                // The canonical task tenant is not a jiacn. Resolve the authoritative task
                // under the receiver's authenticated owner context, never from payload hints
                // or the thread's potentially empty after-commit context. Invites/events can
                // reach assigned members; chat and other commands require writable members.
                if (trustedTaskMemberAgentIds != null && !trustedTaskMemberAgentIds.contains(agentId)) {
                    continue;
                }
                boolean inviteOrEvent = AgentProtocolConstants.TYPE_TASK_EVENT.equals(messageType)
                        || (AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(messageType)
                        && AgentProtocolConstants.COMMAND_TASK_INVITE.equals(asString(outbound.get("commandType"))));
                if (!isAuthorizedTaskRecipient(session, taskScope, agentId, inviteOrEvent)) {
                    continue;
                }
            } else if (taskScope != null && !taskScope.tenantId().equals(sessionJiacn(session))) {
                continue;
            }
            delivered = sendEvent(session, outerType, outbound) || delivered;
        }
        return delivered;
    }

    private boolean isAuthorizedTaskRecipient(WebSocketSession session,
            TaskDeliveryScope taskScope, String agentId, boolean allowAssignedMember) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null || !validExactDispatchId(sessionJiacn(session), 50)
                || !validExactDispatchId(sessionClientId(session), 50)
                || !isCanonicalScopeId(taskScope.taskId())) {
            return false;
        }
        EsContext originalContext = EsContextHolder.getContext();
        EsContext ownerContext = new EsContext();
        ownerContext.setJiacn(sessionJiacn(session));
        ownerContext.setClientId(sessionClientId(session));
        EsContextHolder.setContext(ownerContext);
        try {
            List<String> members = allowAssignedMember
                    ? agentService.listTaskMemberAgentIds("0", taskScope.clientId(), taskScope.taskId())
                    : agentService.listTaskWritableMemberAgentIds("0", taskScope.clientId(), taskScope.taskId());
            return members != null && members.contains(agentId);
        } catch (RuntimeException denied) {
            log.warn("Refusing owner-scoped task delivery because membership could not be verified, "
                            + "clientId={}, taskId={}, agentId={}",
                    taskScope.clientId(), taskScope.taskId(), agentId, denied);
            return false;
        } finally {
            EsContextHolder.setContext(originalContext);
        }
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
        if (!validExactDispatchId(tenantId, 50)
                || !validExactDispatchId(clientId, 50)
                || !isCanonicalScopeId(taskId)) {
            return Set.of();
        }
        try {
            List<String> rawMembers = agentService.listTaskWritableMemberAgentIds(
                    tenantId, clientId, taskId);
            if (rawMembers == null || rawMembers.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<String> members = new LinkedHashSet<>();
            for (String member : rawMembers) {
                if (!isCanonicalScopeId(member) || !members.add(member)) {
                    return Set.of();
                }
            }
            return Set.copyOf(members);
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
        int ready = 0;
        for (WebSocketSession session : sessions.values()) {
            if (exactCommandSessionReady(session, tenantId, clientId, targetAgentId, null)) ready++;
        }
        return ready == 1;
    }

    private boolean matchesManagedSkill(WebSocketSession session,String tenant,String client,String agent,String key,byte[] generation) {
        return session.isOpen() && agent.equals(sessionAgentId(session)) && tenant.equals(sessionJiacn(session))
                && client.equals(sessionClientId(session)) && key.equals(sessionAttribute(session,"managedApiKeyId"))
                && successfullyRegisteredAgentIds(session.getId()).contains(agent)
                && session.getAttributes().get("skillRegistrationHash") instanceof byte[] hash
                && java.security.MessageDigest.isEqual(generation,hash);
    }
    public boolean isManagedSkillSessionReady(String tenant,String client,String agent,String key,byte[] generation) {
        return sessions.values().stream().anyMatch(s->matchesManagedSkill(s,tenant,client,agent,key,generation));
    }
    public AgentRawCommandDispatchResult dispatchManagedSkill(String tenant,String client,String agent,String key,byte[] generation,byte[] raw) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Skill WebSocket I/O inside transaction");
        try {
            var node=STRICT_RAW_COMMAND_JSON.readTree(raw);
            if (!"SKILL_INSTALL".equals(textJson(node,"commandType"))
                    || !validRawCommandEnvelope(tenant,client,textJson(node,"orderId"),agent,raw))
                return AgentRawCommandDispatchResult.rejected();
        } catch(Exception invalid) { return AgentRawCommandDispatchResult.rejected(); }
        // Latest registration generation selects one exact authenticated session, not an owner-wide broadcast.
        for(var session:sessions.values()) if(matchesManagedSkill(session,tenant,client,agent,key,generation)) {
            try { synchronized(session) { session.sendMessage(new TextMessage(raw)); } return AgentRawCommandDispatchResult.sent(1,1); }
            catch(Exception failure) { return AgentRawCommandDispatchResult.sendFailed(1); }
        }
        return AgentRawCommandDispatchResult.offline();
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

    /** Public active projection is execution readiness, not socket presence. Keep caller ACL on get(). */
    public List<AgentRuntimeDTO> getExecutionReadyAgents() {
        AgentService service = agentServiceProvider.getIfAvailable();
        if (service == null) return List.of();
        Map<String, List<WebSocketSession>> current = new LinkedHashMap<>();
        for (WebSocketSession session : sessions.values()) {
            String agent = sessionAgentId(session);
            if (agent != null && typedDeliberationSessionCurrent(session, agent)) {
                current.computeIfAbsent(agent, ignored -> new ArrayList<>()).add(session);
            }
        }
        List<AgentRuntimeDTO> result = new ArrayList<>();
        current.forEach((agent, channels) -> {
            if (channels.size() != 1 || !hasReadyAdapter(channels.getFirst(), agent)) return;
            try { result.add(service.get(agent)); }
            catch (RuntimeException unavailableOrNotOwned) {
                log.debug("Execution-ready Agent is unavailable in caller scope");
            }
        });
        return result;
    }

    private boolean hasReadyAdapter(WebSocketSession session, String agent) {
        String id = session.getId();
        if (!sessionReadyCommandTypes.getOrDefault(id, Set.of()).isEmpty()) return true;
        AgentRuntimeCapabilities chat = sessionRuntimeCapabilities.get(id);
        if (chat != null && chat.modern()
                && chat.decision(InteractionRoute.CHAT) == AgentRuntimeCapabilities.Decision.READY) return true;
        NativeProviderCredentialBindingDeclaration provider = sessionNativeProviderCredentialBinding.get(id);
        boolean providerReady = provider != null && provider.snapshot().state()
                == NativeProviderCredentialBindingLookup.State.READY;
        NativeBountyExecutionDeclaration nativeDeclaration = sessionNativeBountyExecution.get(id);
        if (providerReady && nativeDeclaration != null && nativeDeclaration.snapshot().state()
                == NativeBountyExecutionSessionLookup.State.READY) return true;
        ControlledImageBountyExecutionDeclaration controlled = sessionControlledImageBountyExecution.get(id);
        if (providerReady && controlled != null && controlled.state() == ControlledImageExecutionSessionLookup.State.READY) return true;
        ControlledImageV3Declaration v3 = sessionControlledImageV3.get(id);
        if (providerReady && v3 != null && v3.state == ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY) return true;
        try {
            if (typedDeliberationSessions != null && id.equals(typedDeliberationSessions.requireSingleReady(
                    new TypedDeliberationSessionRegistry.Scope(sessionTenantId(session), sessionJiacn(session),
                            sessionClientId(session)), agent).sessionId())) return true;
        } catch (IllegalStateException notReady) { /* Independent adapter, do not disable siblings. */ }
        try {
            return typedInspectionSessions != null && id.equals(typedInspectionSessions.requireSingleReady(
                    new TypedInspectionSessionRegistry.Scope(sessionTenantId(session), sessionJiacn(session),
                            sessionClientId(session)), agent).sessionId());
        } catch (IllegalStateException notReady) { return false; }
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

    private ServerResolvedAgentSender resolveAgentSender(
            WebSocketSession session, Map<String, Object> payload, String authenticatedAgentId) {
        try {
            AgentRuntimeDTO runtime = null;
            AgentService agentService = agentServiceProvider.getIfAvailable();
            if (agentService != null) {
                try {
                    runtime = withSessionContext(session, () -> agentService.get(authenticatedAgentId));
                } catch (RuntimeException unavailableRuntime) {
                    log.debug("Agent runtime display name unavailable; using authenticated agent id");
                }
            }
            String displayName = AgentSenderIdentityResolver.resolve(runtime, authenticatedAgentId);
            return new ServerResolvedAgentSender(
                    ServerResolvedAgentSender.AGENT_TYPE,
                    displayName,
                    sessionJiacn(session),
                    sessionClientId(session),
                    authenticatedAgentId);
        } catch (RuntimeException invalidIdentity) {
            sendError(session, payload, "AGENT_IDENTITY_UNAVAILABLE",
                    "Authenticated Agent identity is unavailable");
            return null;
        }
    }

    private cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof sessionProof(WebSocketSession session) {
        Object proof = session.getAttributes().get(cn.jia.chat.config.AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE);
        if (!(proof instanceof cn.jia.agent.security.AgentRuntimeAuthenticationService.Proof verified)) {
            throw new IllegalArgumentException("Runtime proof unavailable");
        }
        return verified;
    }

    private void validateSessionEnvelope(WebSocketSession session, Map<String, Object> payload) {
        var proof = sessionProof(session);
        var scope = proof.scope();
        for (var entry : Map.of("tenantId", scope.tenantId(), "clientId", scope.clientId(),
                "runtimeInstanceId", scope.runtimeInstanceId(), "hostId", proof.hostId()).entrySet()) {
            if (payload.containsKey(entry.getKey()) && !entry.getValue().equals(payload.get(entry.getKey()))) {
                throw new cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException(
                        "runtimeInstanceId".equals(entry.getKey()) ? "RUNTIME_INSTANCE_ID_MISMATCH" : "SESSION_PROOF_MISMATCH",
                        "Runtime proof mismatch");
            }
        }
        // installationId also denotes existing SKILL installation business keys, so only runtimeInstallationId is universal.
        if (payload.containsKey("runtimeInstallationId") && !proof.installationId().equals(payload.get("runtimeInstallationId"))) {
            throw new IllegalArgumentException("Runtime installation mismatch");
        }
        if (payload.containsKey("sessionGeneration")
                && !Objects.equals(proof.sessionGeneration(), exactLong(payload.get("sessionGeneration")))) {
            throw new IllegalArgumentException("Runtime generation mismatch");
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
        if (runtimeAuthentication != null) runtimeAuthentication.disconnect(session.getId());
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
        context.setTenantId(sessionTenantId(session));
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

    private void requireNoActiveTransactionForChatDelivery() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Chat delivery attempted before transaction commit");
        }
    }

    private String sessionTenantId(WebSocketSession session) {
        return sessionAttribute(session, "tenantId");
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

    private static final class ControlledImageV3Declaration {
        private static final String TRANSPORT="PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V3";
        private final ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State state;
        private final List<String> operations;
        private ControlledImageV3Declaration(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State state,List<String> operations){this.state=state;this.operations=List.copyOf(operations);}
        static ControlledImageV3Declaration parse(Object raw){
            if(raw==null)return new ControlledImageV3Declaration(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.UNDECLARED,List.of());
            try{if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("schemaVersion","enabled","transport","commandSchemaVersions","leaseProtocolVersions","providerStartFenceVersions","resultCommitProtocolVersions","operations"))||!Integer.valueOf(1).equals(m.get("schemaVersion"))||!(m.get("enabled") instanceof Boolean enabled)||!TRANSPORT.equals(m.get("transport"))||!List.of(3).equals(m.get("commandSchemaVersions"))||!List.of(1).equals(m.get("leaseProtocolVersions"))||!List.of(3).equals(m.get("providerStartFenceVersions"))||!List.of(1).equals(m.get("resultCommitProtocolVersions"))||!(m.get("operations") instanceof List<?> list))throw new IllegalArgumentException();
                if(!enabled){if(!list.isEmpty())throw new IllegalArgumentException();return new ControlledImageV3Declaration(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.DISABLED,List.of());}
                List<String> ops=new ArrayList<>();for(Object item:list){if(!(item instanceof Map<?,?> op)||!op.keySet().equals(Set.of("operation","inputManifest","resultManifest")))throw new IllegalArgumentException();String operation=Objects.toString(op.get("operation"),"");if(!Set.of("GENERATE_IMAGE","EDIT_IMAGE").contains(operation)||ops.contains(operation))throw new IllegalArgumentException();if(!(op.get("inputManifest") instanceof Map<?,?> input)||!input.keySet().equals(Set.of("schemaVersion","minItems","maxItems","mimeTypes","sourceKinds"))||!Integer.valueOf(3).equals(input.get("schemaVersion"))||!Integer.valueOf("EDIT_IMAGE".equals(operation)?1:0).equals(input.get("minItems"))||!Integer.valueOf("EDIT_IMAGE".equals(operation)?1:16).equals(input.get("maxItems"))||!List.of("image/jpeg","image/png").equals(input.get("mimeTypes"))||!List.of("TASK_LINKED_WORKSPACE_VERSION","CURRENT_CONVERSATION_ASSET").equals(input.get("sourceKinds")))throw new IllegalArgumentException();if(!(op.get("resultManifest") instanceof Map<?,?> result)||!result.keySet().equals(Set.of("schemaVersion","minItems","maxItems","outputId","mimeTypes"))||!Integer.valueOf(1).equals(result.get("schemaVersion"))||!Integer.valueOf(1).equals(result.get("minItems"))||!Integer.valueOf(1).equals(result.get("maxItems"))||!"output_1".equals(result.get("outputId"))||!List.of("image/png").equals(result.get("mimeTypes")))throw new IllegalArgumentException();ops.add(operation);}if(!Set.copyOf(ops).equals(Set.of("GENERATE_IMAGE","EDIT_IMAGE")))throw new IllegalArgumentException();return new ControlledImageV3Declaration(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,ops);
            }catch(RuntimeException invalid){return new ControlledImageV3Declaration(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.UNSUPPORTED,List.of());}}
        Map<String,Object> normalizedForReceipt(){return state==ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY?Map.of("state",state.name(),"schemaVersion",1,"transport",TRANSPORT,"supportedOperations",operations):Map.of("state",state.name());}
        ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration session(String runtime){return new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration(state,state==ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY?runtime:null,operations);}
        ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration combine(String runtime,NativeProviderCredentialBindingLookup.Snapshot b){if(state!=ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY)return empty(state);if(b==null||b.state()!=NativeProviderCredentialBindingLookup.State.READY)return empty(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.MISMATCHED);return new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration(state,runtime,operations,b.providerLane(),b.bindingId(),b.bindingEpoch(),b.modelId(),b.maxInputItems(),b.maxOutboundRequestAttempts(),b.precallFenceVersion());}
        static ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration empty(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State s){return new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration(s,null,List.of(),null,null,null,null,null,null,null);}
    }

    private record TaskDeliveryScope(String tenantId, String clientId, String taskId) {
    }

    private record StreamState(String sessionId, String requestId, String conversationId, Disposable disposable) {
        private void dispose() {
            disposable.dispose();
        }
    }
}
