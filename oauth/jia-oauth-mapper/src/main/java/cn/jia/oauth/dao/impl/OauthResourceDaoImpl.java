package cn.jia.oauth.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.oauth.dao.OauthResourceDao;
import cn.jia.oauth.entity.OauthResourceEntity;
import cn.jia.oauth.mapper.OauthResourceMapper;
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
public class OauthResourceDaoImpl extends BaseDaoImpl<OauthResourceMapper, OauthResourceEntity>
        implements OauthResourceDao {

    @Override
    public List<OauthResourceEntity> selectByExample(OauthResourceEntity record) {
        return baseMapper.selectByExample(record);
    }
}
