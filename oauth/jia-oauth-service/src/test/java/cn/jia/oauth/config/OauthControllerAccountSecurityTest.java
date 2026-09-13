package cn.jia.oauth.config;

import cn.jia.core.redis.ThirdPartyLoginTransactionService;
import cn.jia.oauth.api.OauthController;
import cn.jia.oauth.config.OauthExternalHttpClient;
import cn.jia.oauth.service.ClientService;
import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.CustomUserDetails;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OauthControllerAccountSecurityTest extends BaseMockTest {
    @Mock ClientService clientService;
    @Mock UserService userService;
    @Mock AccountSecurityService accountSecurityService;
    @Mock PermsService permsService;
    @Mock OauthExternalHttpClient externalHttpClient;
    @Mock ThirdPartyLoginTransactionService transactions;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void thirdPartyLoginRereadsAccountImmediatelyBeforeCreatingAuthentication() {
        UserEntity callbackUser = new UserEntity().setGithubid("42").setUsername("alice");
        UserEntity persisted = new UserEntity().setId(17L).setGithubid("42").setUsername("alice").setJiacn("Jia-A");
        when(userService.upsert(callbackUser)).thenReturn(persisted);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 6)));
        when(permsService.findByUserId(17L)).thenReturn(List.of());

        String result = complete(callbackUser);

        assertEquals("redirect:https://client.example/oauth2/authorize?client_id=web", result);
        assertInstanceOf(CustomUserDetails.class,
                SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        assertEquals(17, principal.getUserId());
        assertEquals("Jia-A", principal.getJiacn());
        assertEquals(6, principal.getLoginAuthEpoch());
        verify(accountSecurityService).findByUserId(17);
    }

    @Test
    void providerFailureIsNotReportedAsExpiredAndDoesNotWriteLocally() {
        ThirdPartyLoginTransactionService.Transaction transaction =
                new ThirdPartyLoginTransactionService.Transaction("github",
                        "https://client.example/oauth2/authorize?client_id=web");
        when(transactions.consume("github", "state")).thenReturn(transaction);
        when(externalHttpClient.postForEntity(anyString(), any(HttpEntity.class)))
                .thenThrow(new OauthExternalHttpClient.OauthExternalCallRejectedException("safe"));
        OauthController controller = controller();
        ReflectionTestUtils.setField(controller, "githubAppId", "app");
        ReflectionTestUtils.setField(controller, "githubSecret", "secret");

        assertEquals("redirect:/login/index.html?thirdPartyLoginError=provider",
                controller.thirdPartyGithub("code", "state", new MockHttpServletRequest(), new MockHttpServletResponse()));

        verifyNoInteractions(userService, accountSecurityService, permsService);
    }

    @Test
    void completedDuplicateCallbackDoesNotCallTheProviderAgain() {
        ThirdPartyLoginTransactionService.Transaction completed =
                new ThirdPartyLoginTransactionService.Transaction("github",
                        "https://client.example/oauth2/authorize?client_id=web");
        when(transactions.consume("github", "state")).thenReturn(null);
        when(transactions.findCompleted("github", "state")).thenReturn(completed);

        assertEquals("redirect:https://client.example/oauth2/authorize?client_id=web",
                controller().thirdPartyGithub("replayed-code", "state", new MockHttpServletRequest(),
                        new MockHttpServletResponse()));

        verifyNoInteractions(externalHttpClient, userService, accountSecurityService, permsService);
    }

    @Test
    void completedMarkerFailureDoesNotTurnAnEstablishedLoginIntoUnknownFailure() {
        UserEntity callbackUser = new UserEntity().setGithubid("42").setUsername("alice");
        UserEntity persisted = new UserEntity().setId(17L).setGithubid("42").setUsername("alice").setJiacn("Jia-A");
        when(userService.upsert(callbackUser)).thenReturn(persisted);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 6)));
        when(permsService.findByUserId(17L)).thenReturn(List.of());
        doThrow(new IllegalStateException("redis unavailable")).when(transactions)
                .markCompleted(eq("state"), any());
        ThirdPartyLoginTransactionService.Transaction transaction =
                new ThirdPartyLoginTransactionService.Transaction("github",
                        "https://client.example/oauth2/authorize?client_id=web");
        MockHttpServletRequest request = new MockHttpServletRequest();

        String result = ReflectionTestUtils.invokeMethod(controller(), "completeThirdPartyLogin",
                callbackUser, transaction, "state", request, new MockHttpServletResponse());

        assertEquals("redirect:https://client.example/oauth2/authorize?client_id=web", result);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void thirdPartyAuthenticationIsRecoverableFromTheRedirectSession() {
        UserEntity callbackUser = new UserEntity().setGithubid("42").setUsername("alice");
        UserEntity persisted = new UserEntity().setId(17L).setGithubid("42").setUsername("alice").setJiacn("Jia-A");
        when(userService.upsert(callbackUser)).thenReturn(persisted);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 6)));
        when(permsService.findByUserId(17L)).thenReturn(List.of());
        MockHttpServletRequest callback = new MockHttpServletRequest();

        ReflectionTestUtils.invokeMethod(controller(), "completeThirdPartyLogin", callbackUser,
                new ThirdPartyLoginTransactionService.Transaction("github",
                        "https://client.example/oauth2/authorize?client_id=web"),
                null, callback, new MockHttpServletResponse());
        SecurityContextHolder.clearContext();

        MockHttpServletRequest authorize = new MockHttpServletRequest();
        authorize.setSession((MockHttpSession) callback.getSession(false));
        var restored = new HttpSessionSecurityContextRepository().loadDeferredContext(authorize).get();
        assertNotNull(restored.getAuthentication());
        assertEquals("alice", restored.getAuthentication().getName());
    }

    @Test
    void staleOrInactiveCallbackObjectCannotCreateAuthentication() {
        UserEntity callbackUser = new UserEntity().setGithubid("42").setUsername("alice");
        UserEntity persisted = new UserEntity().setId(17L).setGithubid("42").setUsername("alice").setJiacn("Jia-A");
        when(userService.upsert(callbackUser)).thenReturn(persisted);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.DISABLED, 7)));

        assertEquals("redirect:/login/index.html?thirdPartyLoginError=1", complete(callbackUser));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(permsService);
    }

    @Test
    void invalidStateDoesNotCallProviderOrCreateAuthentication() {
        assertEquals("redirect:/login/index.html?thirdPartyLoginError=expired",
                controller().thirdPartyGithub("code", "invalid", new MockHttpServletRequest(),
                        new MockHttpServletResponse()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(externalHttpClient, userService, accountSecurityService, permsService);
    }

    private OauthController controller() {
        return new OauthController(clientService, userService, accountSecurityService,
                permsService, externalHttpClient, transactions);
    }

    private String complete(UserEntity user) {
        OauthController controller = controller();
        MockHttpServletRequest request = new MockHttpServletRequest();
        ThirdPartyLoginTransactionService.Transaction transaction =
                new ThirdPartyLoginTransactionService.Transaction("github",
                        "https://client.example/oauth2/authorize?client_id=web");
        return ReflectionTestUtils.invokeMethod(controller, "completeThirdPartyLogin",
                user, transaction, null, request, new MockHttpServletResponse());
    }
}
