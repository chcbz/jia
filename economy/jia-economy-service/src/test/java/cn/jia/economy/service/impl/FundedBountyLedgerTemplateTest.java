package cn.jia.economy.service.impl;

import cn.jia.economy.bounty.FundedBountyRefundCommand;
import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPostingService;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundedBountyLedgerTemplateTest {
    @Test
    void refundUsesExactBountyTaskEscrowToAuthenticatedUserAvailableTemplate() {
        EconomyPostingService posting = mock(EconomyPostingService.class);
        when(posting.post(any())).thenReturn(new EconomyPostingResult(
                "etx-refund", "POSTED", "SILVER", 100L, 50L, 50L, List.of(), null));
        FundedBountyLedgerServiceImpl service = new FundedBountyLedgerServiceImpl(
                mock(EconomyLedgerMapper.class), posting);
        byte[] hash = new byte[32];

        service.refund(new FundedBountyRefundCommand(new EconomyScope("tenant", "client"),
                new EconomyPrincipal(EconomyPrincipalType.USER, "user-1"),
                "018f0000-0000-7000-8000-000000000006", hash,
                "task-1", 50L, 1L, "etx-reserve"));

        ArgumentCaptor<EconomyPostingCommand> command = ArgumentCaptor.forClass(EconomyPostingCommand.class);
        verify(posting).post(command.capture());
        EconomyPostingCommand value = command.getValue();
        assertEquals(EconomyJournalType.REFUND_BOUNTY, value.journalType());
        assertEquals(EconomyEscrowType.BOUNTY, value.escrowSettlement().escrowType());
        assertEquals(EconomyAccountOwnerType.TASK, value.escrowSettlement().escrowAccount().ownerType());
        assertEquals(EconomyAccountPurpose.ESCROW, value.escrowSettlement().escrowAccount().purpose());
        assertEquals(EconomyAccountOwnerType.USER, value.escrowSettlement().destinationAccount().ownerType());
        assertEquals(EconomyAccountPurpose.AVAILABLE, value.escrowSettlement().destinationAccount().purpose());
        assertEquals("user-1", value.escrowSettlement().destinationAccount().ownerId());
        assertEquals("etx-reserve", value.escrowSettlement().reserveTransactionId());
    }
}
