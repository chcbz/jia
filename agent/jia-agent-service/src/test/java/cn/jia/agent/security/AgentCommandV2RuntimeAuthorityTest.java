package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.service.AgentCommandV2RuntimeAuthority;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The obsolete API-key V2 channel is not an installation-session fallback.
 * These negative merge regressions do not claim durable V2 activation/readiness. */
class AgentCommandV2RuntimeAuthorityTest {
    private final AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
    private final AgentRuntimeV1InstallationDao installations = mock(AgentRuntimeV1InstallationDao.class);
    private final AgentIdentityRegistryDao registry = mock(AgentIdentityRegistryDao.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final AccountSecurityService accounts = mock(AccountSecurityService.class);
    private final AgentRuntimeAuthenticationService authentication =
            new AgentRuntimeAuthenticationService(runtimes, installations, registry, identities,
                    accounts, mock(AgentTaskEventsGate.class));

    @Test void installationAuthenticationDoesNotProvideObsoleteV2Authority() {
        assertFalse(AgentCommandV2RuntimeAuthority.class.isAssignableFrom(
                AgentRuntimeAuthenticationService.class));
        assertTrue(Arrays.stream(AgentRuntimeAuthenticationService.class.getMethods())
                .noneMatch(method -> method.getName().equals("bindV2")
                        || method.getName().equals("authorizeAndDispatch")));
        assertTrue(Arrays.stream(AgentRuntimeAuthenticationService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .noneMatch(type -> type == ApiKeyService.class));
    }

    @Test void oldRegistrationTokenAndApiKeyCannotAuthenticateInstallationSession() {
        for (String obsolete : new String[] {"1".repeat(32), "server-api-key-secret"}) {
            var headers = new AgentRuntimeAuthenticationFilter.SessionHeaders("agent-1",
                    "rti_" + "a".repeat(32), "host", "boot", 1, obsolete);
            assertThrows(IllegalArgumentException.class,
                    () -> authentication.authenticate(headers, false));
        }
        verifyNoInteractions(runtimes, installations, registry, identities, accounts);
    }

    @Test void validTokenSyntaxWithoutPersistedInstallationCannotCreateAuthority() {
        String installation = "rti_" + "a".repeat(32);
        var headers = new AgentRuntimeAuthenticationFilter.SessionHeaders("agent-1",
                installation, "host", "boot", 1, "rts1_" + "a".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> authentication.authenticate(headers, false));
        verify(installations).lock(installation);
        verifyNoInteractions(runtimes, registry, identities, accounts);
    }

    @Test void absentChannelAndMissingProofCannotCreateRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> authentication.currentRegisteredProof("1", "client-a", "agent-1"));
        assertThrows(IllegalArgumentException.class,
                () -> authentication.bind("socket", null, () -> true));
        verifyNoInteractions(runtimes, installations, registry, identities, accounts);
    }
}
