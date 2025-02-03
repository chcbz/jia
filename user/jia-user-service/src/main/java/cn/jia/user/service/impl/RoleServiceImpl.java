package cn.jia.user.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.user.dao.UserPermsRelDao;
import cn.jia.user.dao.UserRoleDao;
import cn.jia.user.dao.UserRoleRelDao;
import cn.jia.user.entity.PermsRelEntity;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.entity.RoleRelEntity;
import cn.jia.user.entity.RoleVO;
import cn.jia.user.service.RoleService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class RoleServiceImpl extends BaseServiceImpl<UserRoleDao, RoleEntity> implements RoleService {
    @Autowired
    private UserPermsRelDao userPermsRelDao;
    @Autowired
    private UserRoleRelDao userRoleRelDao;

    @Override
    @Transactional
    public void changePerms(RoleVO role) {
        //查询用户当前权限
        List<PermsRelEntity> authList = userPermsRelDao.selectByRoleId(role.getId());
        List<PermsRelEntity> addList = new ArrayList<>(); //需要添加的角色权限
        List<PermsRelEntity> cancelList = new ArrayList<>(); //需要取消的角色权限
        //查找需要添加的角色权限
        role.getPermsIds().stream().filter(permsId -> authList.stream().noneMatch(auth ->
                permsId.equals(auth.getPermsId()))).forEach(permsId -> {
            PermsRelEntity auth = new PermsRelEntity();
            auth.setRoleId(role.getId());
            auth.setPermsId(permsId);
            addList.add(auth);
        });
        if (addList.size() > 0) {
            userPermsRelDao.insertBatch(addList);
        }

        //查找需要取消的角色权限
        authList.stream().filter(auth -> !role.getPermsIds().contains(auth.getPermsId())).forEach(cancelList::add);
        if (cancelList.size() > 0) {
            userPermsRelDao.deleteBatchIds(cancelList);
        }
    }

    @Override
    public PageInfo<RoleEntity> listByUserId(Long userId, int pageSize, int pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(baseDao.selectByUserId(userId));
    }

    @Override
    public PageInfo<RoleEntity> listByGroupId(Long groupId, int pageSize, int pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(baseDao.selectByGroupId(groupId));
    }

    @Override
    public void batchAddUser(RoleVO role) {
        //查询用户当前角色
        List<RoleRelEntity> roleRelList = userRoleRelDao.selectByRoleId(role.getId());
        List<RoleRelEntity> addList = new ArrayList<>(); //需要添加的角色
        //查找需要添加的角色
        if (role.getUserIds() != null) {
            role.getUserIds().stream().filter(userId -> roleRelList.stream().noneMatch(roleRel ->
                    userId.equals(roleRel.getUserId()))).forEach(userId -> {
                RoleRelEntity rel = new RoleRelEntity();
                rel.setUserId(userId);
                rel.setRoleId(role.getId());
                rel.setClientId(role.getClientId());
                addList.add(rel);
            });
        }
        if (role.getGroupIds() != null) {
            role.getGroupIds().stream().filter(groupId -> roleRelList.stream().noneMatch(roleRel ->
                    groupId.equals(roleRel.getGroupId()))).forEach(groupId -> {
                RoleRelEntity rel = new RoleRelEntity();
                rel.setGroupId(groupId);
                rel.setRoleId(role.getId());
                rel.setClientId(role.getClientId());
                addList.add(rel);
            });
        }

        if (addList.size() > 0) {
            userRoleRelDao.insertBatch(addList);
        }
    }

    @Override
    public void batchDelUser(RoleVO role) {
        //查询用户当前角色
        List<RoleRelEntity> roleRelList = userRoleRelDao.selectByRoleId(role.getId());
        List<RoleRelEntity> cancelList = new ArrayList<>(); //需要取消的角色

        //查找需要取消的角色
        roleRelList.stream().filter(roleRel ->
                        roleRel.getUserId() != null && role.getUserIds().contains(roleRel.getUserId()))
                .forEach(cancelList::add);
        roleRelList.stream().filter(roleRel ->
                        roleRel.getGroupId() != null && role.getGroupIds().contains(roleRel.getGroupId()))
                .forEach(cancelList::add);
        if (cancelList.size() > 0) {
            userRoleRelDao.deleteBatchIds(cancelList);
        }
    }

    @Override
    public PageInfo<PermsRelEntity> listPerms(Long roleId, int pageSize, int pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(userPermsRelDao.selectByRoleId(roleId));
    }
}
