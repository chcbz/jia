package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserOrgDao;
import cn.jia.user.entity.OrgEntity;
import cn.jia.user.mapper.OrgMapper;
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
public class UserOrgDaoImpl extends BaseDaoImpl<OrgMapper, OrgEntity> implements UserOrgDao {

    @Override
    public OrgEntity findByCode(String code) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(OrgEntity.class).eq(OrgEntity::getCode, code));
    }

    @Override
    public List<OrgEntity> selectByParentId(Long parentId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(OrgEntity.class).eq(OrgEntity::getPId, parentId));
    }

    @Override
    public List<OrgEntity> selectByUserId(Long userId) {
        return baseMapper.selectByUserId(userId);
    }

    @Override
    public OrgEntity findFirstChild(Long id) {
        return baseMapper.findFirstChild(id);
    }

    @Override
    public String findDirector(Long orgId, Long roleId) {
        return baseMapper.findDirector(orgId, roleId);
    }

    @Override
    public OrgEntity findPreOrg(Long id) {
        return baseMapper.findPreOrg(id);
    }

    @Override
    public OrgEntity findNextOrg(Long id) {
        return baseMapper.findNextOrg(id);
    }

    @Override
    public OrgEntity findParentOrg(Long id) {
        return baseMapper.findParentOrg(id);
    }
}
