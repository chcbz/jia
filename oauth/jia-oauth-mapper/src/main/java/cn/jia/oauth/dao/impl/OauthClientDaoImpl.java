package cn.jia.oauth.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.oauth.dao.OauthClientDao;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.mapper.OauthClientMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

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
}
