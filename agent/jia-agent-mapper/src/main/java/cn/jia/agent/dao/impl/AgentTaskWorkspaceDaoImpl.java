package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;
import cn.jia.agent.mapper.AgentTaskWorkspaceMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

@Named
public class AgentTaskWorkspaceDaoImpl implements AgentTaskWorkspaceDao {
    private final AgentTaskWorkspaceMapper mapper;

    @Inject
    public AgentTaskWorkspaceDaoImpl(AgentTaskWorkspaceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public TaskRow findTask(String tenantId, String clientId, String ownerJiacn, String taskId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        return mapper.findTask(tenantId, clientId, ownerJiacn, taskId);
    }

    @Override
    public MemberRow findActorMember(
            String tenantId, String clientId, String ownerJiacn, String taskId, String actorAgentId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        requireId(actorAgentId, "actorAgentId", 100);
        return mapper.findActorMember(tenantId, clientId, ownerJiacn, taskId, actorAgentId);
    }

    @Override
    public List<MemberRow> findMembers(String tenantId, String clientId, String ownerJiacn, String taskId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        return mapper.findMembers(tenantId, clientId, ownerJiacn, taskId);
    }

    @Override
    public List<WorkItemRow> findWorkItems(String tenantId, String clientId, String ownerJiacn, String taskId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        return mapper.findWorkItems(tenantId, clientId, ownerJiacn, taskId);
    }

    @Override
    public List<RequestRow> findOpenRequests(String tenantId, String clientId, String ownerJiacn, String taskId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        return mapper.findOpenRequests(tenantId, clientId, ownerJiacn, taskId);
    }

    @Override
    public List<ArtifactRow> findVisibleArtifacts(String tenantId, String clientId, String ownerJiacn, String taskId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        requireId(actorAgentId, "actorAgentId", 100);
        return mapper.findVisibleArtifacts(tenantId, clientId, ownerJiacn, taskId, actorAgentId,
                reviewerAccess, coordinatorAccess);
    }

    @Override
    public ArtifactRow findArtifactVersion(String tenantId, String clientId, String ownerJiacn, String taskId,
            String artifactId, int artifactVersion) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        requireId(artifactId, "artifactId", 100);
        if (artifactVersion <= 0) {
            throw new IllegalArgumentException("artifactVersion must be positive");
        }
        return mapper.findArtifactVersion(
                tenantId, clientId, ownerJiacn, taskId, artifactId, artifactVersion);
    }

    @Override
    public List<EventRow> findLatestEvents(String tenantId, String clientId, String ownerJiacn, String taskId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        return mapper.findLatestEvents(tenantId, clientId, ownerJiacn, taskId);
    }

    private static void requireScope(String tenantId, String clientId, String ownerJiacn, String taskId) {
        if (!"0".equals(tenantId)) {
            throw new IllegalArgumentException("tenantId must be literal 0");
        }
        requireId(clientId, "clientId", 50);
        requireId(ownerJiacn, "ownerJiacn", 50);
        if ("0".equals(ownerJiacn)) {
            throw new IllegalArgumentException("ownerJiacn must be a real task owner");
        }
        requireId(taskId, "taskId", 100);
    }

    private static void requireId(String value, String name, int maxLength) {
        if (value == null || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maxLength
                || value.codePoints().allMatch(AgentTaskWorkspaceDaoImpl::isPadding)
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
