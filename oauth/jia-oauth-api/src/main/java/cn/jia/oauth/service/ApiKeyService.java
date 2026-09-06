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

    /**
     * Disables one active managed key only when every non-secret association field matches exactly.
     * Callers that couple revocation to another domain mutation must invoke this inside that same transaction.
     *
     * @return {@code true} only when the active row was changed by this CAS
     */
    boolean disableManagedKey(String keyId, String tenantId, String clientId, String jiacn, String keyName);
}
