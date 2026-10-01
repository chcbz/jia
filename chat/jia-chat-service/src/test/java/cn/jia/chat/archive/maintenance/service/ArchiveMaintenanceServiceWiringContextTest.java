package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.archive.store.ArchiveContentStore;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.model.ArchiveAppointmentRecord;
import cn.jia.chat.archive.maintenance.model.ArchiveManagerGrantRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

/** Registers the production component itself so Spring must select its public six-argument constructor. */
class ArchiveMaintenanceServiceWiringContextTest {
    @Test
    void springCreatesServiceFromProductionDependenciesWithoutAClockBean() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        ArchiveContentStore content = mock(ArchiveContentStore.class);
        ArchiveTransactions transactions = mock(ArchiveTransactions.class);
        AgentIdentityService identities = mock(AgentIdentityService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        AgentTaskArtifactStorage sourceStorage = mock(AgentTaskArtifactStorage.class);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("archiveMaintenanceStore", store);
            context.getBeanFactory().registerSingleton("archiveContentStore", content);
            context.getBeanFactory().registerSingleton("archiveTransactions", transactions);
            context.getBeanFactory().registerSingleton("agentIdentityService", identities);
            context.getBeanFactory().registerSingleton("objectMapper", mapper);
            context.getBeanFactory().registerSingleton("agentTaskArtifactStorage", sourceStorage);
            context.register(ArchiveMaintenanceServiceImpl.class);

            context.refresh();

            ArchiveMaintenanceServiceImpl service = context.getBean(ArchiveMaintenanceServiceImpl.class);
            assertNotNull(service);
            assertSame(service, context.getBean(ArchiveMaintenanceService.class));
            verifyNoInteractions(store, content, transactions, identities, mapper, sourceStorage);
        }
    }

    @Test
    void optionalResolverBeanIsInjectedButVerifiedProofStillCannotUnlockExecution() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        ArchiveContentStore content = mock(ArchiveContentStore.class);
        ArchiveTransactions transactions = mock(ArchiveTransactions.class);
        AgentIdentityService identities = mock(AgentIdentityService.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        AgentTaskArtifactStorage sourceStorage = mock(AgentTaskArtifactStorage.class);
        InstalledSkillResolver resolver=mock(InstalledSkillResolver.class);
        ArchiveActorScope actor=new ArchiveActorScope("0","client-a","owner-a");
        String sha="a".repeat(64);
        when(store.findManagerGrant(actor,"collection-a",false)).thenReturn(new ArchiveManagerGrantRecord(
                "collection-a","0","client-a","owner-a","appoint",1,"ACTIVE"));
        when(store.listAppointments(actor,"collection-a")).thenReturn(java.util.List.of(new ArchiveAppointmentRecord(
                "apt-a","collection-a","ARCHIVE_EDITOR","0","client-a","owner-a","agent-a","7",
                "COLLECTION","","DRAFT_ONLY","archive-maintainer","1.0.0",sha,"ACTIVE",1,
                java.time.Instant.parse("2026-09-30T00:00:00Z"),null)));
        when(resolver.resolve(any())).thenReturn(new InstalledSkillResolver.Resolution(
                InstalledSkillResolver.State.VERIFIED,new InstalledSkillResolver.Proof(
                        "psi-a",2,"archive-maintainer","1.0.0",sha)));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("archiveMaintenanceStore", store);
            context.getBeanFactory().registerSingleton("archiveContentStore", content);
            context.getBeanFactory().registerSingleton("archiveTransactions", transactions);
            context.getBeanFactory().registerSingleton("agentIdentityService", identities);
            context.getBeanFactory().registerSingleton("objectMapper", mapper);
            context.getBeanFactory().registerSingleton("agentTaskArtifactStorage", sourceStorage);
            context.getBeanFactory().registerSingleton("installedSkillResolver", resolver);
            context.register(ArchiveMaintenanceServiceImpl.class);
            context.refresh();

            var appointment=context.getBean(ArchiveMaintenanceServiceImpl.class).appointments(actor,"collection-a").getFirst();
            assertEquals("VERIFIED",appointment.skillReadiness().state());
            assertEquals("CLIENT_UPDATE_REQUIRED",appointment.readiness());
            assertFalse(appointment.skillReadiness().executable());
            assertEquals("EXECUTION_NOT_WIRED",appointment.skillReadiness().blocker());
            verify(resolver).resolve(any());
        }
    }
}

