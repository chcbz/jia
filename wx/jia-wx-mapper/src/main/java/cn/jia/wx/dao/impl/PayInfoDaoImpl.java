package cn.jia.wx.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.wx.dao.PayInfoDao;
import cn.jia.wx.entity.PayInfoEntity;
import cn.jia.wx.mapper.PayInfoMapper;
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
public class PayInfoDaoImpl extends BaseDaoImpl<PayInfoMapper, PayInfoEntity> implements PayInfoDao {

}
