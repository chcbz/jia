package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.AuthEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserAuthDao extends IBaseDao<AuthEntity> {

    List<AuthEntity> selectByRoleId(Long roleId);

    List<AuthEntity> selectByUserId(Long userId);
}
