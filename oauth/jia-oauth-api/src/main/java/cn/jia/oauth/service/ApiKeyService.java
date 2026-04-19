package cn.jia.oauth.service;

import cn.jia.common.service.IBaseService;
import cn.jia.oauth.entity.OauthApiKeyEntity;

/**
 * OAuth API密钥服务接口
 * 提供API密钥的业务逻辑处理方法
 */
public interface ApiKeyService extends IBaseService<OauthApiKeyEntity> {
    /**
     * 根据API密钥查询
     *
     * @param apiKey API密钥
     * @return API密钥实体，未找到返回null
     */
    OauthApiKeyEntity findByApiKey(String apiKey);
}
