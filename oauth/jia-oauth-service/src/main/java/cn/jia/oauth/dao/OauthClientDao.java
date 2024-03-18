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
    OauthClientEntity selectByAppcn(String appcn);
}
