package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyTreasuryPostingService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Package-owned treasury seam. Its only construction path is the service container; callers cannot
 * use the generic posting service to manufacture issuance authority.
 */
@Service
final class EconomyTreasuryPostingServiceImpl implements EconomyTreasuryPostingService {
    private final EconomyPostingServiceImpl postingService;

    EconomyTreasuryPostingServiceImpl(EconomyPostingServiceImpl postingService) {
        this.postingService = Objects.requireNonNull(postingService, "postingService");
    }

    @Override
    public EconomyPostingResult issue(EconomyPostingCommand command) {
        if (command == null || command.journalType() != EconomyJournalType.ISSUE_SILVER) {
            throw new EconomyPostingException(EconomyPostingException.Reason.INVALID_COMMAND,
                    "treasury authority only permits ISSUE_SILVER");
        }
        return postingService.postTreasuryIssue(command);
    }
}
