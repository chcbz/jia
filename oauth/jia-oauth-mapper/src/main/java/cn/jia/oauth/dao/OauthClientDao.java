package cn.jia.oauth.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.oauth.entity.OauthClientEntity;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
public interface OauthClientDao extends IBaseDao<OauthClientEntity> {
    /**
     * 根据应用标识符查询客户端
     *
     * @param appcn 应用标识符
     * @return 客户端实体
     */
    OauthClientEntity selectByAppcn(String appcn);
}
