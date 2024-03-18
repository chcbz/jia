package cn.jia.wx.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.wx.dao.PayOrderDao;
import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.service.PayOrderService;
import org.springframework.stereotype.Service;

@Service
public class PayOrderServiceImpl extends BaseServiceImpl<PayOrderDao, PayOrderEntity> implements PayOrderService {
}