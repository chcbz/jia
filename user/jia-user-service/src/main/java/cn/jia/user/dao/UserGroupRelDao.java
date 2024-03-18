package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.GroupRelEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserGroupRelDao extends IBaseDao<GroupRelEntity> {
    /**
     * 根据用户组ID获取用户列表
     *
     * @param groupId 用户组ID
     * @return 用户列表
     */
    List<GroupRelEntity> selectByGroupId(Long groupId);

    List<GroupRelEntity> selectByUserId(Long userId);

}
