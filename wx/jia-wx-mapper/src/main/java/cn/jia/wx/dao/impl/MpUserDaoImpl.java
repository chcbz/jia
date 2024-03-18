package cn.jia.wx.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.util.BeanUtil;
import cn.jia.wx.dao.MpUserDao;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.mapper.MpUserMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-01-09
 */
@Named
public class MpUserDaoImpl extends BaseDaoImpl<MpUserMapper, MpUserEntity> implements MpUserDao {
    @Override
    public int unsubscribe(MpUserEntity example) {
        UpdateWrapper<MpUserEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.allEq(BeanUtil.toMap(example), false).lambda().set(MpUserEntity::getSubscribe, 0);
        return baseMapper.update(null, updateWrapper);
    }
}
