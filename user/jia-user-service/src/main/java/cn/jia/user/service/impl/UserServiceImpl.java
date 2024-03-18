package cn.jia.user.service.impl;

import cn.jia.core.configuration.SpringContextHolder;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.ImgUtil;
import cn.jia.core.util.StringUtils;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.service.LdapUserService;
import cn.jia.user.common.UserConstants;
import cn.jia.user.common.UserErrorConstants;
import cn.jia.user.dao.UserGroupRelDao;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.dao.UserOrgRelDao;
import cn.jia.user.dao.UserRoleRelDao;
import cn.jia.user.entity.*;
import cn.jia.user.service.UserService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.jia.core.util.CollectionUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class UserServiceImpl extends BaseServiceImpl<UserInfoDao, UserEntity> implements UserService {
    @Autowired
    private UserRoleRelDao userRoleRelDao;
    @Autowired
    private UserOrgRelDao userOrgRelDao;
    @Autowired
    private UserGroupRelDao userGroupRelDao;
    @Autowired(required = false)
    private LdapUserService ldapUserService;

    @Override
    @Transactional
    public UserEntity create(UserEntity user) {
        //查找LDAP服务器是否有该用户
        LdapUser params = new LdapUser();
        params.setTelephoneNumber(user.getPhone());
        params.setEmail(user.getEmail());
        params.setOpenid(user.getOpenid());

        List<LdapUser> ldapUserResult = ldapUserService.search(params);
        if (CollectionUtil.isNotNullOrEmpty(ldapUserResult)) {
            user.setJiacn(String.valueOf(ldapUserResult.get(0).getUid()));
        } else {
            //将用户添加到ldap服务器
            params = new LdapUser();
            String cn = StringUtils.isEmpty(user.getPhone()) ?
                    (StringUtils.isEmpty(user.getEmail()) ? user.getOpenid() : user.getEmail()) :
                    user.getPhone();
            params.setUid(cn);
            params.setCn(cn);
            params.setSn(cn);
            params.setTelephoneNumber(StringUtils.isEmpty(user.getPhone()) ? null : user.getPhone());
            params.setEmail(StringUtils.isEmpty(user.getEmail()) ? null : user.getEmail());
            params.setOpenid(StringUtils.isEmpty(user.getOpenid()) ? null : user.getOpenid());
            params.setCountry(StringUtils.isEmpty(user.getCountry()) ? null : user.getCountry());
            params.setProvince(StringUtils.isEmpty(user.getProvince()) ? null : user.getProvince());
            params.setCity(StringUtils.isEmpty(user.getCity()) ? null : user.getCity());
            params.setSex(user.getSex());
            params.setNickname(StringUtils.isEmpty(user.getNickname()) ? null : user.getNickname());
            if (user.getAvatar() != null) {
                String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
                params.setHeadimg(ImgUtil.fromFile(new File(filePath + "/" + user.getAvatar())));
            }
            ldapUserService.create(params);
            user.setJiacn(params.getUid());
        }
        //查找本地数据库是否有该用户，如果没有则新增，如果有则更新
        UserEntity searchUser = new UserEntity();
        searchUser.setJiacn(user.getJiacn());
        searchUser.setPhone(StringUtils.isEmpty(user.getPhone()) ? null : user.getPhone());
        searchUser.setEmail(StringUtils.isEmpty(user.getEmail()) ? null : user.getEmail());
        searchUser.setOpenid(StringUtils.isEmpty(user.getOpenid()) ? null : user.getOpenid());
        List<UserEntity> curUser = baseDao.searchByExample(searchUser);
        if (CollectionUtil.isNotNullOrEmpty(curUser)) {
            throw new EsRuntimeException(UserErrorConstants.USER_HAS_EXIST);
        } else {
            baseDao.insert(user);
            //设置默认角色
            RoleRelEntity rel = new RoleRelEntity();
            rel.setRoleId(UserConstants.DEFAULT_ROLE_ID);
            rel.setUserId(user.getId());
            userRoleRelDao.insert(rel);
        }
        return user;
    }

    /**
     * 增加或减少用户积分
     */
    @Override
    public void changePoint(String jiacn, int add) {
        UserEntity user = baseDao.selectByJiacn(jiacn);
        if (user == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        UserEntity upUser = new UserEntity();
        upUser.setId(user.getId());
        upUser.setPoint(user.getPoint() + add);
        if (upUser.getPoint() < 0) {
            throw new EsRuntimeException(UserErrorConstants.POINT_NO_ENOUGH);
        }
        baseDao.updateById(upUser);
    }

    @Override
    public void changeRole(UserVO user) {
        //查询用户当前角色
        List<RoleRelEntity> roleRelList = userRoleRelDao.selectByUserId(user.getId());
        List<RoleRelEntity> addList = new ArrayList<>(); //需要添加的角色
        List<RoleRelEntity> cancelList = new ArrayList<>(); //需要取消的角色
        //查找需要添加的角色
        user.getRoleIds().stream().filter(roleId -> roleRelList.stream().noneMatch(roleRel ->
                roleId.equals(roleRel.getRoleId()))).forEach(roleId -> {
            RoleRelEntity rel = new RoleRelEntity();
            rel.setUserId(user.getId());
            rel.setRoleId(roleId);
            addList.add(rel);
        });
        if (addList.size() > 0) {
            userRoleRelDao.insertBatch(addList);
        }

        //查找需要取消的角色
        roleRelList.stream().filter(roleRel ->
                !user.getRoleIds().contains(roleRel.getRoleId())).forEach(cancelList::add);
        if (cancelList.size() > 0) {
            userRoleRelDao.deleteBatchIds(cancelList);
        }
    }

    @Override
    @Transactional
    public void changeGroup(UserVO user) {
        // 查询用户当前组
        List<GroupRelEntity> groupRelList = userGroupRelDao.selectByUserId(user.getId());
        List<GroupRelEntity> addList = new ArrayList<>(); // 需要添加的组
        List<GroupRelEntity> cancelList = new ArrayList<>(); // 需要取消的组
        // 查找需要添加的组
        user.getGroupIds().stream().filter(groupId -> groupRelList.stream().noneMatch(groupRel ->
                groupId.equals(groupRel.getGroupId()))).forEach(groupId -> {
            GroupRelEntity rel = new GroupRelEntity();
            rel.setUserId(user.getId());
            rel.setGroupId(groupId);
            addList.add(rel);
        });
        if (addList.size() > 0) {
            userGroupRelDao.insertBatch(addList);
        }

        // 查找需要取消的组
        groupRelList.stream().filter(groupRel ->
                !user.getGroupIds().contains(groupRel.getGroupId())).forEach(cancelList::add);
        if (cancelList.size() > 0) {
            userGroupRelDao.deleteBatchIds(cancelList);
        }
    }

    @Override
    public void changeOrg(UserVO user) {
        //查询用户当前组织
        List<OrgRelEntity> orgRelList = userOrgRelDao.selectByUserId(user.getId());
        List<OrgRelEntity> addList = new ArrayList<>(); //需要添加的组织
        List<OrgRelEntity> cancelList = new ArrayList<>(); //需要取消的组织
        //查找需要添加的组织
        user.getOrgIds().stream().filter(orgId -> orgRelList.stream().noneMatch(orgRel ->
                orgId.equals(orgRel.getOrgId()))).forEach(orgId -> {
            OrgRelEntity rel = new OrgRelEntity();
            rel.setUserId(user.getId());
            rel.setOrgId(orgId);
            addList.add(rel);
        });
        if (addList.size() > 0) {
            userOrgRelDao.insertBatch(addList);
        }

        //查找需要取消的组织
        orgRelList.stream().filter(orgRel -> !user.getOrgIds().contains(orgRel.getOrgId())).forEach(cancelList::add);
        if (cancelList.size() > 0) {
            userOrgRelDao.deleteBatchIds(cancelList);
        }
    }

    @Override
    public PageInfo<UserEntity> listByRoleId(Long roleId, int pageSize, int pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(baseDao.selectByRole(roleId));
    }

    @Override
    public PageInfo<UserEntity> listByGroupId(Long groupId, int pageSize, int pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(baseDao.selectByGroup(groupId));
    }

    @Override
    public PageInfo<UserEntity> listByOrgId(Long orgId, int pageSize, int pageNum) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(baseDao.selectByOrg(orgId));
    }

    @Override
    public UserEntity findByJiacn(String jiacn) {
        return baseDao.selectByJiacn(jiacn);
    }

    @Override
    public UserEntity findByOpenid(String openid) {
        return baseDao.selectByOpenid(openid);
    }

    @Override
    public UserEntity findByPhone(String phone) {
        return baseDao.selectByPhone(phone);
    }

    @Override
    public PageInfo<UserEntity> search(UserEntity user, int pageNo, int pageSize) {
        PageHelper.startPage(pageNo, pageSize);
        return PageInfo.of(baseDao.searchByExample(user));
    }

    @Override
    public void sync(List<UserEntity> userList) {
        for (UserEntity user : userList) {
            //查找LDAP服务器是否有该用户
            LdapUser ldapUser = null;
            LdapUser params = new LdapUser();
            params.setTelephoneNumber(user.getPhone());
            params.setEmail(user.getEmail());
            params.setOpenid(user.getOpenid());

            List<LdapUser> ldapUserResult = ldapUserService.search(params);
            if (ldapUserResult.size() > 0) {
                ldapUser = ldapUserResult.get(0);
                ldapUser.setTelephoneNumber(StringUtils.isEmpty(user.getPhone()) ? ldapUser.getTelephoneNumber() : user.getPhone());
                ldapUser.setEmail(StringUtils.isEmpty(user.getEmail()) ? ldapUser.getEmail() : user.getEmail());
                ldapUser.setOpenid(StringUtils.isEmpty(user.getOpenid()) ? ldapUser.getOpenid() : user.getOpenid());
                ldapUser.setCountry(StringUtils.isEmpty(user.getCountry()) ? ldapUser.getCountry() : user.getCountry());
                ldapUser.setProvince(StringUtils.isEmpty(user.getProvince()) ? ldapUser.getProvince() : user.getProvince());
                ldapUser.setCity(StringUtils.isEmpty(user.getCity()) ? ldapUser.getCity() : user.getCity());
                ldapUser.setSex(user.getSex() == null ? ldapUser.getSex() : user.getSex());
                ldapUser.setNickname(StringUtils.isEmpty(user.getNickname()) ? ldapUser.getNickname() : user.getNickname());
                if (StringUtils.isNotEmpty(user.getAvatar())) {
                    String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
                    ldapUser.setHeadimg(ImgUtil.fromFile(new File(filePath + "/" + user.getAvatar())));
                }
                ldapUserService.modifyLdapUser(ldapUser);
                user.setJiacn(String.valueOf(ldapUser.getUid()));
            }
            //将用户添加到ldap服务器
            if (ldapUser == null) {
                params = new LdapUser();
                String cn = StringUtils.isEmpty(user.getPhone()) ? (StringUtils.isEmpty(user.getEmail()) ?
                        user.getOpenid() : user.getEmail()) : user.getPhone();
                params.setCn(cn);
                params.setSn(cn);
                params.setTelephoneNumber(StringUtils.isEmpty(user.getPhone()) ? null : user.getPhone());
                params.setEmail(StringUtils.isEmpty(user.getEmail()) ? null : user.getEmail());
                params.setOpenid(StringUtils.isEmpty(user.getOpenid()) ? null : user.getOpenid());
                params.setCountry(StringUtils.isEmpty(user.getCountry()) ? null : user.getCountry());
                params.setProvince(StringUtils.isEmpty(user.getProvince()) ? null : user.getProvince());
                params.setCity(StringUtils.isEmpty(user.getCity()) ? null : user.getCity());
                params.setSex(user.getSex());
                params.setNickname(StringUtils.isEmpty(user.getNickname()) ? null : user.getNickname());
                if (user.getAvatar() != null) {
                    String filePath = SpringContextHolder.getProperty("jia.file.path", String.class);
                    params.setHeadimg(ImgUtil.fromFile(new File(filePath + "/" + user.getAvatar())));
                }
                ldapUserService.create(params);
                user.setJiacn(params.getUid());
            }

            //查找本地数据库是否有该用户，如果没有则新增，如果有则更新
            UserEntity searchUser = new UserEntity();
            searchUser.setJiacn(user.getJiacn());
            searchUser.setPhone(user.getPhone());
            searchUser.setEmail(user.getEmail());
            searchUser.setOpenid(user.getOpenid());
            List<UserEntity> curUser = baseDao.searchByExample(searchUser);
            if (CollectionUtil.isNullOrEmpty(curUser)) {
                //新增用户
                baseDao.insert(user);
                //设置默认角色
                RoleRelEntity rel = new RoleRelEntity();
                rel.setRoleId(UserConstants.DEFAULT_ROLE_ID);
                rel.setUserId(user.getId());
                userRoleRelDao.insert(rel);
            } else {
                UserEntity cu = curUser.get(0);
                user.setId(cu.getId());
                List<String> subscribe = new ArrayList<>(Arrays.asList(cu.getSubscribe().split(",")));
                if (!subscribe.contains(user.getSubscribe())) {
                    subscribe.add(user.getSubscribe());
                    user.setSubscribe(String.join(",", subscribe));
                }
                baseDao.updateById(user);
            }
        }
    }

    @Override
    public List<Long> findRoleIds(Long userId) {
        List<RoleRelEntity> roleRelList = userRoleRelDao.selectByUserId(userId);
        List<Long> roleIds = new ArrayList<>();
        for (RoleRelEntity rel : roleRelList) {
            roleIds.add(rel.getRoleId());
        }
        return roleIds;
    }

    @Override
    public List<Long> findOrgIds(Long userId) {
        List<OrgRelEntity> orgRelList = userOrgRelDao.selectByUserId(userId);
        List<Long> orgIds = new ArrayList<>();
        for (OrgRelEntity rel : orgRelList) {
            orgIds.add(rel.getOrgId());
        }
        return orgIds;
    }

    @Override
    public List<Long> findGroupIds(Long userId) {
        List<GroupRelEntity> groupRelList = userGroupRelDao.selectByUserId(userId);
        List<Long> groupIds = new ArrayList<>();
        for (GroupRelEntity rel : groupRelList) {
            groupIds.add(rel.getGroupId());
        }
        return groupIds;
    }

    @Override
    public UserEntity findByUsername(String username) {
        return baseDao.selectByUsername(username);
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        UserEntity user = baseDao.selectById(userId);
        if (user == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }
        if (!user.getPassword().equals(oldPassword)) {
            throw new EsRuntimeException(UserErrorConstants.OLD_PASSWORD_WRONG);
        }
        UserEntity upUser = new UserEntity();
        upUser.setId(userId);
        upUser.setPassword(newPassword);
        baseDao.updateById(upUser);
    }

    @Override
    public void resetPassword(String phone, String newPassword) {
        UserEntity user = baseDao.selectByPhone(phone);
        if (user == null) {
            throw new EsRuntimeException(UserErrorConstants.USER_NOT_EXIST);
        }

        UserEntity upUser = new UserEntity();
        upUser.setId(user.getId());
        upUser.setPassword(newPassword);
        baseDao.updateById(upUser);
    }

    @Override
    public void setDefaultOrg(Long userId) {
        UserEntity user = baseDao.selectById(userId);
        List<OrgRelEntity> orgRelList = userOrgRelDao.selectByUserId(userId);
        if (orgRelList.size() > 0) {
            UserEntity upUser = new UserEntity();
            upUser.setId(user.getId());
            //如果用户还没有设置默认职位，则默认第一个职位
            if (user.getPosition() == null) {
                upUser.setPosition(String.valueOf(orgRelList.get(0).getOrgId()));
                baseDao.updateById(upUser);
            } else { //如果用户当前职位已失效，则默认第一个职位
                boolean avail = false;
                for (OrgRelEntity rel : orgRelList) {
                    if (user.getPosition().equals(String.valueOf(rel.getOrgId()))) {
                        avail = true;
                        break;
                    }
                }
                if (!avail) {
                    upUser.setPosition(String.valueOf(orgRelList.get(0).getOrgId()));
                    baseDao.updateById(upUser);
                }
            }
        }
    }
}
