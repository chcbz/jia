package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.RoleRelEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserRoleRelDao extends IBaseDao<RoleRelEntity> {
    /** 根据用户组ID获取用户角色列表
     *
     * @param groupId 用户组ID
     * @return 用户角色列表
     */
    List<RoleRelEntity> selectByGroupId(Long groupId);

    /** 根据用户ID获取用户角色列表
     *
     * @param userId 用户ID
     * @return 用户角色列表
     */
    List<RoleRelEntity> selectByUserId(Long userId);

    /** 根据角色ID获取用户角色列表
     *
     * @param roleId 角色ID
     * @return 用户角色列表
     */
    List<RoleRelEntity> selectByRoleId(Long roleId);

}
