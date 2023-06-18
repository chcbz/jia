package cn.jia.wx.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.wx.entity.PayInfo;
import cn.jia.wx.mapper.PayInfoMapper;
import cn.jia.wx.service.IPayInfoService;
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
public class PayInfoServiceImpl extends BaseServiceImpl<PayInfoMapper, PayInfo> implements IPayInfoService {

}
