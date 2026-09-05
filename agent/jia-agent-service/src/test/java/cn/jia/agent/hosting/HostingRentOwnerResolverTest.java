package cn.jia.agent.hosting;

import cn.jia.user.entity.UserEntity;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingRentOwnerResolverTest {
    @Test
    void exactSubMapsThroughUniqueActiveAccountWithoutReplacingPayerWithTenant() {
        var accounts = mock(AccountSecurityService.class);
        var users = mock(UserService.class);
        var snapshot = new AccountSecuritySnapshot(17L, "Jia-A", AccountState.ACTIVE, 3L);
        when(accounts.findUniqueByExactJiacn("Jia-A")).thenReturn(Optional.of(snapshot));
        when(accounts.findByUserId(17)).thenReturn(Optional.of(snapshot));
        when(users.get(17L)).thenReturn(new UserEntity().setId(17L).setUsername("Login-A").setJiacn("Jia-A"));
        org.springframework.beans.factory.ObjectProvider<AccountSecurityService> accountProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        org.springframework.beans.factory.ObjectProvider<UserService> userProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(accountProvider.getIfAvailable()).thenReturn(accounts);
        when(userProvider.getIfAvailable()).thenReturn(users);
        var resolver = new HostingRentOwnerResolver(accountProvider, userProvider);
        var actor = new HostingRentHttp.Actor("Login-A", "Jia-A", "Client-A");
        assertEquals("Jia-A", resolver.requireOwner(actor));
        assertEquals("Login-A", actor.principal().id());
        for (String impostor : new String[]{"login-a", "Jia-A", "other-user"}) {
            assertThrows(HostingRentApplicationException.class,
                    () -> resolver.requireOwner(new HostingRentHttp.Actor(impostor, "Jia-A", "Client-A")));
        }
        when(accounts.findUniqueByExactJiacn("Jia-A")).thenReturn(Optional.empty());
        assertThrows(HostingRentApplicationException.class, () -> resolver.requireOwner(actor));
    }

    @Test
    void onlyExistingLoginFormsAreAcceptedNotArbitraryPersistedAliases() {
        var row = new UserEntity().setUsername("Login-A").setJiacn("Jia-A").setOpenid("Open-A").setPhone("123");
        assertTrue(HostingRentOwnerResolver.matchesLoginSubject("wx-Open-A", row));
        assertTrue(HostingRentOwnerResolver.matchesLoginSubject("mb-123", row));
        assertFalse(HostingRentOwnerResolver.matchesLoginSubject("Open-A", row));
        assertFalse(HostingRentOwnerResolver.matchesLoginSubject("Jia-A", row));
        row.setUsername(null);
        assertTrue(HostingRentOwnerResolver.matchesLoginSubject("Jia-A", row));
        assertFalse(HostingRentOwnerResolver.matchesLoginSubject("Open-A", row));
    }
}
