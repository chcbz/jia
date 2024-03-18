package cn.jia.oauth.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.oauth.dao.OauthActionDao;
import cn.jia.oauth.entity.OauthActionEntity;
import cn.jia.oauth.mapper.OauthActionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
@Named
public class OauthActionDaoImpl extends BaseDaoImpl<OauthActionMapper, OauthActionEntity> implements OauthActionDao {

    @Override
    public List<OauthActionEntity> selectByResourceId(String resourceId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(OauthActionEntity.class)
                .eq(OauthActionEntity::getResourceId, resourceId));
    }
}
