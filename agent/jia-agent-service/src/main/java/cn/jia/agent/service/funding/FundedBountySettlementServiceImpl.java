package cn.jia.agent.service.funding;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.*;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskSettlementMapper;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.JsonUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Reads committed legacy financial facts without a preview flag or a debit/capture capability. */
@Service
public final class FundedBountySettlementServiceImpl implements FundedBountySettlementService {
    private final AgentTaskFundingMapper funding;
    private final AgentTaskSettlementMapper settlements;
    private final AgentTaskMutationTransaction roots;

    public FundedBountySettlementServiceImpl(AgentTaskFundingMapper funding,
            AgentTaskSettlementMapper settlements, AgentTaskMutationTransaction roots) {
        this.funding = funding;
        this.settlements = settlements;
        this.roots = roots;
    }

    public AgentTaskSettlementDTO read(FundedBountyActor actor, String taskId) {
        validateActor(actor); validateId(taskId, 100);
        return locked(actor, taskId, root -> {
            AgentTaskFundingEntity row = ownedFunding(actor, taskId);
            AgentTaskSettlementEntity settled = settlements.findForUpdate(actor.tenantId(), actor.clientId(), taskId);
            AgentTaskSettlementReceiptDTO receipt = null;
            AgentTaskFundingCancelReceiptDTO cancel = null;
            switch (row.getFundingStatus()) {
                case "SETTLED" -> {
                    if (settled == null || !actor.userId().equals(settled.getPrincipalId())
                            || !Objects.equals(row.getVersion(), settled.getFundingVersion())
                            || !Objects.equals(row.getEscrowVersion(), settled.getEscrowVersion())
                            || !Objects.equals(row.getGrossBountyAmountWen(), settled.getGrossWen())
                            || !Objects.equals(row.getEscrowId(), settled.getEscrowId())
                            || !Long.valueOf(0).equals(row.getRemainingWen())) throw unavailable("Settlement audit mismatch");
                    receipt = receipt(settled);
                }
                case "REFUNDED" -> {
                    if (settled != null || row.getRefundTransactionId() == null) throw unavailable("Refund audit mismatch");
                    cancel = new AgentTaskFundingCancelReceiptDTO(taskId, "REFUNDED", row.getRefundTransactionId(),
                            decimal(row.getCancelRefundedWen()), decimal(row.getRemainingWen()),
                            decimal(row.getCancelTaskVersion()), decimal(row.getVersion()), decimal(row.getRefundedAt()));
                }
                case "FUNDS_HELD" -> { if (settled != null) throw unavailable("Held audit mismatch"); }
                default -> throw conflict("Funding is not readable in its current state");
            }
            return new AgentTaskSettlementDTO(taskId, row.getFundingStatus(), row.getSettlementPolicy(), row.getEscrowId(),
                    decimal(row.getGrossBountyAmountWen()), decimal(row.getRemainingWen()), decimal(root.getTaskVersion()),
                    decimal(row.getVersion()), receipt, cancel);
        });
    }

    private AgentTaskFundingEntity ownedFunding(FundedBountyActor actor, String taskId) {
        AgentTaskFundingEntity row = funding.selectFundingForUpdate(actor.tenantId(), actor.clientId(), taskId);
        if (row == null || !"USER".equals(row.getPayerPrincipalType()) || !actor.userId().equals(row.getPayerPrincipalId())
                || !actor.tenantId().equals(row.getTenantId()) || !actor.clientId().equals(row.getClientId())
                || !taskId.equals(row.getTaskId())) throw error(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
        if (!"GROSS_INCLUSIVE".equals(row.getSettlementPolicy()) || !"FUNDED_SINGLE_AGENT".equals(row.getFundingMode())) {
            throw conflict("Unsupported settlement policy");
        }
        return row;
    }

    private <T> T locked(FundedBountyActor actor, String taskId, Function<AgentTaskMetaEntity, T> action) {
        try {
            return roots.executeWithLockedTaskRootInOwnerScope(
                    actor.tenantId(), actor.clientId(), actor.ownerJiacn(), taskId, action::apply);
        } catch (AgentTaskCollaborationException scope) {
            if (scope.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND
                    || scope.getReason() == AgentTaskCollaborationException.Reason.FORBIDDEN) {
                throw error(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
            }
            throw unavailable("Task root unavailable");
        }
    }

    private static AgentTaskSettlementReceiptDTO receipt(AgentTaskSettlementEntity row) {
        List<String> ids = JsonUtil.jsonToList(row.getTransactionIds(), String.class);
        if (ids == null || ids.isEmpty() || ids.size() > 3 || ids.stream().anyMatch(Objects::isNull)
                || !"SETTLED".equals(row.getStatus()) || !Long.valueOf(0).equals(row.getRefundedWen())) {
            throw unavailable("Settlement receipt is corrupt");
        }
        return new AgentTaskSettlementReceiptDTO(row.getTaskId(), "SETTLED", row.getQuoteId(), row.getAgentId(),
                row.getEscrowId(), decimal(row.getGrossWen()), decimal(row.getActualComputeWen()),
                decimal(row.getPlatformFeeWen()), decimal(row.getAgentPayoutWen()), "0", "0",
                decimal(row.getTaskVersion()), decimal(row.getFundingVersion()), decimal(row.getEscrowVersion()),
                decimal(row.getSettledAt()), ids);
    }

    static void validateActor(FundedBountyActor actor) {
        if (actor == null) throw error(HttpStatus.UNAUTHORIZED, "ECONOMY_UNAUTHENTICATED", "Authentication required");
        if (!"0".equals(actor.tenantId())) throw bad("tenantId must be 0");
        validateId(actor.clientId(), 50); validateId(actor.ownerJiacn(), 50); validateId(actor.userId(), 100);
        if ("0".equals(actor.ownerJiacn())) throw bad("ownerJiacn must not be 0");
    }

    private static void validateId(String id, int limit) {
        if (id == null || id.isEmpty() || !id.equals(id.strip()) || id.getBytes(StandardCharsets.UTF_8).length > limit
                || id.codePoints().anyMatch(Character::isISOControl)) throw bad("Invalid exact identifier");
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= id.length() || !Character.isLowSurrogate(id.charAt(i))) throw bad("Invalid Unicode");
            } else if (Character.isLowSurrogate(ch)) throw bad("Invalid Unicode");
        }
    }

    private static String decimal(Long value) {
        if (value == null || value < 0) throw unavailable("Invalid audit number");
        return Long.toString(value);
    }

    private static FundedBountyException bad(String message) { return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message); }
    private static FundedBountyException conflict(String message) { return error(HttpStatus.CONFLICT, "FUNDED_BOUNTY_CONFLICT", message); }
    private static FundedBountyException unavailable(String message) { return new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE, "FUNDED_BOUNTY_UNAVAILABLE", message, true); }
    private static FundedBountyException error(HttpStatus status, String code, String message) { return new FundedBountyException(status, code, message); }
}
