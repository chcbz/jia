package cn.jia.wx.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.wx.dao.MpInfoDao;
import cn.jia.wx.entity.MpInfoEntity;
import cn.jia.wx.mapper.MpInfoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
public class MpInfoDaoImpl extends BaseDaoImpl<MpInfoMapper, MpInfoEntity> implements MpInfoDao {

    @Override
    public MpInfoEntity findByKey(String key) {
        LambdaQueryWrapper<MpInfoEntity> queryWrapper =
                Wrappers.lambdaQuery(new MpInfoEntity()).eq(MpInfoEntity::getAppid, key)
                .or().eq(MpInfoEntity::getOriginal, key);
        return baseMapper.selectOne(queryWrapper);
    }
}
