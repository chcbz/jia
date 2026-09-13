package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskArtifactOutcomeDao;
import cn.jia.agent.entity.AgentTaskAcceptedArtifactRow;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeDecisionEntity;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeEntity;
import cn.jia.agent.mapper.AgentTaskArtifactOutcomeMapper;
import cn.jia.agent.state.AgentTaskArtifactOutcomeState;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskArtifactOutcomeDaoImpl implements AgentTaskArtifactOutcomeDao {
    private final AgentTaskArtifactOutcomeMapper mapper;

    @Inject
    public AgentTaskArtifactOutcomeDaoImpl(AgentTaskArtifactOutcomeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentTaskArtifactOutcomeEntity findForUpdate(
            String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion) {
        requireScopeAndArtifact(tenantId, clientId, taskId, artifactId, artifactVersion);
        return mapper.selectExactForUpdate(
                tenantId, clientId, taskId, artifactId, artifactVersion);
    }

    @Override
    public AgentTaskArtifactOutcomeDecisionEntity findDecisionForUpdate(
            String tenantId, String clientId, String taskId, String decisionId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(decisionId, "decisionId");
        return mapper.selectDecisionForUpdate(tenantId, clientId, taskId, decisionId);
    }

    @Override
    public int insertDecision(String tenantId, String clientId,
            AgentTaskArtifactOutcomeDecisionEntity decision) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireDecision(decision);
        TaskCollaborationDaoSupport.applyScope(decision, tenantId, clientId);
        decision.init4Creation();
        return mapper.insertDecision(decision);
    }

    @Override
    public int insert(String tenantId, String clientId, AgentTaskArtifactOutcomeEntity outcome) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireOutcome(outcome);
        TaskCollaborationDaoSupport.applyScope(outcome, tenantId, clientId);
        if (outcome.getVersion() != 1L) {
            throw new IllegalArgumentException("inserted outcome version must be one");
        }
        outcome.init4Creation();
        return mapper.insert(outcome);
    }

    @Override
    public int updateByVersion(String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion, String expectedState,
            long expectedVersion, AgentTaskArtifactOutcomeEntity outcome) {
        requireScopeAndArtifact(tenantId, clientId, taskId, artifactId, artifactVersion);
        TaskCollaborationDaoSupport.requireId(expectedState, "expectedState");
        TaskCollaborationDaoSupport.requireExpectedVersion(expectedVersion);
        requireOutcome(outcome);
        if (!taskId.equals(outcome.getTaskId()) || !artifactId.equals(outcome.getArtifactId())
                || artifactVersion != outcome.getArtifactVersion()) {
            throw new IllegalArgumentException("outcome does not match update key");
        }
        if (outcome.getVersion() == null || outcome.getVersion() != expectedVersion + 1) {
            throw new IllegalArgumentException("outcome result version is invalid");
        }
        return mapper.updateByVersion(tenantId, clientId, taskId, artifactId, artifactVersion,
                expectedState, expectedVersion, outcome.getOutcomeState(),
                outcome.getSupersededByArtifactId(), outcome.getSupersededByArtifactVersion(),
                outcome.getDecisionId(), outcome.getDecisionDigest(),
                outcome.getDecidedByAgentId(), outcome.getDecidedAt(), outcome.getVersion());
    }

    @Override
    public List<AgentTaskAcceptedArtifactRow> listAuthoritativeAccepted(
            String tenantId, String clientId, String taskId, String workItemId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(actorAgentId, "actorAgentId");
        if (!StringUtil.isBlank(workItemId)) {
            TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        }
        return mapper.selectAuthoritativeAccepted(tenantId, clientId, taskId,
                StringUtil.isBlank(workItemId) ? null : workItemId,
                actorAgentId, reviewerAccess, coordinatorAccess,
                TaskCollaborationDaoSupport.boundedLimit(limit));
    }

    private void requireDecision(AgentTaskArtifactOutcomeDecisionEntity decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        TaskCollaborationDaoSupport.requireId(decision.getTaskId(), "taskId");
        TaskCollaborationDaoSupport.requireId(decision.getDecisionId(), "decisionId");
        TaskCollaborationDaoSupport.requireId(decision.getDecisionDigest(), "decisionDigest");
        if (!decision.getDecisionDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("decisionDigest is invalid");
        }
        TaskCollaborationDaoSupport.requireId(
                decision.getAcceptedArtifactId(), "acceptedArtifactId");
        if (decision.getAcceptedArtifactVersion() == null
                || decision.getAcceptedArtifactVersion() < 1
                || decision.getAcceptedOutcomeVersion() == null
                || decision.getAcceptedOutcomeVersion() < 1) {
            throw new IllegalArgumentException("accepted decision versions must be positive");
        }
        TaskCollaborationDaoSupport.requireId(
                decision.getDecidedByAgentId(), "decidedByAgentId");
        if (decision.getDecidedAt() == null || decision.getDecidedAt() <= 0) {
            throw new IllegalArgumentException("decidedAt must be positive");
        }
    }

    private void requireScopeAndArtifact(String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(artifactId, "artifactId");
        if (artifactVersion < 1) {
            throw new IllegalArgumentException("artifactVersion must be positive");
        }
    }

    private void requireOutcome(AgentTaskArtifactOutcomeEntity outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        TaskCollaborationDaoSupport.requireId(outcome.getTaskId(), "taskId");
        TaskCollaborationDaoSupport.requireId(outcome.getArtifactId(), "artifactId");
        if (outcome.getArtifactVersion() == null || outcome.getArtifactVersion() < 1) {
            throw new IllegalArgumentException("artifactVersion must be positive");
        }
        final AgentTaskArtifactOutcomeState state;
        try {
            state = AgentTaskArtifactOutcomeState.fromPersistedValue(outcome.getOutcomeState());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("outcomeState is invalid", exception);
        }
        boolean acceptedShape = outcome.getSupersededByArtifactId() == null
                && outcome.getSupersededByArtifactVersion() == null;
        boolean supersededShape = outcome.getSupersededByArtifactId() != null
                && outcome.getSupersededByArtifactVersion() != null
                && outcome.getSupersededByArtifactVersion() > 0
                && (!outcome.getArtifactId().equals(outcome.getSupersededByArtifactId())
                || !outcome.getArtifactVersion().equals(
                outcome.getSupersededByArtifactVersion()));
        if (state == AgentTaskArtifactOutcomeState.ACCEPTED && !acceptedShape
                || state == AgentTaskArtifactOutcomeState.SUPERSEDED && !supersededShape) {
            throw new IllegalArgumentException("outcome supersession shape is invalid");
        }
        TaskCollaborationDaoSupport.requireId(outcome.getDecisionId(), "decisionId");
        TaskCollaborationDaoSupport.requireId(outcome.getDecisionDigest(), "decisionDigest");
        if (!outcome.getDecisionDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("decisionDigest is invalid");
        }
        TaskCollaborationDaoSupport.requireId(outcome.getDecidedByAgentId(), "decidedByAgentId");
        if (outcome.getDecidedAt() == null || outcome.getDecidedAt() <= 0
                || outcome.getVersion() == null || outcome.getVersion() < 1) {
            throw new IllegalArgumentException("outcome time and version must be positive");
        }
    }
}
