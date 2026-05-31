package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.OrgEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserOrgDao extends IBaseDao<OrgEntity> {

    OrgEntity findByCode(String code);

    List<OrgEntity> selectByParentId(Long parentId);

    List<OrgEntity> selectByUserId(Long userId);

    OrgEntity findFirstChild(Long id);

    String findDirector(Long orgId, Long roleId);

    OrgEntity findPreOrg(Long id);

    OrgEntity findNextOrg(Long id);

    OrgEntity findParentOrg(Long id);

}
