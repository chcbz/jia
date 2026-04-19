package cn.jia.oauth.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.oauth.entity.OauthApiKeyEntity;

/**
 * OAuth API密钥数据访问接口
 * 提供API密钥的数据库操作方法
 */
public interface OauthApiKeyDao extends IBaseDao<OauthApiKeyEntity> {
    /**
     * 根据API密钥查询
     *
     * @param apiKey API密钥
     * @return API密钥实体，未找到返回null
     */
    OauthApiKeyEntity selectByApiKey(String apiKey);
}
