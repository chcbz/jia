package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyPostingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises Spring constructor selection, never a supplier or direct new of the subject. */
class EconomyPostingServiceConstructorContextTest {
    @Test
    void springSelectsProductionConstructorWithoutResolvingTestSeams() {
        EconomyLedgerMapper mapper = mock(EconomyLedgerMapper.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        EconomyPreviewGate gate = new EconomyPreviewGate(new EconomyPreviewProperties(false, List.of()));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("ledgerMapper", mapper);
            context.getBeanFactory().registerSingleton("transactionManager", transactions);
            context.getBeanFactory().registerSingleton("previewGate", gate);
            context.register(EconomyPostingServiceImpl.class);
            context.refresh();

            EconomyPostingServiceImpl service = context.getBean(EconomyPostingServiceImpl.class);
            assertNotNull(service);
            assertSame(service, context.getBean(EconomyPostingService.class));
            verifyNoInteractions(mapper, transactions);
        }
    }

    @Test
    void missingTransactionManagerFailsRatherThanUsingCompatibilityConstruction() {
        EconomyLedgerMapper mapper = mock(EconomyLedgerMapper.class);
        EconomyPreviewGate gate = new EconomyPreviewGate(new EconomyPreviewProperties(false, List.of()));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("ledgerMapper", mapper);
            context.getBeanFactory().registerSingleton("previewGate", gate);
            context.register(EconomyPostingServiceImpl.class);

            assertThrows(UnsatisfiedDependencyException.class, context::refresh);
            verifyNoInteractions(mapper);
        }
    }
}
