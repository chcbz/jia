package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.RoleEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserRoleDao extends IBaseDao<RoleEntity> {

    List<RoleEntity> selectByUserId(Long userId);

    List<RoleEntity> selectByGroupId(Long groupId);

    RoleEntity selectByCode(String code);
}
