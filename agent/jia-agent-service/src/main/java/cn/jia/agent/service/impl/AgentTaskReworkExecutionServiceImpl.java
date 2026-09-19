package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskReworkExecutionService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/**
 * Owner-scoped adapter from a terminal formal rejection to the normal durable TASK queue.
 * It authorizes one exact published output/version while holding the task root; Provider work is
 * never performed here and retries are delegated to the queue's existing idempotency boundary.
 */
@Named
public class AgentTaskReworkExecutionServiceImpl implements AgentTaskReworkExecutionService {
    private final AgentTaskFormalDeliveryDao formalDeliveries;
    private final PersonalWorkspaceExecutionDao executions;
    private final PersonalWorkspaceExecutionService executionService;
    private final AgentTaskMutationTransaction taskMutations;

    @Inject
    public AgentTaskReworkExecutionServiceImpl(AgentTaskFormalDeliveryDao formalDeliveries,
            PersonalWorkspaceExecutionDao executions,
            PersonalWorkspaceExecutionService executionService,
            AgentTaskMutationTransaction taskMutations) {
        this.formalDeliveries = Objects.requireNonNull(formalDeliveries, "formalDeliveries");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.taskMutations = Objects.requireNonNull(taskMutations, "taskMutations");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PersonalWorkspaceExecutionService.ExecutionView create(
            PersonalWorkspaceExecutionService.OwnerScope scope, ReworkCommand command,
            String idempotencyKey) {
        validate(scope, command, idempotencyKey);
        return taskMutations.executeWithLockedTaskRootInOwnerScope(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), command.taskId(), root -> createLocked(scope, command,
                        idempotencyKey, root));
    }

    private PersonalWorkspaceExecutionService.ExecutionView createLocked(
            PersonalWorkspaceExecutionService.OwnerScope scope, ReworkCommand command,
            String idempotencyKey, AgentTaskMetaEntity root) {
        if (root == null || !same(root.getTaskId(), command.taskId())
                || !same(root.getOwnerJiacn(), scope.ownerJiacn())) {
            throw failure(PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
        }
        AgentTaskFormalDeliveryEntity delivery = formalDeliveries.findForUpdate(
                scope.tenantId(), scope.clientId(), command.formalDeliveryId());
        if (delivery == null || !same(delivery.getTenantId(), scope.tenantId())
                || !same(delivery.getClientId(), scope.clientId())
                || !same(delivery.getTaskId(), command.taskId())) {
            throw failure(PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
        }
        if (!"changes_requested".equals(delivery.getState())
                || delivery.getVersion() == null
                || delivery.getVersion() != command.expectedDecisionVersion()
                || delivery.getReviewedAt() == null || delivery.getReviewedAt() <= 0
                || !same(delivery.getReviewedByJiacn(), scope.ownerJiacn())) {
            throw failure(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT);
        }
        PersonalWorkspaceExecutionOutputEntity source = executions.findPublishedReworkSource(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), command.taskId(),
                command.formalDeliveryId(), command.sourceOutputId(), command.sourceFileId(),
                command.sourceFileVersion());
        if (source == null || !same(source.getTenantId(), scope.tenantId())
                || !same(source.getClientId(), scope.clientId())
                || !same(source.getOwnerJiacn(), scope.ownerJiacn())
                || !same(source.getOutputId(), command.sourceOutputId())
                || !same(source.getFormalDeliveryId(), command.formalDeliveryId())
                || !same(source.getWorkspaceFileId(), command.sourceFileId())
                || source.getWorkspaceFileVersion() == null
                || source.getWorkspaceFileVersion() != command.sourceFileVersion()
                || !"COMMITTED".equals(source.getOutputState())
                || !"PUBLISHED".equals(source.getPublicationState())) {
            throw failure(PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
        }
        PersonalWorkspaceExecutionService.SourceOutputRef sourceRef =
                new PersonalWorkspaceExecutionService.SourceOutputRef(
                        command.formalDeliveryId(), command.expectedDecisionVersion(),
                        command.sourceOutputId(), command.sourceFileId(), command.sourceFileVersion());
        PersonalWorkspaceExecutionService.CreateCommand create =
                new PersonalWorkspaceExecutionService.CreateCommand(command.conversationId(),
                        command.targetAgentId(), command.taskId(), command.instruction(),
                        command.outputContentMimeType(),
                        List.of(new PersonalWorkspaceExecutionService.InputSelection(
                                command.sourceFileId(), command.sourceFileVersion())), sourceRef);
        return executionService.create(scope, create, idempotencyKey);
    }

    private static void validate(PersonalWorkspaceExecutionService.OwnerScope scope,
            ReworkCommand command, String idempotencyKey) {
        if (scope == null || !"0".equals(scope.tenantId())) throw badRequest();
        id(scope.clientId(), 50); id(scope.ownerJiacn(), 50);
        if ("0".equals(scope.ownerJiacn()) || command == null) throw badRequest();
        id(command.taskId(), 100); id(command.formalDeliveryId(), 100);
        id(command.conversationId(), 100); id(command.targetAgentId(), 100);
        id(command.sourceOutputId(), 100); id(command.sourceFileId(), 100);
        text(command.instruction(), 4_000); id(command.outputContentMimeType(), 127);
        id(idempotencyKey, 100);
        if (command.expectedDecisionVersion() < 1 || command.sourceFileVersion() < 1) {
            throw badRequest();
        }
    }

    private static void id(String value, int maximum) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > maximum
                || value.chars().anyMatch(Character::isISOControl)) throw badRequest();
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank()
                || value.codePointCount(0, value.length()) > maximum
                || value.chars().anyMatch(Character::isISOControl)) throw badRequest();
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static PersonalWorkspaceExecutionService.Failure badRequest() {
        return failure(PersonalWorkspaceExecutionService.Reason.BAD_REQUEST);
    }

    private static PersonalWorkspaceExecutionService.Failure failure(
            PersonalWorkspaceExecutionService.Reason reason) {
        return new PersonalWorkspaceExecutionService.Failure(reason);
    }
}
