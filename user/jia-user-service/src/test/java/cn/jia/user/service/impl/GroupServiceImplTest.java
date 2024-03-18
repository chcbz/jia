package cn.jia.user.service.impl;

import cn.jia.test.BaseMockTest;
import cn.jia.user.dao.UserGroupDao;
import cn.jia.user.dao.UserGroupRelDao;
import cn.jia.user.dao.UserRoleRelDao;
import cn.jia.user.entity.GroupEntity;
import cn.jia.user.entity.GroupRelEntity;
import cn.jia.user.entity.GroupVO;
import cn.jia.user.entity.RoleRelEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

class GroupServiceImplTest extends BaseMockTest {
    @Mock
    UserGroupDao userGroupDao;
    @Mock
    UserGroupRelDao userGroupRelDao;
    @Mock
    UserRoleRelDao userRoleRelDao;
    @InjectMocks
    GroupServiceImpl groupServiceImpl;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(groupServiceImpl, "baseDao", userGroupDao);
    }

    @Test
    void testFindByUserId() {
        GroupEntity groupEntity = new GroupEntity();
        when(userGroupDao.selectByUserId(anyLong())).thenReturn(List.of(groupEntity));

        List<GroupEntity> result = groupServiceImpl.findByUserId(1L);
        Assertions.assertEquals(groupEntity, result.get(0));
    }

    @Test
    void testBatchAddUser() {
        List<GroupRelEntity> groupRelList = new ArrayList<>();
        groupRelList.add(new GroupRelEntity().setUserId(1L));
        groupRelList.add(new GroupRelEntity().setUserId(3L));
        when(userGroupRelDao.selectByGroupId(anyLong())).thenReturn(groupRelList);
        when(userGroupRelDao.insertBatch(any())).thenReturn(true);

        GroupVO groupVO = new GroupVO();
        groupVO.setId(1L);
        groupVO.setUserIds(List.of(1L, 2L));
        groupServiceImpl.batchAddUser(groupVO);

        ArgumentCaptor<List<GroupRelEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userGroupRelDao, times(1)).insertBatch(captor.capture());
        Assertions.assertEquals(1, captor.getValue().size());
        Assertions.assertEquals(2L, captor.getValue().get(0).getUserId());
    }

    @Test
    void testBatchDelUser() {
        List<GroupRelEntity> groupRelList = new ArrayList<>();
        groupRelList.add(new GroupRelEntity().setUserId(1L));
        groupRelList.add(new GroupRelEntity().setUserId(3L));
        when(userGroupRelDao.selectByGroupId(anyLong())).thenReturn(groupRelList);
        when(userGroupRelDao.deleteBatchIds(any())).thenReturn(1);

        GroupVO groupVO = new GroupVO();
        groupVO.setId(1L);
        groupVO.setUserIds(List.of(1L, 2L));
        groupServiceImpl.batchDelUser(groupVO);

        ArgumentCaptor<List<GroupRelEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userGroupRelDao, times(1)).deleteBatchIds(captor.capture());
        Assertions.assertEquals(1, captor.getValue().size());
        Assertions.assertEquals(1L, captor.getValue().get(0).getUserId());
    }

    @Test
    void testChangeRole() {
        List<RoleRelEntity> roleRelList = new ArrayList<>();
        roleRelList.add(new RoleRelEntity().setRoleId(1L));
        roleRelList.add(new RoleRelEntity().setRoleId(3L));
        when(userRoleRelDao.selectByGroupId(anyLong())).thenReturn(roleRelList);
        when(userRoleRelDao.insertBatch(any())).thenReturn(true);
        when(userRoleRelDao.deleteBatchIds(any())).thenReturn(1);

        GroupVO groupVO = new GroupVO();
        groupVO.setId(1L);
        groupVO.setRoleIds(List.of(1L, 2L));
        groupServiceImpl.changeRole(groupVO);

        ArgumentCaptor<List<RoleRelEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRelDao, times(1)).insertBatch(captor.capture());
        Assertions.assertEquals(1, captor.getValue().size());
        Assertions.assertEquals(2L, captor.getValue().get(0).getRoleId());

        captor = ArgumentCaptor.forClass(List.class);
        verify(userRoleRelDao, times(1)).deleteBatchIds(captor.capture());
        Assertions.assertEquals(1, captor.getValue().size());
        Assertions.assertEquals(3L, captor.getValue().get(0).getRoleId());
    }

    @Test
    void testFindRoleIds() {
        RoleRelEntity roleRelEntity = new RoleRelEntity().setRoleId(1L);
        when(userRoleRelDao.selectByGroupId(anyLong())).thenReturn(List.of(roleRelEntity));

        List<Long> result = groupServiceImpl.findRoleIds(1L);
        Assertions.assertEquals(List.of(1L), result);
    }
}