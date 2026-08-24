package cn.jia.user.config;

import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.CustomUserDetails;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultSecurityConfigTest extends BaseMockTest {
    @Mock UserService userService;
    @Mock PermsService permsService;
    @Mock AccountSecurityService accountSecurityService;

    DefaultSecurityConfig config;
    UserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        config = new DefaultSecurityConfig();
        ReflectionTestUtils.setField(config, "userService", userService);
        ReflectionTestUtils.setField(config, "permsService", permsService);
        ReflectionTestUtils.setField(config, "accountSecurityService", accountSecurityService);
        userDetailsService = config.userDetailsService();
    }

    @Test
    void freezesStableIdentityAndEpochForActiveUser() {
        UserEntity user = new UserEntity().setId(17L).setUsername("alice").setPassword("encoded").setJiacn("Jia-A");
        when(userService.findByUsername("alice")).thenReturn(user);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 9)));
        when(permsService.findByUserId(17L)).thenReturn(List.of());

        CustomUserDetails details = (CustomUserDetails) userDetailsService.loadUserByUsername("alice");
        assertEquals(17, details.getUserId());
        assertEquals("Jia-A", details.getJiacn());
        assertEquals(9, details.getLoginAuthEpoch());
        assertEquals("alice", details.getUsername());
    }

    @Test
    void missingInactiveOrMismatchedIdentityUsesSameAuthenticationFailure() {
        when(userService.findByUsername("missing")).thenReturn(null);
        assertGenericFailure("missing");

        UserEntity suspended = new UserEntity().setId(18L).setUsername("suspended").setJiacn("Jia-B");
        when(userService.findByUsername("suspended")).thenReturn(suspended);
        when(accountSecurityService.findByUserId(18)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(18, "Jia-B", AccountState.SUSPENDED, 2)));
        assertGenericFailure("suspended");

        UserEntity mismatch = new UserEntity().setId(19L).setUsername("mismatch").setJiacn("Jia-C");
        when(userService.findByUsername("mismatch")).thenReturn(mismatch);
        when(accountSecurityService.findByUserId(19)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(19, "jia-c", AccountState.ACTIVE, 0)));
        assertGenericFailure("mismatch");
        verify(permsService, never()).findByUserId(anyLong());
    }

    private void assertGenericFailure(String username) {
        UsernameNotFoundException failure = assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername(username));
        assertEquals("Authentication failed", failure.getMessage());
    }
}
