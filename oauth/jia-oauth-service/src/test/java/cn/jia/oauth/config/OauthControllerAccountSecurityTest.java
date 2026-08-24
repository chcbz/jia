package cn.jia.oauth.config;

import cn.jia.core.redis.ThirdPartyLoginTransactionService;
import cn.jia.oauth.api.OauthController;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OauthControllerAccountSecurityTest extends BaseMockTest {
    @Mock ClientService clientService;
    @Mock UserService userService;
    @Mock AccountSecurityService accountSecurityService;
    @Mock PermsService permsService;
    @Mock RestTemplate restTemplate;
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

    private String complete(UserEntity user) {
        OauthController controller = new OauthController(clientService, userService, accountSecurityService,
                permsService, restTemplate, transactions);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ThirdPartyLoginTransactionService.Transaction transaction =
                new ThirdPartyLoginTransactionService.Transaction("github",
                        "https://client.example/oauth2/authorize?client_id=web");
        return ReflectionTestUtils.invokeMethod(controller, "completeThirdPartyLogin",
                user, transaction, null, request);
    }
}
