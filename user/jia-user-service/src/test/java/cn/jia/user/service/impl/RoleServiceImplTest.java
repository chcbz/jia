package cn.jia.user.service.impl;

import cn.jia.test.BaseMockTest;
import cn.jia.user.dao.UserPermsRelDao;
import cn.jia.user.dao.UserRoleDao;
import cn.jia.user.dao.UserRoleRelDao;
import cn.jia.user.entity.PermsRelEntity;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.entity.RoleRelEntity;
import cn.jia.user.entity.RoleVO;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

class RoleServiceImplTest extends BaseMockTest {
    @Mock
    UserPermsRelDao userPermsRelDao;
    @Mock
    UserRoleRelDao userRoleRelDao;
    @Mock
    UserRoleDao userRoleDao;
    @InjectMocks
    RoleServiceImpl roleServiceImpl;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(roleServiceImpl, "baseDao", userRoleDao);
    }

    @Test
    void testChangePerms() {
        PermsRelEntity permsRelEntity = new PermsRelEntity().setPermsId(1L);
        PermsRelEntity permsRelEntity2 = new PermsRelEntity().setPermsId(3L);
        when(userPermsRelDao.selectByRoleId(anyLong())).thenReturn(List.of(permsRelEntity, permsRelEntity2));
        when(userPermsRelDao.insertBatch(any())).thenReturn(true);
        when(userPermsRelDao.deleteBatchIds(any())).thenReturn(1);

        RoleVO roleVO = new RoleVO().setPermsIds(List.of(1L, 2L));
        roleVO.setId(1L);
        roleServiceImpl.changePerms(roleVO);
    }

    @Test
    void testListByUserId() {
        RoleEntity roleEntity = new RoleEntity();
        when(userRoleDao.selectByUserId(anyLong())).thenReturn(List.of(roleEntity));
        PageInfo<RoleEntity> result = roleServiceImpl.listByUserId(1L, 10, 1);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(roleEntity, result.getList().get(0));
    }

    @Test
    void testListByGroupId() {
        RoleEntity roleEntity = new RoleEntity();
        when(userRoleDao.selectByGroupId(anyLong())).thenReturn(List.of(roleEntity));
        PageInfo<RoleEntity> result = roleServiceImpl.listByGroupId(1L, 10, 1);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(roleEntity, result.getList().get(0));
    }

    @Test
    void testBatchAddUser() {
        when(userRoleRelDao.selectByRoleId(anyLong())).thenReturn(List.of(new RoleRelEntity()));
        when(userRoleRelDao.insertBatch(any())).thenReturn(true);

        RoleVO roleVO = new RoleVO();
        roleVO.setId(1L);
        roleVO.setUserIds(List.of(1L));
        roleVO.setGroupIds(List.of(1L));
        roleServiceImpl.batchAddUser(roleVO);
    }

    @Test
    void testBatchDelUser() {
        List<RoleRelEntity> roleRelList = new ArrayList<>();
        roleRelList.add(new RoleRelEntity().setUserId(1L));
        roleRelList.add(new RoleRelEntity().setGroupId(1L));
        when(userRoleRelDao.selectByRoleId(anyLong())).thenReturn(roleRelList);
        when(userRoleRelDao.deleteBatchIds(any())).thenReturn(1);

        RoleVO roleVO = new RoleVO();
        roleVO.setId(1L);
        roleVO.setUserIds(List.of(1L));
        roleVO.setGroupIds(List.of(1L));
        roleServiceImpl.batchDelUser(roleVO);
    }

    @Test
    void testListPerms() {
        PermsRelEntity permsRelEntity = new PermsRelEntity();
        when(userPermsRelDao.selectByRoleId(anyLong())).thenReturn(List.of(permsRelEntity));

        PageInfo<PermsRelEntity> result = roleServiceImpl.listPerms(1L, 10, 1);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(permsRelEntity, result.getList().get(0));
    }
}
