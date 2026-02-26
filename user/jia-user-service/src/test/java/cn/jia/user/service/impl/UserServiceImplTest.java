package cn.jia.user.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.PasswordUtil;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.service.LdapUserGroupService;
import cn.jia.isp.service.LdapUserService;
import cn.jia.test.BaseMockTest;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.dao.UserGroupRelDao;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.dao.UserOrgRelDao;
import cn.jia.user.dao.UserRoleRelDao;
import cn.jia.user.entity.*;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UserServiceImplTest extends BaseMockTest {
    @Mock
    UserRoleRelDao userRoleRelDao;
    @Mock
    UserOrgRelDao userOrgRelDao;
    @Mock
    UserGroupRelDao userGroupRelDao;
    @Mock
    LdapUserService ldapUserService;
    @Mock
    LdapUserGroupService ldapUserGroupService;
    @Mock
    UserInfoDao userInfoDao;
    @InjectMocks
    UserServiceImpl userServiceImpl;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userServiceImpl, "baseDao", userInfoDao);
    }

    @Test
    void testCreate() {
        when(ldapUserService.create(any())).thenReturn(new LdapUser("uid"));
        when(ldapUserService.search(any())).thenReturn(Collections.emptyList());
        when(userInfoDao.insert(any())).thenReturn(1);
        when(userInfoDao.searchByExample(any())).thenReturn(Collections.emptyList());
        when(userRoleRelDao.insert(any())).thenReturn(1);

        UserEntity userEntity = new UserEntity();
        userEntity.setJiacn("jiacn");
        userEntity.setOpenid("openid");
        UserEntity result = userServiceImpl.create(userEntity);
        Assertions.assertEquals(userEntity, result);
    }

    @Test
    void testChangePoint() {
        UserEntity userEntity = new UserEntity();
        userEntity.setPoint(1);
        when(userInfoDao.selectByJiacn(any())).thenReturn(userEntity);
        when(userInfoDao.updateById(any())).thenReturn(1);

        userServiceImpl.changePoint("jiacn", 1);

        EsRuntimeException exception = assertThrows(EsRuntimeException.class, () ->
                userServiceImpl.changePoint("jiacn", -2));
        Assertions.assertEquals(UserErrorConstants.POINT_NO_ENOUGH.getMessage(), exception.getMessage());
    }

    @Test
    void testChangeRole() {
        RoleRelEntity roleRelEntity = new RoleRelEntity().setRoleId(1L);
        RoleRelEntity roleRelEntity2 = new RoleRelEntity().setRoleId(3L);
        when(userRoleRelDao.selectByUserId(anyLong())).thenReturn(List.of(roleRelEntity, roleRelEntity2));
        when(userRoleRelDao.insertBatch(any())).thenReturn(true);
        when(userRoleRelDao.deleteBatchIds(any())).thenReturn(1);

        UserVO userVO = new UserVO();
        userVO.setId(1L);
        userVO.setRoleIds(List.of(1L, 2L));
        userServiceImpl.changeRole(userVO);
    }

    @Test
    void testChangeGroup() {
        GroupRelEntity groupRelEntity = new GroupRelEntity().setGroupId(1L);
        GroupRelEntity groupRelEntity2 = new GroupRelEntity().setGroupId(3L);
        when(userGroupRelDao.selectByUserId(anyLong())).thenReturn(List.of(groupRelEntity, groupRelEntity2));
        when(userGroupRelDao.insertBatch(any())).thenReturn(true);
        when(userGroupRelDao.deleteBatchIds(any())).thenReturn(1);

        UserVO userVO = new UserVO();
        userVO.setId(1L);
        userVO.setGroupIds(List.of(1L, 2L));
        userServiceImpl.changeGroup(userVO);
    }

    @Test
    void testChangeOrg() {
        OrgRelEntity orgRelEntity = new OrgRelEntity().setOrgId(1L);
        OrgRelEntity orgRelEntity2 = new OrgRelEntity().setOrgId(3L);
        when(userOrgRelDao.selectByUserId(anyLong())).thenReturn(List.of(orgRelEntity, orgRelEntity2));
        when(userOrgRelDao.insertBatch(any())).thenReturn(true);
        when(userOrgRelDao.deleteBatchIds(any())).thenReturn(1);

        UserVO userVO = new UserVO();
        userVO.setId(1L);
        userVO.setOrgIds(List.of(1L, 2L));
        userServiceImpl.changeOrg(userVO);
    }

    @Test
    void testListByRoleId() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.selectByRole(anyLong())).thenReturn(List.of(userEntity));
        PageInfo<UserEntity> result = userServiceImpl.listByRoleId(1L, 10, 1, null);
        Assertions.assertEquals(userEntity, result.getList().get(0));
    }

    @Test
    void testListByGroupId() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.selectByGroup(anyLong())).thenReturn(List.of(userEntity));
        PageInfo<UserEntity> result = userServiceImpl.listByGroupId(1L, 10, 1, null);
        Assertions.assertEquals(userEntity, result.getList().get(0));
    }

    @Test
    void testListByOrgId() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.selectByOrg(anyLong())).thenReturn(List.of(userEntity));
        PageInfo<UserEntity> result = userServiceImpl.listByOrgId(1L, 10, 1, null);
        Assertions.assertEquals(userEntity, result.getList().get(0));
    }

    @Test
    void testFindByJiacn() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.selectByJiacn(any())).thenReturn(userEntity);
        UserEntity result = userServiceImpl.findByJiacn("jiacn");
        Assertions.assertEquals(userEntity, result);
    }

    @Test
    void testFindByOpenid() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.selectByOpenid(any())).thenReturn(userEntity);
        UserEntity result = userServiceImpl.findByOpenid("openid");
        Assertions.assertEquals(userEntity, result);
    }

    @Test
    void testFindByPhone() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.selectByPhone(any())).thenReturn(userEntity);
        UserEntity result = userServiceImpl.findByPhone("phone");
        Assertions.assertEquals(userEntity, result);
    }

    @Test
    void testSearch() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.searchByExample(any())).thenReturn(List.of(userEntity));
        PageInfo<UserEntity> result = userServiceImpl.search(new UserEntity(), 10, 1, null);
        Assertions.assertEquals(userEntity, result.getList().get(0));
    }

    @Test
    void testSync() {
        when(ldapUserService.create(any())).thenReturn(new LdapUser("uid"));
        when(ldapUserService.search(any())).thenReturn(Collections.emptyList());
        when(ldapUserGroupService.findByClientId(any())).thenReturn(new LdapUserGroup());
        when(userInfoDao.insert(any())).thenReturn(1);
        when(userInfoDao.searchByExample(any())).thenReturn(Collections.emptyList());
        when(userRoleRelDao.insert(any())).thenReturn(1);

        UserEntity userEntity = new UserEntity();
        userEntity.setJiacn("jiacn");
        userEntity.setOpenid("openid");
        userServiceImpl.sync(Collections.singletonList(userEntity));
    }

    @Test
    void testFindRoleIds() {
        RoleRelEntity roleRelEntity = new RoleRelEntity();
        roleRelEntity.setRoleId(1L);
        when(userRoleRelDao.selectByUserId(anyLong())).thenReturn(List.of(roleRelEntity));

        List<Long> result = userServiceImpl.findRoleIds(1L);
        Assertions.assertEquals(List.of(1L), result);
    }

    @Test
    void testFindOrgIds() {
        OrgRelEntity orgRelEntity = new OrgRelEntity();
        orgRelEntity.setOrgId(1L);
        when(userOrgRelDao.selectByUserId(anyLong())).thenReturn(List.of(orgRelEntity));

        List<Long> result = userServiceImpl.findOrgIds(1L);
        Assertions.assertEquals(List.of(1L), result);
    }

    @Test
    void testFindGroupIds() {
        GroupRelEntity groupRelEntity = new GroupRelEntity();
        groupRelEntity.setGroupId(1L);
        when(userGroupRelDao.selectByUserId(anyLong())).thenReturn(List.of(groupRelEntity));

        List<Long> result = userServiceImpl.findGroupIds(1L);
        Assertions.assertEquals(List.of(1L), result);
    }

    @Test
    void testFindByUsername() {
        UserEntity userEntity = new UserEntity();
        when(userInfoDao.selectByUsername(any())).thenReturn(userEntity);
        UserEntity result = userServiceImpl.findByUsername("username");
        Assertions.assertEquals(userEntity, result);
    }

    @Test
    void testChangePassword() {
        UserEntity userEntity = new UserEntity();
        userEntity.setPassword(PasswordUtil.encode("password"));
        when(userInfoDao.selectById(any())).thenReturn(userEntity);
        when(userInfoDao.updateById(any())).thenReturn(1);

        userServiceImpl.changePassword(1L, "password", "newPassword");

        when(userInfoDao.selectById(any())).thenReturn(null);
        EsRuntimeException exception = assertThrows(EsRuntimeException.class, () ->
                userServiceImpl.changePassword(1L, "password", "password"));
        Assertions.assertEquals(UserErrorConstants.USER_NOT_EXIST.getMessage(), exception.getMessage());

        when(userInfoDao.selectById(any())).thenReturn(userEntity);
        exception = assertThrows(EsRuntimeException.class, () ->
                userServiceImpl.changePassword(1L, "oldPassword", "password"));
        Assertions.assertEquals(UserErrorConstants.OLD_PASSWORD_WRONG.getMessage(), exception.getMessage());
    }

    @Test
    void testResetPassword() {
        UserEntity userEntity = new UserEntity();
        userEntity.setPhone("phone");
        when(userInfoDao.selectByPhone(any())).thenReturn(userEntity);
        when(userInfoDao.updateById(any())).thenReturn(1);

        userServiceImpl.resetPassword("phone", "password");

        when(userInfoDao.selectByPhone(any())).thenReturn(null);
        EsRuntimeException exception = assertThrows(EsRuntimeException.class, () ->
                userServiceImpl.resetPassword("phone", "password"));
        Assertions.assertEquals(UserErrorConstants.USER_NOT_EXIST.getMessage(), exception.getMessage());
    }

    @Test
    void testSetDefaultOrg() {
        UserEntity userEntity = new UserEntity();
        userEntity.setId(1L);
        when(userInfoDao.selectById(any())).thenReturn(userEntity);
        OrgRelEntity orgRelEntity = new OrgRelEntity();
        orgRelEntity.setOrgId(1L);
        when(userOrgRelDao.selectByUserId(any())).thenReturn(List.of(orgRelEntity));
        when(userInfoDao.updateById(any())).thenReturn(1);

        userServiceImpl.setDefaultOrg(1L);

        userEntity.setPosition("2");
        userServiceImpl.setDefaultOrg(1L);
    }
}
