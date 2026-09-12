package cn.jia.user.api;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserRelationIds;
import cn.jia.user.entity.UserVO;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

class UserControllerListTest {
    @Mock
    private UserService userService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        openMocks(this);
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
    }

    @Test
    void listKeepsAclPageOrderAndResponseShapeWithOneRelationBatch() throws Exception {
        UserVO search = new UserVO();
        search.setTenantId("Tenant-A");
        search.setClientId("Client-A");
        JsonRequestPage<UserVO> request = new JsonRequestPage<>();
        request.setSearch(search);
        request.setPageNum(3);
        request.setPageSize(25);
        request.setOrderBy("create_time desc");

        UserEntity first = new UserEntity().setId(10L).setPassword("secret-a");
        first.setTenantId("Tenant-A");
        first.setClientId("Client-A");
        UserEntity second = new UserEntity().setId(20L).setPassword("secret-b");
        second.setTenantId("Tenant-A");
        second.setClientId("Client-A");
        PageInfo<UserEntity> page = new PageInfo<>();
        page.setList(List.of(first, second));
        page.setPageNum(3);
        page.setTotal(42L);
        when(userService.findListPage(search, 3, 25, "create_time desc")).thenReturn(page);
        when(userService.findRelationIds(List.of(10L, 20L))).thenReturn(Map.of(
                10L, new UserRelationIds(List.of(1L), List.of(2L), List.of(3L)),
                20L, new UserRelationIds(List.of(), List.of(), List.of())));

        @SuppressWarnings("unchecked")
        JsonResultPage<UserVO> result = (JsonResultPage<UserVO>) controller.list(request);

        assertEquals(3, result.getPageNum());
        assertEquals(42L, result.getTotal());
        assertEquals(List.of(10L, 20L), result.getData().stream().map(UserVO::getId).toList());
        assertEquals("******", result.getData().getFirst().getPassword());
        assertEquals(List.of(1L), result.getData().getFirst().getRoleIds());
        assertEquals(List.of(2L), result.getData().getFirst().getOrgIds());
        assertEquals(List.of(3L), result.getData().getFirst().getGroupIds());
        assertEquals("Tenant-A", result.getData().getFirst().getTenantId());
        assertEquals("Client-A", result.getData().getFirst().getClientId());
        verify(userService).findRelationIds(List.of(10L, 20L));
        verify(userService, never()).findRoleIds(anyLong());
        verify(userService, never()).findOrgIds(anyLong());
        verify(userService, never()).findGroupIds(anyLong());

        Method list = UserController.class.getDeclaredMethod("list", JsonRequestPage.class);
        PreAuthorize acl = list.getAnnotation(PreAuthorize.class);
        assertNotNull(acl);
        assertEquals("hasAuthority('user-list')", acl.value());
    }
}
