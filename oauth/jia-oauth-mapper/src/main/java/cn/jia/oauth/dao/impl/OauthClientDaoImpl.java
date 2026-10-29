package cn.jia.oauth.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.oauth.dao.OauthClientDao;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.mapper.OauthClientMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-07
 */
@Named
public class OauthClientDaoImpl extends BaseDaoImpl<OauthClientMapper, OauthClientEntity> implements OauthClientDao {

    @Override
    public OauthClientEntity selectByAppcn(String appcn) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(OauthClientEntity.class)
                .eq(OauthClientEntity::getAppcn, appcn));
    }
    @Override
    public OauthClientEntity selectExactByClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || !clientId.equals(clientId.strip())
                || clientId.length() > 50 || clientId.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        List<OauthClientEntity> matches = baseMapper.selectExactByClientId(clientId);
        return matches != null && matches.size() == 1 ? matches.getFirst() : null;
    }
}
