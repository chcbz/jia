package cn.jia.user.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLegacyIdentityCompatibilityTest {
    @AfterEach void clear() { EsContextHolder.clearContext(); }
    @Test void tenantZeroKeepsExistingFieldsAndPasswordMask() {
        var service = mock(UserService.class);
        var controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", service);
        var accounts = mock(cn.jia.point.service.PointAccountService.class);
        ReflectionTestUtils.setField(controller, "pointAccounts", accounts);
        when(accounts.balance("owner")).thenReturn(17);
        when(accounts.tenantId()).thenReturn("0");
        var user = new UserEntity();
        user.setPoint(999);
        user.setJiacn("owner"); user.setPhone("15500000000");
        user.setEmail("own@example.test"); user.setPassword("never-return");
        when(service.findByJiacn("owner")).thenReturn(user);
        EsContextHolder.getContext().setTenantId("0");
        EsContextHolder.getContext().setClientId("web-client");
        EsContextHolder.getContext().setJiacn("owner");
        var result = assertInstanceOf(JsonResult.class, controller.userInfo());
        var data = assertInstanceOf(UserEntity.class, result.getData());
        assertEquals("15500000000", data.getPhone());
        assertEquals("own@example.test", data.getEmail());
        assertEquals("***", data.getPassword());
        assertEquals(17, data.getPoint());
        assertEquals("0", data.getTenantId());
        assertEquals("web-client", data.getClientId());
        verify(service, never()).findOwnIdentityByExactJiacn(anyString());
    }
    @Test void historicalProfileWithoutFinancialIdentityHasZeroProjectionWithoutAccountAccess() {
        var controller = new UserController();
        var accounts = mock(cn.jia.point.service.PointAccountService.class);
        ReflectionTestUtils.setField(controller, "pointAccounts", accounts);
        for (String identity : new String[] {null, ""}) {
            var user = new UserEntity();
            user.setJiacn(identity);
            assertEquals(0, (Integer) ReflectionTestUtils.invokeMethod(controller, "projectBalance", user));
        }
        verifyNoInteractions(accounts);
    }

    @Test void historicalIdentitylessRowsStayInUserListsWithZeroProjection() {
        var controller = new UserController();
        var accounts = mock(cn.jia.point.service.PointAccountService.class);
        ReflectionTestUtils.setField(controller, "pointAccounts", accounts);
        var missing = new UserEntity();
        var empty = new UserEntity(); empty.setJiacn("");
        var owner = new UserEntity(); owner.setJiacn("owner");
        when(accounts.balances(java.util.List.of("owner"))).thenReturn(java.util.Map.of("owner", 17));
        var users = java.util.List.of(missing, empty, owner);
        ReflectionTestUtils.invokeMethod(controller, "projectBalances", users);
        assertEquals(0, missing.getPoint()); assertEquals(0, empty.getPoint()); assertEquals(17, owner.getPoint());
        verify(accounts).balances(java.util.List.of("owner"));
        verifyNoMoreInteractions(accounts);
    }

}
