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
        var user = new UserEntity();
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
        verify(service, never()).findOwnIdentityByExactJiacn(anyString());
    }
}
