package cn.jia.user.config;

import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.CustomUserDetails;
import cn.jia.user.entity.UserEntity;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultSecurityConfigTest extends BaseMockTest {
    @Mock UserService userService;
    @Mock PermsService permsService;

    DefaultSecurityConfig config;
    UserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        config = new DefaultSecurityConfig();
        ReflectionTestUtils.setField(config, "userService", userService);
        ReflectionTestUtils.setField(config, "permsService", permsService);
        userDetailsService = config.userDetailsService();
    }

    @Test
    void freezesStableIdentityAndEpochForActiveUser() {
        UserEntity user = activeUser(17L, "alice", "Jia-A", 9L);
        when(userService.findByUsername("alice")).thenReturn(user);
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

        UserEntity suspended = activeUser(18L, "suspended", "Jia-B", 2L)
                .setAccountState(AccountState.SUSPENDED.name());
        when(userService.findByUsername("suspended")).thenReturn(suspended);
        assertGenericFailure("suspended");

        UserEntity invalidEpoch = activeUser(19L, "invalid-epoch", "Jia-C", -1L);
        when(userService.findByUsername("invalid-epoch")).thenReturn(invalidEpoch);
        assertGenericFailure("invalid-epoch");
        verify(permsService, never()).findByUserId(anyLong());
    }

    private static UserEntity activeUser(long id, String username, String jiacn, long authEpoch) {
        return new UserEntity()
                .setId(id)
                .setUsername(username)
                .setPassword("encoded")
                .setJiacn(jiacn)
                .setAccountState(AccountState.ACTIVE.name())
                .setAuthEpoch(authEpoch);
    }

    private void assertGenericFailure(String username) {
        UsernameNotFoundException failure = assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername(username));
        assertEquals("Authentication failed", failure.getMessage());
    }
}
