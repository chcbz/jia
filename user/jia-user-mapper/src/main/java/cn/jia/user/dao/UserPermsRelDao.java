package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.PermsRelEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserPermsRelDao extends IBaseDao<PermsRelEntity> {

    List<PermsRelEntity> selectByRoleId(Long roleId);

    List<PermsRelEntity> selectByUserId(Long userId);
}
