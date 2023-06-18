package cn.jia.wx.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.wx.entity.PayOrder;
import cn.jia.wx.mapper.PayOrderMapper;
import cn.jia.wx.service.IPayOrderService;
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
public class PayOrderServiceImpl extends BaseServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

}
