package cn.jia.oauth.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.oauth.dao.OauthApiKeyDao;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.stereotype.Service;

/**
 * OAuth API密钥服务实现类
 * 实现API密钥的业务逻辑处理方法
 */
@Service
public class ApiKeyServiceImpl extends BaseServiceImpl<OauthApiKeyDao, OauthApiKeyEntity> implements ApiKeyService {

    /**
     * 根据API密钥查询实体
     *
     * @param apiKey API密钥
     * @return API密钥实体，未找到返回null
     */
    @Override
    public OauthApiKeyEntity findByApiKey(String apiKey) {
        return baseDao.selectByApiKey(apiKey);
    }
}
