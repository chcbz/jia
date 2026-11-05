package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.mapper.SkillTradeMapper;
import cn.jia.economy.service.*;
import cn.jia.economy.service.skill.SkillOrder;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillTradeServiceTest {
    private final SkillTradeMapper trades=mock(SkillTradeMapper.class);
    private final EconomyLedgerMapper ledger=mock(EconomyLedgerMapper.class);
    private final EconomyPostingService posting=mock(EconomyPostingService.class);
    private final SkillTradeServiceImpl service=new SkillTradeServiceImpl(trades,ledger,posting,
            new EconomyPreviewGate(new EconomyPreviewProperties(false,List.of())));
    private final EconomyScope scope=new EconomyScope("0","client");
    private final EconomyPrincipal actor=new EconomyPrincipal(EconomyPrincipalType.USER,"buyer");

    @Test void freeSettlementDoesNotRequireEconomyOrInventFinancialEffects() {
        assertFalse(service.allows("0","client"));
        assertNull(service.reserve(scope,actor,"order",0,1));
        var order=new SkillOrder().setOrderId("order").setPriceWen(0L);
        assertNull(service.settle(scope,actor,order,null,true,1));
        assertNull(service.settle(scope,actor,order,null,false,1));
        verifyNoInteractions(trades,ledger,posting);
    }

    @Test void paidOperationsFailBeforeAnyWriteWhenEconomyIsDisabled() {
        var reserve=assertThrows(EconomyPostingException.class,()->service.reserve(scope,actor,"order",1,1));
        assertEquals(EconomyPostingException.Reason.FEATURE_DISABLED,reserve.reason());
        var order=new SkillOrder().setOrderId("order").setPriceWen(1L);
        for(boolean capture:new boolean[]{true,false}) {
            var failure=assertThrows(EconomyPostingException.class,()->service.settle(scope,actor,order,1L,capture,1));
            assertEquals(EconomyPostingException.Reason.FEATURE_DISABLED,failure.reason());
        }
        verifyNoInteractions(trades,ledger,posting);
    }
}
