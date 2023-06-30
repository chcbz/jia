package cn.jia.wx.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.mapper.MpUserMapper;
import cn.jia.wx.service.IMpUserService;
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
public class MpUserServiceImpl extends BaseServiceImpl<MpUserMapper, MpUserEntity> implements IMpUserService {

}
