package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.GroupEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserGroupDao extends IBaseDao<GroupEntity> {
    List<GroupEntity> selectByUserId(Long userId);

    GroupEntity selectByCode(String code);

}
