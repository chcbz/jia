package cn.jia.oauth.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.oauth.entity.OauthActionEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface OauthActionDao extends IBaseDao<OauthActionEntity> {
    List<OauthActionEntity> selectByResourceId(String resourceId);
}
