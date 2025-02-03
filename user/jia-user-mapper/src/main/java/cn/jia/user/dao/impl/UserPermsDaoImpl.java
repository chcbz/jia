package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserPermsDao;
import cn.jia.user.entity.PermsEntity;
import cn.jia.user.mapper.PermsMapper;
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
public class UserPermsDaoImpl extends BaseDaoImpl<PermsMapper, PermsEntity> implements UserPermsDao {

    @Override
    public List<PermsEntity> selectByResourceId(String resourceId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(PermsEntity.class)
                .eq(PermsEntity::getResourceId, resourceId));
    }
}
