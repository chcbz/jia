package cn.jia.user.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.test.BaseMockTest;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.dao.UserOrgDao;
import cn.jia.user.dao.UserOrgRelDao;
import cn.jia.user.dao.UserRoleDao;
import cn.jia.user.entity.OrgEntity;
import cn.jia.user.entity.OrgRelEntity;
import cn.jia.user.entity.OrgVO;
import cn.jia.user.entity.RoleEntity;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OrgServiceImplTest extends BaseMockTest {
    @Mock
    UserOrgRelDao userOrgRelDao;
    @Mock
    UserRoleDao userRoleDao;
    @Mock
    UserOrgDao userOrgDao;
    @InjectMocks
    OrgServiceImpl orgServiceImpl;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orgServiceImpl, "baseDao", userOrgDao);
    }

    @Test
    void testList() {
        OrgEntity orgEntity = new OrgEntity();
        when(userOrgDao.selectAll()).thenReturn(List.of(orgEntity));
        PageInfo<OrgEntity> result = orgServiceImpl.list(1, 10, null);
        assertEquals(orgEntity, result.getList().get(0));
    }

    @Test
    void testListSub() {
        OrgEntity orgEntity = new OrgEntity();
        when(userOrgDao.selectByParentId(anyLong())).thenReturn(List.of(orgEntity));
        PageInfo<OrgEntity> result = orgServiceImpl.listSub(1L, 1, 10, null);
        assertEquals(orgEntity, result.getList().get(0));

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(userOrgDao).selectByParentId(captor.capture());
        assertEquals(1L, captor.getValue());
    }

    @Test
    void testFindByUserId() {
        OrgEntity orgEntity = new OrgEntity();
        when(userOrgDao.selectByUserId(anyLong())).thenReturn(List.of(orgEntity));
        List<OrgEntity> result = orgServiceImpl.findByUserId(1L);
        assertEquals(orgEntity, result.get(0));

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(userOrgDao).selectByUserId(captor.capture());
        assertEquals(1L, captor.getValue());
    }

    @Test
    void testBatchAddUser() {
        List<OrgRelEntity> orgRelList = new ArrayList<>();
        orgRelList.add(new OrgRelEntity().setUserId(1L));
        orgRelList.add(new OrgRelEntity().setUserId(3L));
        when(userOrgRelDao.selectByOrgId(anyLong())).thenReturn(orgRelList);
        when(userOrgRelDao.insertBatch(any())).thenReturn(true);

        OrgVO orgVO = new OrgVO();
        orgVO.setId(1L);
        orgVO.setUserIds(List.of(1L, 2L));
        orgServiceImpl.batchAddUser(orgVO);

        ArgumentCaptor<List<OrgRelEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userOrgRelDao, times(1)).insertBatch(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(2L, captor.getValue().get(0).getUserId());
    }

    @Test
    void testBatchDelUser() {
        List<OrgRelEntity> orgRelList = new ArrayList<>();
        orgRelList.add(new OrgRelEntity().setUserId(1L));
        orgRelList.add(new OrgRelEntity().setUserId(3L));
        when(userOrgRelDao.selectByOrgId(anyLong())).thenReturn(orgRelList);
        when(userOrgRelDao.deleteBatchIds(any())).thenReturn(1);

        OrgVO orgVO = new OrgVO();
        orgVO.setId(1L);
        orgVO.setUserIds(List.of(1L, 2L));
        orgServiceImpl.batchDelUser(orgVO);

        ArgumentCaptor<List<OrgRelEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(userOrgRelDao, times(1)).deleteBatchIds(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(1L, captor.getValue().get(0).getUserId());
    }

    @Test
    void testFindDirector_RoleNull() {
        when(userRoleDao.selectByCode(anyString())).thenReturn(null);

        EsRuntimeException thrown = assertThrows(EsRuntimeException.class, () ->
                orgServiceImpl.findDirector(1L, "role"));
        assertEquals(UserErrorConstants.ROLE_NOT_EXIST.getCode(), thrown.getMessageKey());
    }

    @Test
    void testFindDirector() {
        RoleEntity roleEntity = new RoleEntity();
        roleEntity.setId(1L);
        when(userRoleDao.selectByCode(anyString())).thenReturn(roleEntity);
        when(userOrgDao.findDirector(anyLong(), anyLong())).thenReturn("director");

        String result = orgServiceImpl.findDirector(1L, "role");
        assertEquals("director", result);
    }

    @Test
    void testFindParent() {
        OrgEntity orgEntity = new OrgEntity();
        orgEntity.setPId(1L);
        orgEntity.setId(2L);
        when(userOrgDao.selectById(same(2L))).thenReturn(orgEntity);

        OrgEntity parent = new OrgEntity();
        parent.setId(1L);
        when(userOrgDao.selectById(same(1L))).thenReturn(parent);

        OrgEntity result = orgServiceImpl.findParent(2L);
        assertEquals(parent, result);
    }
}
