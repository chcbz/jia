package cn.jia.oauth.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.oauth.dao.OauthApiKeyDao;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.mapper.OauthApiKeyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

/**
 * OAuth API密钥数据访问实现类
 * 实现API密钥的数据库操作方法
 */
@Named
public class OauthApiKeyDaoImpl extends BaseDaoImpl<OauthApiKeyMapper, OauthApiKeyEntity> implements OauthApiKeyDao {

    /**
     * 根据API密钥查询实体
     *
     * @param apiKey API密钥
     * @return API密钥实体，未找到返回null
     */
    @Override
    public OauthApiKeyEntity selectByApiKey(String apiKey) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(OauthApiKeyEntity.class)
                .eq(OauthApiKeyEntity::getApiKey, apiKey));
    }
}
