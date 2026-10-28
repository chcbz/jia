package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server-only first image execution lane. A model answer or browser proposal cannot enter here:
 * only a committed, owner-scoped step and the exact persisted grant can authorize CREATE.
 * Re-reads both before side effects. A single database transaction binds the durable link;
 * retries use the immutable execution intent, never a new chargeable intent.
 */
@Service
public class ChatBountyExecutionCoordinator {
    private final JdbcTemplate jdbc;
    private final ChatInteractionStepStore steps;
    private final ChatDeliberationDao requests;
    private final ChatConversationDao conversations;
    private final AgentTaskExecutionGrantService grants;
    private final AgentTaskRequirementSnapshotService requirements;
    private final PersonalWorkspaceExecutionService executions;
    private final ObjectMapper json;

    public ChatBountyExecutionCoordinator(JdbcTemplate jdbc, ChatInteractionStepStore steps,
            ChatDeliberationDao requests, ChatConversationDao conversations,
            AgentTaskExecutionGrantService grants, AgentTaskRequirementSnapshotService requirements,
            PersonalWorkspaceExecutionService executions, ObjectMapper json) {
        this.jdbc=Objects.requireNonNull(jdbc); this.steps=Objects.requireNonNull(steps);
        this.requests=Objects.requireNonNull(requests); this.conversations=Objects.requireNonNull(conversations);
        this.grants=Objects.requireNonNull(grants); this.requirements=Objects.requireNonNull(requirements);
        this.executions=Objects.requireNonNull(executions); this.json=Objects.requireNonNull(json);
    }

    public record Pending(String tenantId, String ownerJiacn, String clientId,
            String requestId, String stepId) { }

    /** Retired v1-v3 queue: history remains untouched and is never re-admitted. */
    public List<Pending> pending(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid candidate limit");
        return List.of();
    }

    public String coordinate(Pending candidate) {
        if (candidate == null) throw new IllegalArgumentException("Missing candidate");
        return "RETIRED";
    }
}
