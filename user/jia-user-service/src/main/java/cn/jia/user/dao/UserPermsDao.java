package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.PermsEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserPermsDao extends IBaseDao<PermsEntity> {
    List<PermsEntity> selectByResourceId(String resourceId);
}
