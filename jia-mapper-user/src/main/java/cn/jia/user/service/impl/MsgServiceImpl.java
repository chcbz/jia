package cn.jia.user.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.user.entity.MsgEntity;
import cn.jia.user.mapper.MsgMapper;
import cn.jia.user.service.IMsgService;
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
public class MsgServiceImpl extends BaseServiceImpl<MsgMapper, MsgEntity> implements IMsgService {

}
