package cn.jia.user.service.impl;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.ImgUtil;
import cn.jia.core.util.PasswordUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.isp.entity.LdapUser;
import cn.jia.isp.entity.LdapUserGroup;
import cn.jia.isp.enums.IspFileTypeEnum;
import cn.jia.isp.service.FileService;
import cn.jia.isp.service.LdapUserGroupService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.jia.core.util.CollectionUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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
    @Autowired(required = false)
    private LdapUserGroupService ldapUserGroupService;
    @Autowired(required = false)
    private FileService fileService;

    @Value("${jia.file.path:}")
    private String filePath;

    @Override
    @Transactional
    public UserEntity create(UserEntity user) {
        // 处理LDAP用户
        handleLdapUserCreation(user);
        
        // 检查用户是否已存在
        if (isUserExist(user)) {
            throw new EsRuntimeException(UserErrorConstants.USER_HAS_EXIST);
        }
        
        // 创建新用户
        createUserWithDefaultRole(user);
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
        handleChangeRelation(user.getId(), user.getRoleIds(), 
            userRoleRelDao::selectByUserId,
            roleId -> {
                RoleRelEntity rel = new RoleRelEntity();
                rel.setUserId(user.getId());
                rel.setRoleId(roleId);
                return rel;
            },
            userRoleRelDao::insertBatch,
            userRoleRelDao::deleteBatchIds
        );
    }

    @Override
    @Transactional
    public void changeGroup(UserVO user) {
        handleChangeRelation(user.getId(), user.getGroupIds(),
            userGroupRelDao::selectByUserId,
            groupId -> {
                GroupRelEntity rel = new GroupRelEntity();
                rel.setUserId(user.getId());
                rel.setGroupId(groupId);
                return rel;
            },
            userGroupRelDao::insertBatch,
            userGroupRelDao::deleteBatchIds
        );
    }

    @Override
    public void changeOrg(UserVO user) {
        handleChangeRelation(user.getId(), user.getOrgIds(),
            userOrgRelDao::selectByUserId,
            orgId -> {
                OrgRelEntity rel = new OrgRelEntity();
                rel.setUserId(user.getId());
                rel.setOrgId(orgId);
                return rel;
            },
            userOrgRelDao::insertBatch,
            userOrgRelDao::deleteBatchIds
        );
    }

    @Override
    public PageInfo<UserEntity> listByRoleId(Long roleId, int pageNum, int pageSize, String orderBy) {
        return getPageInfo(pageNum, pageSize, orderBy, () -> baseDao.selectByRole(roleId));
    }

    @Override
    public PageInfo<UserEntity> listByGroupId(Long groupId, int pageNum, int pageSize, String orderBy) {
        return getPageInfo(pageNum, pageSize, orderBy, () -> baseDao.selectByGroup(groupId));
    }

    @Override
    public PageInfo<UserEntity> listByOrgId(Long orgId, int pageNum, int pageSize, String orderBy) {
        return getPageInfo(pageNum, pageSize, orderBy, () -> baseDao.selectByOrg(orgId));
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
    public List<UserEntity> search(UserEntity user) {
        return baseDao.searchByExample(user);
    }

    @Override
    public PageInfo<UserEntity> search(UserEntity user, int pageNum, int pageSize, String orderBy) {
        return getPageInfo(pageNum, pageSize, orderBy, () -> baseDao.searchByExample(user));
    }

    @Override
    public void sync(List<UserEntity> userList) {
        for (UserEntity user : userList) {
            upsert(user);
        }
    }

    @Override
    public UserEntity upsert(UserEntity user) {
        // 处理LDAP用户
        handleLdapUserUpsert(user);
        
        // 处理本地用户
        handleLocalUserUpsert(user);
        return user;
    }

    @Override
    public List<Long> findRoleIds(Long userId) {
        return extractIds(userRoleRelDao.selectByUserId(userId), RoleRelEntity::getRoleId);
    }

    @Override
    public List<Long> findOrgIds(Long userId) {
        return extractIds(userOrgRelDao.selectByUserId(userId), OrgRelEntity::getOrgId);
    }

    @Override
    public List<Long> findGroupIds(Long userId) {
        return extractIds(userGroupRelDao.selectByUserId(userId), GroupRelEntity::getGroupId);
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
        if (!PasswordUtil.validatePassword(oldPassword, user.getPassword())) {
            throw new EsRuntimeException(UserErrorConstants.OLD_PASSWORD_WRONG);
        }
        UserEntity upUser = new UserEntity();
        upUser.setId(userId);
        upUser.setPassword(PasswordUtil.encode(newPassword));
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
        upUser.setPassword(PasswordUtil.encode(newPassword));
        baseDao.updateById(upUser);
    }

    @Override
    public void setDefaultOrg(Long userId) {
        UserEntity user = baseDao.selectById(userId);
        List<OrgRelEntity> orgRelList = userOrgRelDao.selectByUserId(userId);
        if (!orgRelList.isEmpty()) {
            UserEntity upUser = new UserEntity();
            upUser.setId(user.getId());
            //如果用户还没有设置默认职位，则默认第一个职位
            if (user.getPosition() == null) {
                upUser.setPosition(String.valueOf(orgRelList.getFirst().getOrgId()));
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
                    upUser.setPosition(String.valueOf(orgRelList.getFirst().getOrgId()));
                    baseDao.updateById(upUser);
                }
            }
        }
    }

    /**
     * 处理LDAP用户创建
     * @param user 用户实体
     */
    private void handleLdapUserCreation(UserEntity user) {
        LdapUser params = new LdapUser();
        params.setTelephoneNumber(user.getPhone());
        params.setEmail(user.getEmail());
        params.setOpenid(user.getOpenid());
        params.setWeixinid(user.getWeixinid());
        params.setGithubid(user.getGithubid());

        List<LdapUser> ldapUserResult = ldapUserService.search(params);
        if (CollectionUtil.isNotNullOrEmpty(ldapUserResult)) {
            user.setJiacn(String.valueOf(ldapUserResult.getFirst().getUid()));
        } else {
            String cn = StringUtil.isEmpty(user.getPhone()) ?
                    (StringUtil.isEmpty(user.getEmail()) ? user.getOpenid() : user.getEmail()) :
                    user.getPhone();
            params.setUid(cn);
            params.setCn(cn);
            params.setSn(cn);
            setLdapUserAttributes(params, user);
            ldapUserService.create(params);
            user.setJiacn(params.getUid());
        }
    }

    /**
     * 设置LDAP用户属性
     * @param ldapUser LDAP用户对象
     * @param user 用户实体
     */
    private void setLdapUserAttributes(LdapUser ldapUser, UserEntity user) {
        ldapUser.setTelephoneNumber(StringUtil.isEmpty(user.getPhone()) ? null : user.getPhone());
        ldapUser.setEmail(StringUtil.isEmpty(user.getEmail()) ? null : user.getEmail());
        ldapUser.setOpenid(StringUtil.isEmpty(user.getOpenid()) ? null : user.getOpenid());
        ldapUser.setWeixinid(StringUtil.isEmpty(user.getWeixinid()) ? null : user.getWeixinid());
        ldapUser.setGithubid(StringUtil.isEmpty(user.getGithubid()) ? null : user.getGithubid());
        ldapUser.setCountry(StringUtil.isEmpty(user.getCountry()) ? null : user.getCountry());
        ldapUser.setProvince(StringUtil.isEmpty(user.getProvince()) ? null : user.getProvince());
        ldapUser.setCity(StringUtil.isEmpty(user.getCity()) ? null : user.getCity());
        ldapUser.setSex(user.getSex());
        ldapUser.setNickname(StringUtil.isEmpty(user.getNickname()) ? null : user.getNickname());
        if (user.getAvatar() != null) {
            ldapUser.setHeadimg(ImgUtil.fromFile(new File(filePath + "/" + user.getAvatar())));
        }
    }

    /**
     * 检查用户是否已存在
     * @param user 用户实体
     * @return 是否存在
     */
    private boolean isUserExist(UserEntity user) {
        UserEntity searchUser = new UserEntity();
        searchUser.setJiacn(user.getJiacn());
        searchUser.setPhone(StringUtil.isEmpty(user.getPhone()) ? null : user.getPhone());
        searchUser.setEmail(StringUtil.isEmpty(user.getEmail()) ? null : user.getEmail());
        searchUser.setOpenid(StringUtil.isEmpty(user.getOpenid()) ? null : user.getOpenid());
        searchUser.setWeixinid(StringUtil.isEmpty(user.getWeixinid()) ? null : user.getWeixinid());
        searchUser.setGithubid(StringUtil.isEmpty(user.getGithubid()) ? null : user.getGithubid());
        List<UserEntity> curUser = baseDao.searchByExample(searchUser);
        return CollectionUtil.isNotNullOrEmpty(curUser);
    }

    /**
     * 创建用户并设置默认角色
     * @param user 用户实体
     */
    private void createUserWithDefaultRole(UserEntity user) {
        user.setPassword(StringUtil.isNotEmpty(user.getPassword()) ? PasswordUtil.encode(user.getPassword()) : null);
        baseDao.insert(user);
        RoleRelEntity rel = new RoleRelEntity();
        rel.setRoleId(UserConstants.DEFAULT_ROLE_ID);
        rel.setUserId(user.getId());
        userRoleRelDao.insert(rel);
    }

    /**
     * 处理关系变更的通用方法
     * @param userId 用户ID
     * @param newIds 新的关系ID列表
     * @param selectFunction 查询现有关系的函数
     * @param createFunction 创建关系实体的函数
     * @param insertFunction 批量插入函数
     * @param deleteFunction 批量删除函数
     * @param <T> 关系实体类型
     * @param <ID> ID类型
     */
    private <T, ID> void handleChangeRelation(
            Long userId,
            List<ID> newIds,
            java.util.function.Function<Long, List<T>> selectFunction,
            java.util.function.Function<ID, T> createFunction,
            java.util.function.Consumer<List<T>> insertFunction,
            java.util.function.Consumer<List<T>> deleteFunction) {
        
        List<T> currentRels = selectFunction.apply(userId);
        List<T> addList = new ArrayList<>();
        List<T> cancelList = new ArrayList<>();
        
        // 查找需要添加的关系
        newIds.stream()
            .filter(id -> currentRels.stream().noneMatch(rel -> isSameId(rel, id)))
            .map(createFunction)
            .forEach(addList::add);
        
        if (!addList.isEmpty()) {
            insertFunction.accept(addList);
        }
        
        // 查找需要取消的关系
        currentRels.stream()
            .filter(rel -> newIds.stream().noneMatch(id -> isSameId(rel, id)))
            .forEach(cancelList::add);
        
        if (!cancelList.isEmpty()) {
            deleteFunction.accept(cancelList);
        }
    }

    /**
     * 判断关系实体和ID是否匹配
     */
    private boolean isSameId(Object rel, Object id) {
        if (rel instanceof RoleRelEntity) {
            return ((RoleRelEntity) rel).getRoleId().equals(id);
        } else if (rel instanceof GroupRelEntity) {
            return ((GroupRelEntity) rel).getGroupId().equals(id);
        } else if (rel instanceof OrgRelEntity) {
            return ((OrgRelEntity) rel).getOrgId().equals(id);
        }
        return false;
    }

    /**
     * 获取分页信息的通用方法
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param dataSupplier 数据提供者
     * @return 分页信息
     */
    private PageInfo<UserEntity> getPageInfo(int pageNum, int pageSize, String orderBy, Supplier<List<UserEntity>> dataSupplier) {
        PageHelper.startPage(pageNum, pageSize, orderBy);
        return PageInfo.of(dataSupplier.get());
    }

    /**
     * 处理LDAP用户更新或插入
     * @param user 用户实体
     */
    private void handleLdapUserUpsert(UserEntity user) {
        LdapUser searchLdapUser = new LdapUser();
        searchLdapUser.setTelephoneNumber(user.getPhone());
        searchLdapUser.setEmail(user.getEmail());
        searchLdapUser.setOpenid(user.getOpenid());
        searchLdapUser.setWeixinid(user.getWeixinid());
        searchLdapUser.setGithubid(user.getGithubid());

        List<LdapUser> ldapUserResult = ldapUserService.search(searchLdapUser);
        if (!ldapUserResult.isEmpty()) {
            updateExistingLdapUser(ldapUserResult.getFirst(), user);
            user.setJiacn(String.valueOf(ldapUserResult.getFirst().getUid()));
        } else {
            createNewLdapUser(user);
        }
    }

    /**
     * 更新现有的LDAP用户
     * @param ldapUser LDAP用户
     * @param user 用户实体
     */
    private void updateExistingLdapUser(LdapUser ldapUser, UserEntity user) {
        ldapUser.setTelephoneNumber(StringUtil.isEmpty(user.getPhone()) ? ldapUser.getTelephoneNumber() : user.getPhone());
        ldapUser.setEmail(StringUtil.isEmpty(user.getEmail()) ? ldapUser.getEmail() : user.getEmail());
        ldapUser.setOpenid(StringUtil.isEmpty(user.getOpenid()) ? ldapUser.getOpenid() : user.getOpenid());
        ldapUser.setWeixinid(StringUtil.isEmpty(user.getWeixinid()) ? ldapUser.getWeixinid() : user.getWeixinid());
        ldapUser.setGithubid(StringUtil.isEmpty(user.getGithubid()) ? ldapUser.getGithubid() : user.getGithubid());
        ldapUser.setCountry(StringUtil.isEmpty(user.getCountry()) ? ldapUser.getCountry() : user.getCountry());
        ldapUser.setProvince(StringUtil.isEmpty(user.getProvince()) ? ldapUser.getProvince() : user.getProvince());
        ldapUser.setCity(StringUtil.isEmpty(user.getCity()) ? ldapUser.getCity() : user.getCity());
        ldapUser.setSex(user.getSex() == null ? ldapUser.getSex() : user.getSex());
        ldapUser.setNickname(StringUtil.isEmpty(user.getNickname()) ? ldapUser.getNickname() : user.getNickname());
        if (StringUtil.isNotEmpty(user.getAvatar())) {
            ldapUser.setHeadimg(ImgUtil.fromFile(new File(filePath + "/" + user.getAvatar())));
        }
        ldapUserService.modifyLdapUser(ldapUser);
    }

    /**
     * 创建新的LDAP用户
     * @param user 用户实体
     */
    private void createNewLdapUser(UserEntity user) {
        LdapUser ldapUser = new LdapUser();
        String cn = StringUtil.firstNotEmpty(user.getUsername(), user.getPhone(), user.getEmail(),
                user.getOpenid(), user.getWeixinid(), user.getGithubid());
        ldapUser.setCn(cn);
        ldapUser.setSn(cn);
        setLdapUserAttributes(ldapUser, user);
        ldapUserService.create(ldapUser);
        
        LdapUserGroup org = ldapUserGroupService.findByClientId(EsContextHolder.getContext().getClientId());
        if (org != null) {
            ldapUserGroupService.addUser(org, ldapUser.getDn());
        }
        user.setJiacn(ldapUser.getUid());
    }

    /**
     * 处理本地用户更新或插入
     * @param user 用户实体
     */
    private void handleLocalUserUpsert(UserEntity user) {
        UserEntity searchUser = new UserEntity();
        searchUser.setJiacn(user.getJiacn());
        searchUser.setPhone(user.getPhone());
        searchUser.setEmail(user.getEmail());
        searchUser.setOpenid(user.getOpenid());
        searchUser.setWeixinid(user.getWeixinid());
        searchUser.setGithubid(user.getGithubid());
        List<UserEntity> curUser = baseDao.searchByExample(searchUser);
        
        if (CollectionUtil.isNullOrEmpty(curUser)) {
            handleNewUserCreation(user);
        } else {
            handleExistingUserUpdate(user, curUser.getFirst());
        }
    }

    /**
     * 处理新用户创建
     * @param user 用户实体
     */
    private void handleNewUserCreation(UserEntity user) {
        String avatarFileUri = Optional.ofNullable(user.getAvatar()).orElse("");
        if (avatarFileUri.startsWith("http")) {
            IspFileEntity ispFileEntity = fileService.create(avatarFileUri,
                    IspFileTypeEnum.FILE_TYPE_AVATAR, user.getJiacn() + ".jpg");
            user.setAvatar(ispFileEntity.getUri());
        }
        user.setPassword(StringUtil.isNotEmpty(user.getPassword()) ? PasswordUtil.encode(user.getPassword()) : null);
        baseDao.insert(user);
        RoleRelEntity rel = new RoleRelEntity();
        rel.setRoleId(UserConstants.DEFAULT_ROLE_ID);
        rel.setUserId(user.getId());
        userRoleRelDao.insert(rel);
    }

    /**
     * 处理现有用户更新
     * @param user 用户实体
     * @param existingUser 现有用户
     */
    private void handleExistingUserUpdate(UserEntity user, UserEntity existingUser) {
        user.setId(existingUser.getId());
        List<String> subscribe = new ArrayList<>(Arrays.asList(existingUser.getSubscribe().split(",")));
        if (!subscribe.contains(user.getSubscribe())) {
            subscribe.add(user.getSubscribe());
            user.setSubscribe(String.join(",", subscribe));
        }
        baseDao.updateById(user);
    }

    /**
     * 提取ID列表的通用方法
     * @param entities 实体列表
     * @param idExtractor ID提取函数
     * @param <T> 实体类型
     * @param <ID> ID类型
     * @return ID列表
     */
    private <T, ID> List<ID> extractIds(List<T> entities, java.util.function.Function<T, ID> idExtractor) {
        List<ID> ids = new ArrayList<>();
        for (T entity : entities) {
            ids.add(idExtractor.apply(entity));
        }
        return ids;
    }
}
