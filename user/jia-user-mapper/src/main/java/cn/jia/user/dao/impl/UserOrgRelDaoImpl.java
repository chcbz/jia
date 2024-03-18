package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserOrgRelDao;
import cn.jia.user.entity.OrgRelEntity;
import cn.jia.user.mapper.OrgRelMapper;
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
public class UserOrgRelDaoImpl extends BaseDaoImpl<OrgRelMapper, OrgRelEntity> implements UserOrgRelDao {

    @Override
    public List<OrgRelEntity> selectByUserId(Long userId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(OrgRelEntity.class).eq(OrgRelEntity::getUserId, userId));
    }

    @Override
    public List<OrgRelEntity> selectByOrgId(Long orgId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(OrgRelEntity.class).eq(OrgRelEntity::getOrgId, orgId));
    }
}
