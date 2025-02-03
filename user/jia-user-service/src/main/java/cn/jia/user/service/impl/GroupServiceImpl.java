package cn.jia.user.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.user.dao.UserGroupDao;
import cn.jia.user.dao.UserGroupRelDao;
import cn.jia.user.dao.UserRoleRelDao;
import cn.jia.user.entity.GroupEntity;
import cn.jia.user.entity.GroupRelEntity;
import cn.jia.user.entity.GroupVO;
import cn.jia.user.entity.RoleRelEntity;
import cn.jia.user.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GroupServiceImpl extends BaseServiceImpl<UserGroupDao, GroupEntity> implements GroupService {
    @Autowired
    private UserGroupRelDao userGroupRelDao;
    @Autowired
    private UserRoleRelDao userRoleRelDao;

    @Override
    public List<GroupEntity> findByUserId(Long userId) {
        return baseDao.selectByUserId(userId);
    }

    @Override
    public void batchAddUser(GroupVO group) {
        // 查询用户当前角色
        List<GroupRelEntity> groupRelList = userGroupRelDao.selectByGroupId(group.getId());
        List<GroupRelEntity> addList = new ArrayList<>(); // 需要添加的角色
        // 查找需要添加的角色
        group.getUserIds().stream().filter(userId -> groupRelList.stream().noneMatch(groupRel ->
                userId.equals(groupRel.getUserId()))).forEach(userId -> {
            GroupRelEntity rel = new GroupRelEntity();
            rel.setUserId(userId);
            rel.setGroupId(group.getId());
            addList.add(rel);
        });
        if (!addList.isEmpty()) {
            userGroupRelDao.insertBatch(addList);
        }
    }

    @Override
    public void batchDelUser(GroupVO group) {
        // 查询用户当前角色
        List<GroupRelEntity> groupRelList = userGroupRelDao.selectByGroupId(group.getId());
        List<GroupRelEntity> cancelList = new ArrayList<>(); // 需要取消的角色

        // 查找需要取消的角色
        groupRelList.stream().filter(groupRel ->
                group.getUserIds().contains(groupRel.getUserId())).forEach(cancelList::add);
        if (!cancelList.isEmpty()) {
            userGroupRelDao.deleteBatchIds(cancelList);
        }
    }

    @Override
    public void changeRole(GroupVO group) {
        // 查询用户当前角色
        List<RoleRelEntity> roleRelList = userRoleRelDao.selectByGroupId(group.getId());
        List<RoleRelEntity> addList = new ArrayList<>(); // 需要添加的角色
        List<RoleRelEntity> cancelList = new ArrayList<>(); // 需要取消的角色
        // 查找需要添加的角色
        group.getRoleIds().stream().filter(roleId -> roleRelList.stream().noneMatch(roleRel ->
                roleId.equals(roleRel.getRoleId()))).forEach(roleId -> {
            RoleRelEntity rel = new RoleRelEntity();
            rel.setGroupId(group.getId());
            rel.setRoleId(roleId);
            addList.add(rel);
        });
        if (!addList.isEmpty()) {
            userRoleRelDao.insertBatch(addList);
        }

        // 查找需要取消的角色
        roleRelList.stream().filter(roleRel ->
                !group.getRoleIds().contains(roleRel.getRoleId())).forEach(cancelList::add);
        if (!cancelList.isEmpty()) {
            userRoleRelDao.deleteBatchIds(cancelList);
        }
    }

    @Override
    public List<Long> findRoleIds(Long groupId) {
        List<RoleRelEntity> roleRelList = userRoleRelDao.selectByGroupId(groupId);
        List<Long> roleIds = new ArrayList<>();
        for (RoleRelEntity rel : roleRelList) {
            roleIds.add(rel.getRoleId());
        }
        return roleIds;
    }
}
