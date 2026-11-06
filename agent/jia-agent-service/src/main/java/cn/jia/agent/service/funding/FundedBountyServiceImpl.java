package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingEntity;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.core.util.JsonUtil;
import cn.jia.economy.common.WenAmount;
import tools.jackson.core.type.TypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Read-only historical funded-task projection. Never creates, refunds, or resumes old work. */
@Service
public final class FundedBountyServiceImpl implements FundedBountyService {
    private static final String MODE = "FUNDED_SINGLE_AGENT";
    private static final String FUNDS_HELD = "FUNDS_HELD";
    private static final String REFUNDED = "REFUNDED";
    private final AgentTaskFundingMapper fundingMapper;

    public FundedBountyServiceImpl(AgentTaskFundingMapper fundingMapper) {
        this.fundingMapper = Objects.requireNonNull(fundingMapper, "fundingMapper");
    }

    public AgentTaskFundingDTO findFunding(String tenantId, String clientId, String taskId) {
        validateId(tenantId, "tenantId", 50);
        validateId(clientId, "clientId", 50);
        validateId(taskId, "taskId", 100);
        AgentTaskFundingEntity funding = fundingMapper.selectFunding(tenantId, clientId, taskId);
        return funding == null ? null : fundingDto(funding);
    }

    private AgentTaskFundingDTO fundingDto(AgentTaskFundingEntity funding) {
        validateFunding(funding);
        AgentTaskFundingDTO dto = new AgentTaskFundingDTO();
        dto.setMode(funding.getFundingMode());
        dto.setStatus(funding.getFundingStatus());
        dto.setEscrowId(funding.getEscrowId());
        dto.setGrossBountyAmountWen(WenAmount.format(funding.getGrossBountyAmountWen()));
        dto.setRemainingWen(WenAmount.format(funding.getRemainingWen()));
        return dto;
    }

    public List<AgentSkillRequirementDTO> requiredSkills(String tenantId, String clientId, String taskId) {
        AgentTaskFundingEntity funding = fundingMapper.selectFunding(tenantId, clientId, taskId);
        if (funding == null) return List.of();
        try {
            return List.copyOf(JsonUtil.getMapper().readValue(funding.getRequiredSkillRequirements(),
                    new TypeReference<List<AgentSkillRequirementDTO>>() { }));
        } catch (Exception exception) {
            throw unavailable("Funded task skill requirements are corrupt");
        }
    }

    private void validateFunding(AgentTaskFundingEntity funding) {
        if (funding == null || !MODE.equals(funding.getFundingMode())
                || !(FUNDS_HELD.equals(funding.getFundingStatus()) || REFUNDED.equals(funding.getFundingStatus())
                    || "SETTLED".equals(funding.getFundingStatus()))
                || funding.getGrossBountyAmountWen() == null || funding.getGrossBountyAmountWen() <= 0
                || funding.getRemainingWen() == null || funding.getRemainingWen() < 0
                || funding.getRemainingWen() > funding.getGrossBountyAmountWen()
                || funding.getEscrowId() == null || funding.getEscrowVersion() == null
                || funding.getReserveTransactionId() == null || funding.getVersion() == null) {
            throw unavailable("Funded task projection is corrupt");
        }
    }

    private static void validateId(String value, String name, int maxBytes) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest(name + " is invalid");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static FundedBountyException badRequest(String message) {
        return new FundedBountyException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    private static FundedBountyException unavailable(String message) {
        return new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE, "FUNDED_BOUNTY_UNAVAILABLE", message, true);
    }

}
