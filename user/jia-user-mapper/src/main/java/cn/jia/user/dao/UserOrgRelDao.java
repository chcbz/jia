package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.OrgRelEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserOrgRelDao extends IBaseDao<OrgRelEntity> {

    List<OrgRelEntity> selectByUserId(Long userId);

    List<OrgRelEntity> selectByOrgId(Long orgId);
}
