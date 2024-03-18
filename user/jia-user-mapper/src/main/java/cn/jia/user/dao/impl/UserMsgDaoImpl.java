package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserMsgDao;
import cn.jia.user.entity.MsgEntity;
import cn.jia.user.mapper.MsgMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
@Named
public class UserMsgDaoImpl extends BaseDaoImpl<MsgMapper, MsgEntity> implements UserMsgDao {

    @Override
    public int updateByUserId(MsgEntity entity) {
        return baseMapper.update(entity, Wrappers.lambdaUpdate(MsgEntity.class).eq(MsgEntity::getUserId,
                entity.getUserId()));
    }
}
