package cn.jia.agent.skill;

import cn.jia.agent.dao.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.economy.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkillManagementRecommendationTest {
    @SuppressWarnings("unchecked")
    @Test void readOnlyFactsDoNotPrepareUnrelatedPackagesCreateQuotesOrSendCommands() {
        var db = mock(EconomySkillManagementMapper.class); var market = mock(EconomySkillMarketplaceMapper.class);
        var app = mock(EconomySkillApplicationMapper.class); var marketplace = mock(SkillMarketplaceService.class);
        var versions = mock(SkillAgentVersions.class); var runtime = mock(AgentRuntimeDao.class);
        var installations = mock(AgentRuntimeV1InstallationDao.class); var auth = mock(AgentRuntimeAuthenticationService.class);
        ObjectProvider<SkillPackages> packages = mock(ObjectProvider.class);
        ObjectProvider<SkillInstallCommandWriter> writer = mock(ObjectProvider.class);
        ObjectProvider<AgentManagedSessionLookup> sessions = mock(ObjectProvider.class);
        ObjectProvider<SkillCapabilityLookup> capabilities = mock(ObjectProvider.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.products("0", "client")).thenReturn(List.of());
        when(db.states("0", "client", "agent")).thenReturn(List.of());
        when(auth.currentRegisteredProof("0", "client", "agent")).thenThrow(new IllegalArgumentException("offline"));
        var service = new SkillManagementService(db, market, app, marketplace, versions, runtime, installations,
                auth, mock(cn.jia.agent.service.AgentIdentityService.class), packages, writer, sessions, capabilities, tx);
        var actor = new HostingRentHttp.Actor("user", "0", "client", "owner");
        when(marketplace.available(actor)).thenReturn(true);
        assertEquals(List.of(), service.recommendationSkills(actor, "agent"));
        verify(versions, atLeastOnce()).readOwned(actor, "agent");
        verify(marketplace).available(actor);
        verifyNoInteractions(packages, writer, market, app);
    }
}
