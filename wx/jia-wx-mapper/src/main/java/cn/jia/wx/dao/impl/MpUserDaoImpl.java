package cn.jia.wx.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.wx.dao.MpUserDao;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.mapper.MpUserMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.inject.Named;

import java.util.List;

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
    public List<MpUserEntity> selectByAppIdAndOpenIdExact(String appid, String openId) {
        return baseMapper.selectByAppIdAndOpenIdExact(appid, openId);
    }

    @Override
    public int unsubscribe(MpUserEntity example) {
        UpdateWrapper<MpUserEntity> updateWrapper = new UpdateWrapper<>();
        updateWrapper.lambda()
                .eq(example.getClientId() != null, MpUserEntity::getClientId, example.getClientId())
                .eq(example.getAppid() != null, MpUserEntity::getAppid, example.getAppid())
                .set(MpUserEntity::getSubscribe, 0);
        return baseMapper.update(null, updateWrapper);
    }
}
