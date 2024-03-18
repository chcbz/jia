package cn.jia.oauth.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.oauth.entity.OauthResourceEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
public interface OauthResourceDao extends IBaseDao<OauthResourceEntity> {
    List<OauthResourceEntity> selectByExample(OauthResourceEntity record);
}
