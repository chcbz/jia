package cn.jia.wx.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.wx.dao.PayOrderDao;
import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.mapper.PayOrderMapper;
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
public class PayOrderDaoImpl extends BaseDaoImpl<PayOrderMapper, PayOrderEntity> implements PayOrderDao {

}
