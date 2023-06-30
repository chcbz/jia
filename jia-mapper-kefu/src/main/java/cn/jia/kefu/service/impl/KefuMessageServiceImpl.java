package cn.jia.kefu.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.kefu.entity.KefuMessageEntity;
import cn.jia.kefu.mapper.KefuMessageMapper;
import cn.jia.kefu.service.IKefuMessageService;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-01-29
 */
@Named
public class KefuMessageServiceImpl extends BaseServiceImpl<KefuMessageMapper, KefuMessageEntity> implements IKefuMessageService {

}
